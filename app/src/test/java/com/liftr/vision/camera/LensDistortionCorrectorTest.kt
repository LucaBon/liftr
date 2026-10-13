package com.liftr.vision.camera

import com.liftr.domain.logging.Logger
import com.liftr.domain.model.PoseKeypoint
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Simple fake logger for testing
 */
class FakeLogger : Logger {
    override fun d(tag: String, message: String) {}
    override fun i(tag: String, message: String) {}
    override fun w(tag: String, message: String) {}
    override fun e(tag: String, message: String, throwable: Throwable?) {}
}

/**
 * Tests for LensDistortionCorrector
 *
 * Verifies that lens distortion correction algorithms work correctly for:
 * 1. Empirical barrel distortion model
 * 2. Center region (minimal correction)
 * 3. Edge regions (significant correction)
 * 4. Boundary conditions
 */
class LensDistortionCorrectorTest {

    private lateinit var corrector: LensDistortionCorrector
    private lateinit var logger: FakeLogger

    @Before
    fun setup() {
        logger = FakeLogger()
        corrector = LensDistortionCorrector(logger)
    }

    // ==================== Empirical Model Tests ====================

    @Test
    fun testEmpiricalCorrection_centerPoint_noChange() {
        // Point at exact center should remain unchanged
        corrector.setCorrectionMode(LensDistortionCorrector.CorrectionMode.EMPIRICAL)

        val centerPoint = PoseKeypoint(x = 0.5f, y = 0.5f, z = 0f, visibility = 1.0f)
        val corrected = corrector.correctLandmark(centerPoint, 1920, 1080)

        assertEquals(0.5f, corrected.x, 0.001f)
        assertEquals(0.5f, corrected.y, 0.001f)
    }

    @Test
    fun testEmpiricalCorrection_nearCenter_minimalChange() {
        // Points near center should have minimal correction
        corrector.setCorrectionMode(LensDistortionCorrector.CorrectionMode.EMPIRICAL)

        val nearCenter = PoseKeypoint(x = 0.55f, y = 0.5f, z = 0f, visibility = 1.0f)
        val corrected = corrector.correctLandmark(nearCenter, 1920, 1080)

        // Should be close to original (within 2%)
        assertEquals(0.55f, corrected.x, 0.02f)
        assertEquals(0.5f, corrected.y, 0.02f)
    }

    @Test
    fun testEmpiricalCorrection_edgePoint_pushedOutward() {
        // Barrel distortion correction pushes edge points outward (away from center)
        corrector.setCorrectionMode(LensDistortionCorrector.CorrectionMode.EMPIRICAL)

        val edgePoint = PoseKeypoint(x = 0.2f, y = 0.5f, z = 0f, visibility = 1.0f)
        val corrected = corrector.correctLandmark(edgePoint, 1920, 1080)

        // Corrected point should be pushed further from center (0.5)
        // Original distance from center: 0.5 - 0.2 = 0.3
        // Corrected should be > 0.2 (pushed left/outward)
        assertTrue("Edge point should be pushed outward", corrected.x < edgePoint.x)

        // Distance from center should increase
        val originalDist = abs(0.5f - edgePoint.x)
        val correctedDist = abs(0.5f - corrected.x)
        assertTrue("Distance from center should increase", correctedDist > originalDist)
    }

    @Test
    fun testEmpiricalCorrection_cornerPoint_maximalCorrection() {
        // Corner points have maximum radial distance, thus maximum correction
        corrector.setCorrectionMode(LensDistortionCorrector.CorrectionMode.EMPIRICAL)

        val corner = PoseKeypoint(x = 0.1f, y = 0.1f, z = 0f, visibility = 1.0f)
        val corrected = corrector.correctLandmark(corner, 1920, 1080)

        // Both X and Y should be pushed outward
        assertTrue("Corner X should be pushed outward", corrected.x < corner.x)
        assertTrue("Corner Y should be pushed outward", corrected.y < corner.y)

        // Radial distance from center should increase
        val originalRadius = sqrt((0.5f - corner.x) * (0.5f - corner.x) +
                                  (0.5f - corner.y) * (0.5f - corner.y))
        val correctedRadius = sqrt((0.5f - corrected.x) * (0.5f - corrected.x) +
                                   (0.5f - corrected.y) * (0.5f - corrected.y))
        assertTrue("Radial distance should increase", correctedRadius > originalRadius)
    }

    @Test
    fun testEmpiricalCorrection_symmetry() {
        // Correction should be symmetric around center
        corrector.setCorrectionMode(LensDistortionCorrector.CorrectionMode.EMPIRICAL)

        val left = PoseKeypoint(x = 0.3f, y = 0.5f, z = 0f, visibility = 1.0f)
        val right = PoseKeypoint(x = 0.7f, y = 0.5f, z = 0f, visibility = 1.0f)

        val correctedLeft = corrector.correctLandmark(left, 1920, 1080)
        val correctedRight = corrector.correctLandmark(right, 1920, 1080)

        // Corrections should be symmetric around 0.5
        val leftOffset = abs(0.5f - correctedLeft.x)
        val rightOffset = abs(0.5f - correctedRight.x)

        assertEquals("Correction should be symmetric", leftOffset, rightOffset, 0.001f)
    }

    @Test
    fun testEmpiricalCorrection_withinBounds() {
        // All corrected points should remain in [0, 1] range
        corrector.setCorrectionMode(LensDistortionCorrector.CorrectionMode.EMPIRICAL)

        val testPoints = listOf(
            PoseKeypoint(x = 0.0f, y = 0.0f, z = 0f, visibility = 1.0f),
            PoseKeypoint(x = 0.0f, y = 1.0f, z = 0f, visibility = 1.0f),
            PoseKeypoint(x = 1.0f, y = 0.0f, z = 0f, visibility = 1.0f),
            PoseKeypoint(x = 1.0f, y = 1.0f, z = 0f, visibility = 1.0f),
            PoseKeypoint(x = 0.5f, y = 0.0f, z = 0f, visibility = 1.0f)
        )

        for (point in testPoints) {
            val corrected = corrector.correctLandmark(point, 1920, 1080)

            assertTrue("X should be >= 0", corrected.x >= 0f)
            assertTrue("X should be <= 1", corrected.x <= 1f)
            assertTrue("Y should be >= 0", corrected.y >= 0f)
            assertTrue("Y should be <= 1", corrected.y <= 1f)
        }
    }

    // ==================== Mode Tests ====================

    @Test
    fun testCorrectionMode_none_noChange() {
        corrector.setCorrectionMode(LensDistortionCorrector.CorrectionMode.NONE)

        val testPoint = PoseKeypoint(x = 0.2f, y = 0.3f, z = 0f, visibility = 1.0f)
        val corrected = corrector.correctLandmark(testPoint, 1920, 1080)

        // Should be identical
        assertEquals(testPoint.x, corrected.x, 0.0001f)
        assertEquals(testPoint.y, corrected.y, 0.0001f)
    }

    @Test
    fun testCorrectionMode_auto_fallsBackToEmpirical() {
        // Without intrinsics, AUTO should use EMPIRICAL model
        corrector.setCorrectionMode(LensDistortionCorrector.CorrectionMode.AUTO)
        assertFalse("Should not have intrinsics initially", corrector.hasIntrinsics())

        val edgePoint = PoseKeypoint(x = 0.2f, y = 0.5f, z = 0f, visibility = 1.0f)
        val corrected = corrector.correctLandmark(edgePoint, 1920, 1080)

        // Should apply empirical correction
        assertNotEquals("Should apply correction", edgePoint.x, corrected.x)
    }

    @Test
    fun testGetCorrectionMode() {
        assertEquals(LensDistortionCorrector.CorrectionMode.AUTO, corrector.getCorrectionMode())

        corrector.setCorrectionMode(LensDistortionCorrector.CorrectionMode.EMPIRICAL)
        assertEquals(LensDistortionCorrector.CorrectionMode.EMPIRICAL, corrector.getCorrectionMode())

        corrector.setCorrectionMode(LensDistortionCorrector.CorrectionMode.NONE)
        assertEquals(LensDistortionCorrector.CorrectionMode.NONE, corrector.getCorrectionMode())
    }

    // ==================== Batch Correction Tests ====================

    @Test
    fun testCorrectLandmarks_batchProcessing() {
        corrector.setCorrectionMode(LensDistortionCorrector.CorrectionMode.EMPIRICAL)

        val landmarks = listOf(
            PoseKeypoint(x = 0.5f, y = 0.5f, z = 0f, visibility = 1.0f),  // Center
            PoseKeypoint(x = 0.2f, y = 0.3f, z = 0f, visibility = 1.0f),  // Edge
            PoseKeypoint(x = 0.8f, y = 0.7f, z = 0f, visibility = 1.0f)   // Edge
        )

        val corrected = corrector.correctLandmarks(landmarks, 1920, 1080)

        assertEquals("Should return same number of landmarks", landmarks.size, corrected.size)

        // Center should be unchanged
        assertEquals(0.5f, corrected[0].x, 0.001f)
        assertEquals(0.5f, corrected[0].y, 0.001f)

        // Edges should be corrected
        assertNotEquals(landmarks[1].x, corrected[1].x)
        assertNotEquals(landmarks[2].x, corrected[2].x)
    }

    // ==================== Visibility Preservation Tests ====================

    @Test
    fun testCorrection_preservesVisibility() {
        corrector.setCorrectionMode(LensDistortionCorrector.CorrectionMode.EMPIRICAL)

        val point = PoseKeypoint(x = 0.3f, y = 0.4f, z = 0.5f, visibility = 0.85f)
        val corrected = corrector.correctLandmark(point, 1920, 1080)

        assertEquals("Visibility should be preserved", point.visibility, corrected.visibility, 0.0001f)
        assertEquals("Z should be preserved", point.z, corrected.z, 0.0001f)
    }

    // ==================== Image Size Tests ====================

    @Test
    fun testCorrection_differentImageSizes() {
        corrector.setCorrectionMode(LensDistortionCorrector.CorrectionMode.EMPIRICAL)

        val point = PoseKeypoint(x = 0.3f, y = 0.4f, z = 0f, visibility = 1.0f)

        // Correction should work the same for different image sizes
        // (since we work in normalized 0-1 space)
        val corrected1080p = corrector.correctLandmark(point, 1920, 1080)
        val corrected720p = corrector.correctLandmark(point, 1280, 720)
        val correctedVGA = corrector.correctLandmark(point, 640, 480)

        // All should be corrected similarly (within small tolerance)
        assertEquals(corrected1080p.x, corrected720p.x, 0.01f)
        assertEquals(corrected1080p.y, corrected720p.y, 0.01f)
        assertEquals(corrected1080p.x, correctedVGA.x, 0.01f)
        assertEquals(corrected1080p.y, correctedVGA.y, 0.01f)
    }

    // ==================== Cache Management Tests ====================

    @Test
    fun testClearCache() {
        // Even without real intrinsics, test cache clearing doesn't crash
        assertFalse(corrector.hasIntrinsics())
        corrector.clearCache()
        assertFalse(corrector.hasIntrinsics())
    }

    // ==================== Accuracy Estimation Tests ====================

    @Test
    fun testCorrection_reducesEdgeError() {
        // Simulate known barrel distortion and verify correction reduces error
        corrector.setCorrectionMode(LensDistortionCorrector.CorrectionMode.EMPIRICAL)

        // True position at edge (if camera had no distortion)
        val truePosition = 0.15f

        // Simulated observed position (barrel distortion pulls points inward)
        // For k1 = -0.20, edge points appear closer to center
        val observedPosition = 0.18f  // Distorted inward

        val observed = PoseKeypoint(x = observedPosition, y = 0.5f, z = 0f, visibility = 1.0f)
        val corrected = corrector.correctLandmark(observed, 1920, 1080)

        // Corrected should be closer to true position than observed
        val observedError = abs(observedPosition - truePosition)
        val correctedError = abs(corrected.x - truePosition)

        assertTrue("Correction should reduce error (observed error: $observedError, " +
                "corrected error: $correctedError)",
            correctedError < observedError
        )
    }

    // ==================== Magnitude Tests ====================

    @Test
    fun testCorrection_typicalMagnitude() {
        // For typical barrel distortion (k1 ≈ -0.20), corrections should be reasonable
        corrector.setCorrectionMode(LensDistortionCorrector.CorrectionMode.EMPIRICAL)

        val edgePoint = PoseKeypoint(x = 0.15f, y = 0.5f, z = 0f, visibility = 1.0f)
        val corrected = corrector.correctLandmark(edgePoint, 1920, 1080)

        val correction = abs(corrected.x - edgePoint.x)

        // Typical correction at 0.15 distance from center should be 0.5-10%
        // At r=0.35 (distance from center), k1=-0.20: correction ≈ 0.009 or 0.9%
        assertTrue("Correction should be reasonable (was: $correction)",
            correction > 0.005f && correction < 0.10f
        )
    }
}
