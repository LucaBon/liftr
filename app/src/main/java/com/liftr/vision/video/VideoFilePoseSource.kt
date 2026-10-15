package com.liftr.vision.video

import com.liftr.domain.logging.Logger
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
 */
class VideoFilePoseSource(
    videoFile: File,
    private val createPoseDetector: () -> VideoFramePoseDetector,
    logger: Logger,
    private val beforeFrame: suspend () -> Unit = {}
) : VideoPoseSource {

    private val extractor = VideoFrameExtractor(videoFile, logger)

    override val totalFrames: Int get() = extractor.totalFrames

    override fun poses(include: (timestampMs: Long) -> Boolean): Flow<VideoPoseSource.Frame> = flow {
        createPoseDetector().use { detector ->
            extractor.extractFrames().collect { frame ->
                try {
                    val pose = if (include(frame.timestampMs)) {
                        beforeFrame()
                        detector.detectPose(frame.bitmap, frame.timestampMs)
                    } else null
                    emit(VideoPoseSource.Frame(frame.timestampMs, pose))
                } finally {
                    frame.bitmap.recycle()
                }
            }
        }
    }

    override fun close() = extractor.close()
}
