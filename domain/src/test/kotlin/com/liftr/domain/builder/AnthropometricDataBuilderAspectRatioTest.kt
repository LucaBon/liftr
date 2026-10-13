package com.liftr.domain.builder

import com.liftr.domain.calculator.CameraAlignmentChecker
import com.liftr.domain.calculator.CoordinateCalculator
import com.liftr.domain.extractor.BodySegmentExtractor
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.PoseResultTestFixtures
import com.liftr.domain.validation.MeasurementValidator
import com.liftr.domain.validation.PoseValidator
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for widths measured on non-square frames.
 *
 * MediaPipe normalizes x by the image width and y by the image height. Taking
 * the raw normalized shoulder dx over the normalized height inflated the
 * shoulder width by H/W: on a 960x1280 calibration frame a 31% shoulder width
 * read as 41%.
 */
class AnthropometricDataBuilderAspectRatioTest {

    private val coordinateCalculator = CoordinateCalculator()
    private val builder = AnthropometricDataBuilder(
        poseValidator = PoseValidator(),
        coordinateCalculator = coordinateCalculator,
        segmentExtractor = BodySegmentExtractor(coordinateCalculator),
        measurementValidator = MeasurementValidator(),
        alignmentChecker = CameraAlignmentChecker(),
        logger = object : Logger {
            override fun d(tag: String, message: String) {}
            override fun i(tag: String, message: String) {}
            override fun w(tag: String, message: String) {}
            override fun e(tag: String, message: String, throwable: Throwable?) {}
        }
    )

    private fun shoulderRatio(width: Int, height: Int): Float {
        val pose = PoseResultTestFixtures.createIdealFrontalPose(
            imageWidth = width,
            imageHeight = height,
            shoulderWidthRatio = 0.31f,
            hipWidthRatio = 0.26f
        )
        return builder.build(pose, userHeight = 1.90f).getOrThrow().shoulderWidthRatio
    }

    @Test
    fun `portrait 4-3 calibration frame measures the true shoulder width`() {
        val portrait = shoulderRatio(960, 1280)
        val square = shoulderRatio(1280, 1280)

        // A square frame has equal x and y units, so it is the reference.
        assertEquals(square, portrait, 0.005f)
        // The uncorrected value would be square * 1280 / 960 (about 0.41).
        assertEquals(0.31f, portrait, 0.03f)
    }

    @Test
    fun `shoulder width is the same in portrait and landscape`() {
        assertEquals(shoulderRatio(1080, 1920), shoulderRatio(1920, 1080), 0.005f)
    }
}
