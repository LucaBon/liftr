package com.lifthelper.vision.recording

import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.model.FrameAnnotation
import com.lifthelper.vision.video.VideoFrameExtractor
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces the shareable annotated workout video (Feature 09).
 *
 * Runs a second pass over the raw recording from Feature 17: it re-decodes each
 * frame ([VideoFrameExtractor]), burns the skeleton + HUD onto it
 * ([FrameCompositor]) using the per-frame [FrameAnnotation]s captured during
 * analysis, and re-encodes to a new MP4 ([AnnotatedVideoEncoder]).
 *
 * Frames are matched to annotations by exact presentation timestamp (both
 * passes decode the same file deterministically). Frames with no matching
 * annotation reuse the last known HUD values so rep count / velocity stay
 * continuous, but draw no (stale) skeleton.
 */
@Singleton
class AnnotatedVideoGenerator @Inject constructor(
    private val logger: Logger
) {

    data class Params(
        val sourceVideo: File,
        val outputFile: File,
        val annotations: List<FrameAnnotation>,
        val header: FrameCompositor.Header
    )

    /**
     * Generate the annotated video. Returns the output file on success, or null
     * if decoding/encoding failed (the partial output is cleaned up). Never
     * throws — the caller's analysis result must not be lost over a video error.
     */
    suspend fun generate(params: Params): File? {
        val timeline = AnnotationTimeline(params.annotations)
        val extractor = VideoFrameExtractor(params.sourceVideo, logger)

        var compositor: FrameCompositor? = null
        var encoder: AnnotatedVideoEncoder? = null
        var frameCount = 0

        return try {
            extractor.extractFrames().collect { frame ->
                if (encoder == null) {
                    val w = (extractor.width.coerceAtLeast(2)) and 1.inv()
                    val h = (extractor.height.coerceAtLeast(2)) and 1.inv()
                    val fps = extractor.fps.toInt().coerceIn(MIN_FPS, MAX_FPS)
                    compositor = FrameCompositor(w, h)
                    encoder = AnnotatedVideoEncoder(
                        outputFile = params.outputFile,
                        width = w,
                        height = h,
                        frameRate = fps,
                        bitRate = bitRateFor(w, h),
                        logger = logger
                    ).also { it.start() }
                }

                val annotation = timeline.annotationFor(frame.timestampMs)
                val composited = compositor!!.composite(frame.bitmap, annotation, params.header)
                encoder!!.encodeFrame(composited)
                composited.recycle()
                frame.bitmap.recycle()
                frameCount++
            }

            if (encoder == null) {
                logger.w(TAG, "No frames decoded; annotated video not produced")
                return null
            }
            encoder!!.finish()
            logger.i(TAG, "Annotated video written: ${params.outputFile.name} " +
                "($frameCount frames, ${params.outputFile.length()} bytes)")
            params.outputFile.takeIf { it.exists() && it.length() > 0 }
        } catch (e: Exception) {
            logger.e(TAG, "Annotated video generation failed", e)
            runCatching { params.outputFile.delete() }
            null
        } finally {
            runCatching { encoder?.release() }
            runCatching { extractor.close() }
        }
    }

    private fun bitRateFor(width: Int, height: Int): Int =
        (width * height * 4).coerceIn(2_000_000, 8_000_000)

    companion object {
        private const val TAG = "AnnotatedVideoGenerator"
        private const val MIN_FPS = 10
        private const val MAX_FPS = 60
    }
}
