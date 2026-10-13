package com.liftr.vision.recording

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import com.liftr.domain.logging.Logger
import java.io.File
import java.nio.ByteBuffer

/**
 * Encodes a sequence of ARGB [Bitmap]s into an H.264 MP4 using MediaCodec +
 * MediaMuxer in ByteBuffer (non-Surface) mode.
 *
 * ByteBuffer mode is used deliberately for the offline annotated-video pass: it
 * avoids the EGL/OpenGL setup that a MediaCodec input Surface requires and is
 * deterministic across devices since we control the colour-format conversion
 * ourselves (no GPU compositing). Throughput is irrelevant here — this runs in
 * the background after the set, not on the camera hot path.
 *
 * Usage:
 * ```
 * encoder.start()
 * frames.forEach { encoder.encodeFrame(it) }
 * encoder.finish()   // drains, signals EOS, writes the moov atom
 * ```
 * Always call [release] (e.g. in a finally) to free codec resources.
 */
class AnnotatedVideoEncoder(
    private val outputFile: File,
    width: Int,
    height: Int,
    private val frameRate: Int,
    private val bitRate: Int,
    private val logger: Logger
) {
    // Encoders require even dimensions.
    private val width = width and 1.inv()
    private val height = height and 1.inv()

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    private var frameIndex = 0L
    private val bufferInfo = MediaCodec.BufferInfo()

    private val yuvBuffer = ByteArray(this.width * this.height * 3 / 2)

    fun start() {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(
            MediaFormat.createVideoFormat(mime, width, height)
        )
        val codec = if (codecName != null) {
            MediaCodec.createByCodecName(codecName)
        } else {
            MediaCodec.createEncoderByType(mime)
        }
        colorFormat = chooseColorFormat(codec, mime)

        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        encoder = codec

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        logger.i(TAG, "Encoder started ${width}x$height @${frameRate}fps colorFormat=$colorFormat")
    }

    /** Encode a single composited frame. The bitmap is scaled if needed. */
    fun encodeFrame(bitmap: Bitmap) {
        val codec = encoder ?: return
        val scaled = if (bitmap.width != width || bitmap.height != height) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else bitmap

        bitmapToYuv(scaled, colorFormat, yuvBuffer)
        if (scaled !== bitmap) scaled.recycle()

        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
        if (inputIndex >= 0) {
            val input: ByteBuffer = codec.getInputBuffer(inputIndex)!!
            input.clear()
            input.put(yuvBuffer)
            val ptsUs = frameIndex * 1_000_000L / frameRate
            codec.queueInputBuffer(inputIndex, 0, yuvBuffer.size, ptsUs, 0)
            frameIndex++
        }
        drain(false)
    }

    /** Signal end-of-stream, drain remaining output, and finalize the MP4. */
    fun finish() {
        val codec = encoder ?: return

        // The EOS buffer must actually be queued: drain(endOfStream = true) only
        // terminates when it sees the EOS flag come back out, so if the input
        // buffer is never queued that loop can never finish. Retry rather than
        // silently skipping it, and fail loudly if the codec stays starved.
        var inputIndex = -1
        val deadline = System.nanoTime() + EOS_QUEUE_TIMEOUT_NS
        while (inputIndex < 0 && System.nanoTime() < deadline) {
            inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
        }
        check(inputIndex >= 0) { "Encoder never yielded an input buffer for end-of-stream" }

        codec.queueInputBuffer(
            inputIndex, 0, 0,
            frameIndex * 1_000_000L / frameRate,
            MediaCodec.BUFFER_FLAG_END_OF_STREAM
        )
        drain(true)
    }

    private fun drain(endOfStream: Boolean) {
        val codec = encoder ?: return
        // Bound the end-of-stream drain: a codec that stops producing output
        // must not spin this loop forever inside the analysis service.
        val deadline = System.nanoTime() + DRAIN_TIMEOUT_NS
        while (true) {
            if (endOfStream && System.nanoTime() > deadline) {
                error("Timed out draining encoder output at end of stream")
            }
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) error("Format changed twice")
                    trackIndex = muxer!!.addTrack(codec.outputFormat)
                    muxer!!.start()
                    muxerStarted = true
                }
                outputIndex >= 0 -> {
                    val encoded = codec.getOutputBuffer(outputIndex)!!
                    val isCodecConfig =
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (bufferInfo.size > 0 && muxerStarted && !isCodecConfig) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer!!.writeSampleData(trackIndex, encoded, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun release() {
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { if (muxerStarted) muxer?.stop() }
        runCatching { muxer?.release() }
        encoder = null
        muxer = null
    }

    private fun chooseColorFormat(codec: MediaCodec, mime: String): Int {
        val caps = runCatching { codec.codecInfo.getCapabilitiesForType(mime) }.getOrNull()
            ?: return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        val supported = caps.colorFormats.toSet()
        return when {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar in supported ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar in supported ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            else -> caps.colorFormats.firstOrNull()
                ?: MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        }
    }

    /**
     * Convert ARGB to YUV420 using BT.601, writing either NV12 (semi-planar)
     * or I420 (planar) depending on [format].
     */
    private fun bitmapToYuv(bitmap: Bitmap, format: Int, out: ByteArray) {
        val w = width
        val h = height
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)

        val frameSize = w * h
        val planar = format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        var yIndex = 0
        // Chroma layouts: planar = [Y][U][V]; semi-planar = [Y][UVUV...]
        var uIndex = frameSize
        var vIndex = frameSize + frameSize / 4
        var uvIndex = frameSize

        for (j in 0 until h) {
            for (i in 0 until w) {
                val color = argb[j * w + i]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                out[yIndex++] = y.coerceIn(0, 255).toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    if (planar) {
                        out[uIndex++] = u.coerceIn(0, 255).toByte()
                        out[vIndex++] = v.coerceIn(0, 255).toByte()
                    } else {
                        out[uvIndex++] = u.coerceIn(0, 255).toByte()
                        out[uvIndex++] = v.coerceIn(0, 255).toByte()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "AnnotatedVideoEncoder"
        private const val TIMEOUT_US = 10_000L

        /** How long to keep retrying for an input buffer to carry end-of-stream. */
        private const val EOS_QUEUE_TIMEOUT_NS = 2_000_000_000L

        /** Upper bound on draining encoder output after end-of-stream. */
        private const val DRAIN_TIMEOUT_NS = 10_000_000_000L
    }
}
