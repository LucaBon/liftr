package com.liftr.vision.video

import com.liftr.domain.logging.Logger
import com.liftr.domain.model.PoseResult
import com.liftr.domain.vision.VideoPoseSource
import com.liftr.vision.pose.VideoFramePoseDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * [VideoPoseSource] that decodes [videoFile] with [VideoFrameExtractor] and runs
 * [poseDetector] on each frame. The caller owns [poseDetector]'s lifecycle.
 */
class VideoFilePoseSource(
    videoFile: File,
    private val poseDetector: VideoFramePoseDetector,
    logger: Logger
) : VideoPoseSource {

    private val extractor = VideoFrameExtractor(videoFile, logger)

    override val totalFrames: Int get() = extractor.totalFrames

    override fun poses(): Flow<PoseResult?> = extractor.extractFrames().map { frame ->
        try {
            poseDetector.detectPose(frame.bitmap, frame.timestampMs)
        } finally {
            frame.bitmap.recycle()
        }
    }

    override fun close() = extractor.close()
}
