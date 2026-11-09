package com.liftr.vision.video

import android.graphics.Bitmap
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.repository.SettingsKeys
import com.liftr.domain.vision.BodyPositionCalculator
import com.liftr.domain.vision.PlateObservation
import com.liftr.domain.vision.PlateTracker
import com.liftr.domain.vision.VideoPoseSource
import com.liftr.vision.pose.VideoFramePoseDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * [VideoPoseSource] that decodes [videoFile] with [VideoFrameExtractor] and runs
 * a pose detector from [createPoseDetector] on the selected frames. Each pass
 * gets its own detector: its VIDEO running mode needs increasing timestamps.
 * [knownCaptureFps] is the rate the app recorded at, null for imported videos.
 * [beforeFrame] runs before each detection and may suspend to pause the pass;
 * decoding stops too, a couple of frames ahead.
 * With [createPlateTracker], each pass also follows the barbell plate on the
 * analyzed frames, in a [PLATE_FRAME_WIDTH] px wide grayscale copy.
 */
class VideoFilePoseSource(
    videoFile: File,
    private val createPoseDetector: () -> VideoFramePoseDetector,
    private val logger: Logger,
    knownCaptureFps: Int? = null,
    private val beforeFrame: suspend () -> Unit = {},
    private val createPlateTracker: (() -> PlateTracker)? = null,
    private val exerciseType: ExerciseType? = null,
    private val calibrationData: CalibrationData? = null,
    private val plateDiameterM: Float = SettingsKeys.DEFAULT_PLATE_DIAMETER_M,
    /** Normalized (x, y) the user tapped on the plate; seeds the tracker without a pose. */
    private val plateSeed: Pair<Float, Float>? = null
) : VideoPoseSource {

    private val extractor = VideoFrameExtractor(videoFile, logger, knownCaptureFps)

    override val totalFrames: Int get() = extractor.totalFrames

    override val timeScale: Float get() = extractor.timeBase.timeScale

    override fun poses(include: (timestampUs: Long) -> Boolean): Flow<VideoPoseSource.Frame> = flow {
        val plateTracker = createPlateTracker?.invoke()
        val gray = GrayFrame()
        val bodyScale = BodyPositionCalculator(logger)
        val hintAtPlate = exerciseType?.let(PlateTracker::hintAtPlate) ?: false
        createPoseDetector().use { detector ->
            extractor.extractFrames().collect { frame ->
                try {
                    var plate: PlateObservation? = null
                    val pose = if (include(frame.timestampUs)) {
                        beforeFrame()
                        detector.detectPose(frame.bitmap, frame.timestampUs).also { pose ->
                            plate = plateTracker?.let { tracker ->
                                gray.from(frame.bitmap)
                                tracker.track(
                                    gray.luma, gray.width, gray.height, frame.timestampMs,
                                    hint = pose?.let { p ->
                                        exerciseType?.let { PlateTracker.barHint(p, it) } ?: PlateTracker.shoulderHint(p)
                                    },
                                    hintAtPlate = hintAtPlate,
                                    expectedRadius = pose?.let {
                                        PlateTracker.expectedRadius(it, calibrationData, plateDiameterM, bodyScale)
                                    },
                                    seed = plateSeed
                                )
                            }
                        }
                    } else null
                    emit(VideoPoseSource.Frame(frame.timestampMs, pose, plate))
                } finally {
                    frame.bitmap.recycle()
                }
            }
        }
    }

    override fun close() = extractor.close()

    /** Downscaled luma of a frame; buffers are reused while the size stays the same. */
    private class GrayFrame {
        var width = 0
        var height = 0
        var luma = ByteArray(0)
        private var argb = IntArray(0)

        fun from(bitmap: Bitmap) {
            val longSide = maxOf(bitmap.width, bitmap.height)
            val scaled = if (longSide > PLATE_FRAME_LONG_SIDE) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    bitmap.width * PLATE_FRAME_LONG_SIDE / longSide,
                    bitmap.height * PLATE_FRAME_LONG_SIDE / longSide,
                    true
                )
            } else bitmap
            try {
                width = scaled.width
                height = scaled.height
                val n = width * height
                if (argb.size != n) {
                    argb = IntArray(n)
                    luma = ByteArray(n)
                }
                scaled.getPixels(argb, 0, width, 0, 0, width, height)
                for (i in 0 until n) {
                    val c = argb[i]
                    luma[i] = ((77 * (c shr 16 and 0xFF) + 150 * (c shr 8 and 0xFF) + 29 * (c and 0xFF)) shr 8).toByte()
                }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        }
    }

    private companion object {
        /** Long side of the grayscale frames the plate tracker sees (640×360 landscape, 360×640 portrait). */
        const val PLATE_FRAME_LONG_SIDE = 640
    }
}
