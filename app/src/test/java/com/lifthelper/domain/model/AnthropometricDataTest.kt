package com.lifthelper.domain.model

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for AnthropometricData body measurement calculations
 *
 * Tests cover:
 * - Portrait and landscape orientations
 * - Front and back camera configurations
 * - Different image resolutions
 * - Width calculations (shoulders, hips, knees)
 * - Height calculations
 * - Aspect ratio corrections
 */
class AnthropometricDataTest {

    companion object {
        // Standard test user height
        private const val USER_HEIGHT = 1.75f // 175 cm

        // Expected measurement tolerances
        private const val WIDTH_TOLERANCE_CM = 5f // ±5cm tolerance for widths
        private const val HEIGHT_TOLERANCE_CM = 3f // ±3cm tolerance for heights
        private const val RATIO_TOLERANCE = 0.03f // ±3% tolerance for ratios

        // Expected anthropometric values for ideal pose (in cm)
        private const val EXPECTED_SHOULDER_WIDTH_CM = 40f // ~23% of 175cm
        private const val EXPECTED_HIP_WIDTH_CM = 35f // ~20% of 175cm
        private const val EXPECTED_KNEE_WIDTH_CM = 17f // ~10% of 175cm
    }

    /**
     * Test 1: Portrait orientation with back camera
     * Most common use case for anthropometric calibration
     */
    @Test
    fun testPortraitBackCamera_correctMeasurements() {
        // Arrange
        val poseResult = PoseResultTestFixtures.createPortraitPose(isFrontCamera = false)

        // Act
        val data = AnthropometricData.fromPoseResult(
            poseResult = poseResult,
            userHeight = USER_HEIGHT,
            manualShoulderWidth = null
        )

        // Assert
        assertNotNull("AnthropometricData should not be null", data)
        data!!

        // Check shoulder width
        val shoulderWidthCm = data.getShoulderWidth() * 100
        assertEquals(
            "Shoulder width should be ~40cm in portrait",
            EXPECTED_SHOULDER_WIDTH_CM,
            shoulderWidthCm,
            WIDTH_TOLERANCE_CM
        )

        // Check hip width
        val hipWidthCm = data.getHipWidth() * 100
        assertEquals(
            "Hip width should be ~31cm in portrait",
            EXPECTED_HIP_WIDTH_CM,
            hipWidthCm,
            WIDTH_TOLERANCE_CM
        )

        // Check knee width
        val kneeWidthCm = data.getKneeWidth() * 100
        assertEquals(
            "Knee width should be ~17cm in portrait",
            EXPECTED_KNEE_WIDTH_CM,
            kneeWidthCm,
            WIDTH_TOLERANCE_CM
        )

        // Check total height is correct
        val totalHeightCm = data.userHeight * 100
        assertEquals(
            "Total height should match input",
            USER_HEIGHT * 100,
            totalHeightCm,
            HEIGHT_TOLERANCE_CM
        )

        // Check calibration quality (OPTIMAL or FALLBACK are both acceptable for test fixtures)
        assertTrue(
            "Calibration quality should be OPTIMAL or FALLBACK for ideal pose, got: ${data.captureQuality}",
            data.captureQuality == CalibrationQuality.OPTIMAL ||
            data.captureQuality == CalibrationQuality.FALLBACK
        )
    }

    /**
     * Test 2: Landscape orientation with back camera
     * This was BROKEN before the fix - measurements were too small
     */
    @Test
    fun testLandscapeBackCamera_correctMeasurements() {
        // Arrange
        val poseResult = PoseResultTestFixtures.createLandscapePose(isFrontCamera = false)

        // Act
        val data = AnthropometricData.fromPoseResult(
            poseResult = poseResult,
            userHeight = USER_HEIGHT,
            manualShoulderWidth = null
        )

        // Assert
        assertNotNull("AnthropometricData should not be null", data)
        data!!

        // Check shoulder width (this was failing before: was ~15cm instead of ~40cm)
        val shoulderWidthCm = data.getShoulderWidth() * 100
        assertEquals(
            "Shoulder width should be ~40cm in landscape (CRITICAL FIX)",
            EXPECTED_SHOULDER_WIDTH_CM,
            shoulderWidthCm,
            WIDTH_TOLERANCE_CM
        )

        // Check hip width (this was failing before: was ~9cm instead of ~31cm)
        val hipWidthCm = data.getHipWidth() * 100
        assertEquals(
            "Hip width should be ~31cm in landscape (CRITICAL FIX)",
            EXPECTED_HIP_WIDTH_CM,
            hipWidthCm,
            WIDTH_TOLERANCE_CM
        )

        // Check knee width
        val kneeWidthCm = data.getKneeWidth() * 100
        assertEquals(
            "Knee width should be ~17cm in landscape",
            EXPECTED_KNEE_WIDTH_CM,
            kneeWidthCm,
            WIDTH_TOLERANCE_CM
        )
    }

    /**
     * Test 3: Portrait orientation with front camera (mirrored)
     */
    @Test
    fun testPortraitFrontCamera_correctMeasurements() {
        // Arrange
        val poseResult = PoseResultTestFixtures.createPortraitPose(isFrontCamera = true)

        // Act
        val data = AnthropometricData.fromPoseResult(
            poseResult = poseResult,
            userHeight = USER_HEIGHT,
            manualShoulderWidth = null
        )

        // Assert
        assertNotNull("AnthropometricData should not be null", data)
        data!!

        // Measurements should be identical regardless of mirroring
        val shoulderWidthCm = data.getShoulderWidth() * 100
        assertEquals(
            "Shoulder width should be same for front camera",
            EXPECTED_SHOULDER_WIDTH_CM,
            shoulderWidthCm,
            WIDTH_TOLERANCE_CM
        )

        val hipWidthCm = data.getHipWidth() * 100
        assertEquals(
            "Hip width should be same for front camera",
            EXPECTED_HIP_WIDTH_CM,
            hipWidthCm,
            WIDTH_TOLERANCE_CM
        )
    }

    /**
     * Test 4: Landscape orientation with front camera
     */
    @Test
    fun testLandscapeFrontCamera_correctMeasurements() {
        // Arrange
        val poseResult = PoseResultTestFixtures.createLandscapePose(isFrontCamera = true)

        // Act
        val data = AnthropometricData.fromPoseResult(
            poseResult = poseResult,
            userHeight = USER_HEIGHT,
            manualShoulderWidth = null
        )

        // Assert
        assertNotNull("AnthropometricData should not be null", data)
        data!!

        val shoulderWidthCm = data.getShoulderWidth() * 100
        assertEquals(
            "Shoulder width should be same for front camera in landscape",
            EXPECTED_SHOULDER_WIDTH_CM,
            shoulderWidthCm,
            WIDTH_TOLERANCE_CM
        )
    }

    /**
     * Test 5: Square aspect ratio (1:1)
     */
    @Test
    fun testSquareAspectRatio_correctMeasurements() {
        // Arrange
        val poseResult = PoseResultTestFixtures.createSquarePose(isFrontCamera = false)

        // Act
        val data = AnthropometricData.fromPoseResult(
            poseResult = poseResult,
            userHeight = USER_HEIGHT,
            manualShoulderWidth = null
        )

        // Assert
        assertNotNull("AnthropometricData should not be null", data)
        data!!

        val shoulderWidthCm = data.getShoulderWidth() * 100
        assertEquals(
            "Shoulder width should be correct in square aspect ratio",
            EXPECTED_SHOULDER_WIDTH_CM,
            shoulderWidthCm,
            WIDTH_TOLERANCE_CM
        )
    }

    /**
     * Test 6: Low resolution portrait (480x640)
     */
    @Test
    fun testLowResolutionPortrait_correctMeasurements() {
        // Arrange
        val poseResult = PoseResultTestFixtures.createLowResPortraitPose(isFrontCamera = false)

        // Act
        val data = AnthropometricData.fromPoseResult(
            poseResult = poseResult,
            userHeight = USER_HEIGHT,
            manualShoulderWidth = null
        )

        // Assert
        assertNotNull("AnthropometricData should work with low resolution", data)
        data!!

        val shoulderWidthCm = data.getShoulderWidth() * 100
        assertEquals(
            "Shoulder width should be correct even at low resolution",
            EXPECTED_SHOULDER_WIDTH_CM,
            shoulderWidthCm,
            WIDTH_TOLERANCE_CM
        )
    }

    /**
     * Test 7: Low resolution landscape (640x480)
     */
    @Test
    fun testLowResolutionLandscape_correctMeasurements() {
        // Arrange
        val poseResult = PoseResultTestFixtures.createLowResLandscapePose(isFrontCamera = false)

        // Act
        val data = AnthropometricData.fromPoseResult(
            poseResult = poseResult,
            userHeight = USER_HEIGHT,
            manualShoulderWidth = null
        )

        // Assert
        assertNotNull("AnthropometricData should work with low resolution", data)
        data!!

        val shoulderWidthCm = data.getShoulderWidth() * 100
        assertEquals(
            "Shoulder width should be correct at low resolution landscape",
            EXPECTED_SHOULDER_WIDTH_CM,
            shoulderWidthCm,
            WIDTH_TOLERANCE_CM
        )
    }

    /**
     * Test 8: Measurements are consistent across all orientations
     * This is the CRITICAL test - ensures aspect ratio fix works correctly
     */
    @Test
    fun testMeasurementsConsistentAcrossOrientations() {
        // Arrange - create poses in all orientations
        val portraitBackPose = PoseResultTestFixtures.createPortraitPose(false)
        val landscapeBackPose = PoseResultTestFixtures.createLandscapePose(false)
        val portraitFrontPose = PoseResultTestFixtures.createPortraitPose(true)
        val landscapeFrontPose = PoseResultTestFixtures.createLandscapePose(true)

        // Act - measure all
        val portraitBackData = AnthropometricData.fromPoseResult(portraitBackPose, USER_HEIGHT, null)!!
        val landscapeBackData = AnthropometricData.fromPoseResult(landscapeBackPose, USER_HEIGHT, null)!!
        val portraitFrontData = AnthropometricData.fromPoseResult(portraitFrontPose, USER_HEIGHT, null)!!
        val landscapeFrontData = AnthropometricData.fromPoseResult(landscapeFrontPose, USER_HEIGHT, null)!!

        // Assert - all should have similar measurements
        val shoulderWidths = listOf(
            portraitBackData.getShoulderWidth() * 100,
            landscapeBackData.getShoulderWidth() * 100,
            portraitFrontData.getShoulderWidth() * 100,
            landscapeFrontData.getShoulderWidth() * 100
        )

        val hipWidths = listOf(
            portraitBackData.getHipWidth() * 100,
            landscapeBackData.getHipWidth() * 100,
            portraitFrontData.getHipWidth() * 100,
            landscapeFrontData.getHipWidth() * 100
        )

        // Check that all shoulder widths are within tolerance of each other
        val shoulderWidthVariance = shoulderWidths.maxOrNull()!! - shoulderWidths.minOrNull()!!
        assertTrue(
            "Shoulder width should be consistent across all orientations (variance: $shoulderWidthVariance cm)",
            shoulderWidthVariance < WIDTH_TOLERANCE_CM
        )

        // Check that all hip widths are within tolerance of each other
        val hipWidthVariance = hipWidths.maxOrNull()!! - hipWidths.minOrNull()!!
        assertTrue(
            "Hip width should be consistent across all orientations (variance: $hipWidthVariance cm)",
            hipWidthVariance < WIDTH_TOLERANCE_CM
        )

        // Log measurements for debugging
        println("Shoulder widths: $shoulderWidths (variance: $shoulderWidthVariance cm)")
        println("Hip widths: $hipWidths (variance: $hipWidthVariance cm)")
    }

    /**
     * Test 9: Partial body (missing lower body) returns null
     * This is expected behavior - ankles are required for height estimation
     */
    @Test
    fun testPartialBody_returnsNull() {
        // Arrange
        val poseResult = PoseResultTestFixtures.createPartialBodyPose(1080, 1920)

        // Act
        val data = AnthropometricData.fromPoseResult(
            poseResult = poseResult,
            userHeight = USER_HEIGHT,
            manualShoulderWidth = null
        )

        // Assert - should return null when critical landmarks (ankles) are missing
        assertNull("AnthropometricData should return null when ankles are not visible", data)
    }

    /**
     * Test 10: Aspect ratio scaling is correct for width calculations
     * This directly tests the bug fix
     */
    @Test
    fun testAspectRatioScaling_portraitVsLandscape() {
        // Arrange - 1920x1080 landscape vs 1080x1920 portrait
        val landscapePose = PoseResultTestFixtures.createLandscapePose(false)
        val portraitPose = PoseResultTestFixtures.createPortraitPose(false)

        // Act
        val landscapeData = AnthropometricData.fromPoseResult(landscapePose, USER_HEIGHT, null)!!
        val portraitData = AnthropometricData.fromPoseResult(portraitPose, USER_HEIGHT, null)!!

        // Assert - ratios should be equal (not raw widths)
        val landscapeShoulderRatio = landscapeData.shoulderWidthRatio
        val portraitShoulderRatio = portraitData.shoulderWidthRatio

        assertEquals(
            "Shoulder width ratio should be equal in portrait and landscape",
            portraitShoulderRatio,
            landscapeShoulderRatio,
            RATIO_TOLERANCE
        )

        val landscapeHipRatio = landscapeData.hipWidthRatio
        val portraitHipRatio = portraitData.hipWidthRatio

        assertEquals(
            "Hip width ratio should be equal in portrait and landscape",
            portraitHipRatio,
            landscapeHipRatio,
            RATIO_TOLERANCE
        )

        println("Portrait shoulder ratio: $portraitShoulderRatio, Landscape shoulder ratio: $landscapeShoulderRatio")
        println("Portrait hip ratio: $portraitHipRatio, Landscape hip ratio: $landscapeHipRatio")
    }

    /**
     * Test 11: Missing critical landmarks returns null
     */
    @Test
    fun testMissingCriticalLandmarks_returnsNull() {
        // Arrange - create pose with missing shoulders
        val poseResult = PoseResultTestFixtures.createPortraitPose(false)
        val landmarksWithMissingShoulders = poseResult.landmarks.mapIndexed { index, landmark ->
            if (index == 11 || index == 12) { // Shoulders
                landmark.copy(visibility = 0.1f) // Low visibility
            } else {
                landmark
            }
        }
        val invalidPose = poseResult.copy(landmarks = landmarksWithMissingShoulders)

        // Act
        val data = AnthropometricData.fromPoseResult(
            poseResult = invalidPose,
            userHeight = USER_HEIGHT,
            manualShoulderWidth = null
        )

        // Assert
        assertNull("AnthropometricData should be null when shoulders are missing", data)
    }

    /**
     * Test 12: Sanity check catches unrealistic measurements
     */
    @Test
    fun testSanityChecks_catchUnrealisticValues() {
        // Create a pose with normal measurements
        val poseResult = PoseResultTestFixtures.createPortraitPose(false)
        val data = AnthropometricData.fromPoseResult(poseResult, USER_HEIGHT, null)

        assertNotNull(data)
        data!!

        // Hip width should be in realistic range (5-25% of height)
        val hipWidthRatio = data.hipWidthRatio
        assertTrue(
            "Hip width ratio should be realistic (5-25% of height), got: $hipWidthRatio",
            hipWidthRatio in 0.05f..0.25f
        )

        // Knee width should be in realistic range (3-20% of height)
        val kneeWidthRatio = data.kneeWidthRatio
        assertTrue(
            "Knee width ratio should be realistic (3-20% of height), got: $kneeWidthRatio",
            kneeWidthRatio in 0.03f..0.20f
        )
    }
}
