package com.liftr.vision.video

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.liftr.domain.error.LiftrError
import com.liftr.domain.logging.Logger
import com.liftr.domain.video.VideoTimeBase
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt

/**
 * Decodes an MP4 file frame-by-frame, emitting each frame as an upright ARGB
 * [Bitmap] through a cold [Flow].
 *
 * Decoding goes through [GlFrameDecoder] (MediaCodec to a SurfaceTexture, read
 * back with OpenGL), which yields every frame in order with its exact
 * presentation time. [MediaMetadataRetriever] is only used for metadata: on
 * some devices its frame grabbing returns only sync frames (seek by time) or
 * the same frame for every index, which repeats one image for up to a second.
 *
 * Frame times are real time: slow-motion files, whose timestamps are
 * stretched to play back slowly, are mapped back through [VideoTimeBase].
 * Slow-motion files that change speed are rejected with a
 * [LiftrError.ValidationError]; a real-time file with a varying frame rate is fine.
 *
 * Each emitted [VideoFrame.bitmap] MUST be recycled by the collector after use.
 */
class VideoFrameExtractor(
    private val videoFile: File,
    private val logger: Logger,
    private val knownCaptureFps: Int? = null
) : Closeable {

    /** Video metadata (populated once extraction starts; size is of the upright frames). */
    var width: Int = 0; private set
    var height: Int = 0; private set
    var fps: Float = 0f; private set
    var durationMs: Long = 0L; private set
    var totalFrames: Int = 0; private set
    var timeBase = VideoTimeBase.Result(1f, 0f, false); private set

    /** [timestampUs] is real time since the first frame; [timestampMs] is it in whole milliseconds. */
    data class VideoFrame(
        val bitmap: Bitmap,
        val timestampUs: Long,
        val frameIndex: Int
    ) {
        val timestampMs: Long get() = timestampUs / 1_000
    }

    /** Extract every frame of the video. Bitmaps must be recycled by the collector. */
    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    fun extractFrames(): Flow<VideoFrame> = channelFlow {
        readMetadata()
        // The decoder's EGL context is bound to one thread, so decoding runs
        // on a dedicated thread; buffer(2) bounds how many decoded bitmaps
        // wait for the collector.
        val decodeThread = newSingleThreadContext("VideoFrameExtractor")
        try {
            withContext(decodeThread) {
                GlFrameDecoder(videoFile).use { decoder ->
                    var frameIndex = 0
                    var startUs = -1L
                    while (true) {
                        val frame = decoder.nextFrame() ?: break
                        if (startUs < 0) {
                            startUs = frame.presentationTimeUs
                            width = frame.bitmap.width
                            height = frame.bitmap.height
                        }
                        send(VideoFrame(frame.bitmap, timeBase.realTimeUs(frame.presentationTimeUs - startUs), frameIndex))
                        frameIndex++
                    }
                    if (frameIndex == 0) {
                        throw IllegalStateException("No frames decoded from ${videoFile.name}")
                    }
                    totalFrames = frameIndex
                    logger.i(TAG, "Extraction complete: $frameIndex frames")
                }
            }
        } finally {
            decodeThread.close()
        }
    }.buffer(2)

    private fun readMetadata() {
        if (com.liftr.BuildConfig.DEBUG) VideoMetadataProbe.probe(videoFile)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            // CameraX writes no capture frame rate; the container's frame
            // count gives the real one (60 fps recordings were read as 30).
            val frameCount = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
            } else null
            val (trackFormat, ptsDeltasUs) = readVideoTrack()
            val metadataCaptureFps = captureFps(retriever, trackFormat)
            fps = when {
                frameCount != null && frameCount > 0 && durationMs > 0 -> frameCount * 1_000f / durationMs
                else -> metadataCaptureFps ?: DEFAULT_FPS
            }
            timeBase = VideoTimeBase.resolve(fps, knownCaptureFps, metadataCaptureFps, ptsDeltasUs)
        } finally {
            runCatching { retriever.release() }
        }

        if (durationMs <= 0L) {
            throw IllegalStateException("Video has zero duration: ${videoFile.name}")
        }
        logger.i(TAG, "Time base: capture ${timeBase.captureFps}fps, scale ${timeBase.timeScale}, variable=${timeBase.isVariableSpeed}, slowMo=${timeBase.isSlowMotion}")
        if (timeBase.rejectsSpeedChange) {
            throw LiftrError.ValidationError(
                field = "video",
                message = "Slow-motion videos that change speed can't be analyzed yet. " +
                    "Record at one constant frame rate."
            )
        }
        // Exact when the container has a frame count; replaced once decoding ends.
        totalFrames = (durationMs * fps / 1_000f).roundToInt().coerceAtLeast(1)
        logger.i(TAG, "Video: ${fps}fps, ${durationMs}ms, ~$totalFrames frames")
    }

    /**
     * The capture frame rate the file declares, if any. Which key a device
     * writes is not confirmed yet: the retriever's capture frame rate, else
     * the video track's `capture-rate`.
     */
    private fun captureFps(retriever: MediaMetadataRetriever, trackFormat: MediaFormat?): Float? {
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            ?.toFloatOrNull()?.takeIf { it > 0f }?.let { return it }
        val format = trackFormat?.takeIf { it.containsKey(MediaFormat.KEY_CAPTURE_RATE) } ?: return null
        val rate = runCatching { format.getFloat(MediaFormat.KEY_CAPTURE_RATE) }.getOrNull()
            ?: runCatching { format.getInteger(MediaFormat.KEY_CAPTURE_RATE).toFloat() }.getOrNull()
        return rate?.takeIf { it > 0f }
    }

    /** The video track's format and the gaps between its presentation times, without decoding. */
    private fun readVideoTrack(): Pair<MediaFormat?, List<Long>> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(videoFile.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return null to emptyList()
            extractor.selectTrack(track)
            val ptsUs = ArrayList<Long>()
            do {
                extractor.sampleTime.takeIf { it >= 0 }?.let { ptsUs.add(it) }
            } while (extractor.advance())
            // Samples come in decode order; B-frames put presentation times out of order.
            ptsUs.sort()
            return extractor.getTrackFormat(track) to ptsUs.zipWithNext { a, b -> b - a }
        } catch (e: Exception) {
            logger.w(TAG, "Could not read the video track's timestamps: ${e.message}")
            return null to emptyList()
        } finally {
            runCatching { extractor.release() }
        }
    }

    override fun close() {
        // Decoder resources are scoped to each extractFrames() call.
    }

    companion object {
        private const val TAG = "VideoFrameExtractor"
        private const val DEFAULT_FPS = 30f
    }
}
