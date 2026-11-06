package com.liftr.vision.video

import android.graphics.Bitmap
import com.liftr.domain.logging.Logger
import com.liftr.domain.vision.EndcapDetection
import com.liftr.domain.vision.EndcapDetector
import com.liftr.domain.vision.VideoPoseSource
import com.liftr.vision.pose.VideoFramePoseDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * [VideoPoseSource] that decodes [videoFile] with [VideoFrameExtractor] and runs
 * a pose detector from [createPoseDetector] on the selected frames. Each pass
 * gets its own detector: its VIDEO running mode needs increasing timestamps.
 * [beforeFrame] runs before each detection and may suspend to pause the pass;
 * decoding stops too, a couple of frames ahead.
 *
 * With [createEndcapDetector], each pass also looks for barbell endcaps in
 * every frame that has a pose, on the same decoded bitmap.
 */
class VideoFilePoseSource(
    videoFile: File,
    private val createPoseDetector: () -> VideoFramePoseDetector,
    logger: Logger,
    private val beforeFrame: suspend () -> Unit = {},
    private val createEndcapDetector: (() -> EndcapDetector)? = null
) : VideoPoseSource {

    private val extractor = VideoFrameExtractor(videoFile, logger)

    override val totalFrames: Int get() = extractor.totalFrames

    override fun poses(include: (timestampMs: Long) -> Boolean): Flow<VideoPoseSource.Frame> = flow {
        createPoseDetector().use { detector ->
            val endcapDetector = createEndcapDetector?.invoke()
            try {
                val endcaps = endcapDetector?.takeIf { it.isAvailable() }?.let { EndcapFrames(it) }
                extractor.extractFrames().collect { frame ->
                    try {
                        val pose = if (include(frame.timestampMs)) {
                            beforeFrame()
                            detector.detectPose(frame.bitmap, frame.timestampMs)
                        } else null
                        val detections = if (pose != null && endcaps != null) endcaps.detect(frame.bitmap) else emptyList()
                        emit(VideoPoseSource.Frame(frame.timestampMs, pose, detections))
                    } finally {
                        frame.bitmap.recycle()
                    }
                }
            } finally {
                endcapDetector?.close()
            }
        }
    }

    /** Feeds bitmaps to [detector], reusing one pixel buffer. */
    private class EndcapFrames(private val detector: EndcapDetector) {
        private var pixels = IntArray(0)

        fun detect(bitmap: Bitmap): List<EndcapDetection> {
            val w = bitmap.width
            val h = bitmap.height
            if (pixels.size != w * h) pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            return detector.detect(pixels, w, h)
        }
    }

    override fun close() = extractor.close()
}
