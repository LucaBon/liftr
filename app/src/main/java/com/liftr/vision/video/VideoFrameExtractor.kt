package com.liftr.vision.video

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.liftr.domain.logging.Logger
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

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
 * Each emitted [VideoFrame.bitmap] MUST be recycled by the collector after use.
 */
class VideoFrameExtractor(
    private val videoFile: File,
    private val logger: Logger
) : Closeable {

    /** Video metadata (populated once extraction starts; size is of the upright frames). */
    var width: Int = 0; private set
    var height: Int = 0; private set
    var fps: Float = 0f; private set
    var durationMs: Long = 0L; private set
    var totalFrames: Int = 0; private set

    data class VideoFrame(
        val bitmap: Bitmap,
        val timestampMs: Long,
        val frameIndex: Int
    )

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
                        send(VideoFrame(frame.bitmap, (frame.presentationTimeUs - startUs) / 1_000, frameIndex))
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
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            fps = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE
            )?.toFloatOrNull()?.takeIf { it > 0f } ?: DEFAULT_FPS
        } finally {
            runCatching { retriever.release() }
        }

        if (durationMs <= 0L) {
            throw IllegalStateException("Video has zero duration: ${videoFile.name}")
        }
        // Estimate for progress reporting until the real count is known.
        totalFrames = (durationMs * fps / 1_000f).toInt().coerceAtLeast(1)
        logger.i(TAG, "Video: ${fps}fps, ${durationMs}ms, ~$totalFrames frames")
    }

    override fun close() {
        // Decoder resources are scoped to each extractFrames() call.
    }

    companion object {
        private const val TAG = "VideoFrameExtractor"
        private const val DEFAULT_FPS = 30f
    }
}
