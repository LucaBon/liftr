package com.liftr.vision.video

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.liftr.domain.logging.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.Closeable
import java.io.File

/**
 * Decodes an MP4 file frame-by-frame, emitting each frame as an ARGB [Bitmap]
 * through a cold [Flow].
 *
 * Uses [MediaMetadataRetriever] rather than MediaCodec + ImageReader. The
 * ImageReader/YUV path crashes on some devices (notably recent Motorolas) with
 * a JNI abort when reading decoded plane buffers. Retriever-based sampling is
 * slightly less frame-perfect but stable and sufficient for post-exercise pose
 * analysis.
 *
 * Each emitted [VideoFrame.bitmap] MUST be recycled by the collector after use.
 */
class VideoFrameExtractor(
    private val videoFile: File,
    private val logger: Logger
) : Closeable {

    /** Video metadata (populated once extraction starts). */
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

    /**
     * Extract frames at the video's nominal frame rate (or [DEFAULT_FPS] when
     * metadata is missing). Bitmaps must be recycled by the collector.
     */
    fun extractFrames(): Flow<VideoFrame> = flow {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.absolutePath)

            durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L

            width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0

            height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0

            fps = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE
            )?.toFloatOrNull()?.takeIf { it > 0f } ?: DEFAULT_FPS

            val intervalUs = (1_000_000L / fps).toLong().coerceAtLeast(1L)
            val endUs = durationMs * 1_000L
            totalFrames = if (endUs > 0L) {
                (endUs / intervalUs).toInt().coerceAtLeast(1)
            } else {
                1
            }

            logger.i(
                TAG,
                "Video: ${width}x$height, ${fps}fps, ${durationMs}ms, ~$totalFrames frames (retriever)"
            )

            if (endUs <= 0L) {
                throw IllegalStateException("Video has zero duration: ${videoFile.name}")
            }

            var frameIndex = 0
            var timeUs = 0L
            while (timeUs <= endUs) {
                val bitmap = retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
                if (bitmap != null) {
                    if (width == 0) width = bitmap.width
                    if (height == 0) height = bitmap.height
                    emit(VideoFrame(bitmap, timeUs / 1_000, frameIndex))
                    frameIndex++
                }
                timeUs += intervalUs
            }

            if (frameIndex == 0) {
                throw IllegalStateException("No frames decoded from ${videoFile.name}")
            }

            totalFrames = frameIndex
            logger.i(TAG, "Extraction complete: $frameIndex frames")
        } finally {
            runCatching { retriever.release() }
        }
    }

    override fun close() {
        // Retriever is scoped to each extractFrames() call; nothing persistent to release.
    }

    companion object {
        private const val TAG = "VideoFrameExtractor"
        private const val DEFAULT_FPS = 30f
    }
}
