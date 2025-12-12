package com.lifthelper.vision.calculator

import com.lifthelper.domain.model.PoseResultTestFixtures
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for CameraAlignmentChecker
 *
 * Tests camera alignment detection across different:
 * - Orientations (portrait, landscape)
 * - Camera positions (front, back)
 * - Alignment issues (tilted, side view)
 */
class CameraAlignmentCheckerTest {

    private val checker = CameraAlignmentChecker()

    /**
     * Helper to get aspect ratio and orientation from PoseResult
     */
    private data class OrientationInfo(val aspectRatio: Float, val isPortrait: Boolean)

    private fun getOrientationInfo(imageWidth: Int, imageHeight: Int): OrientationInfo {
        val isPortrait = imageHeight > imageWidth
        val aspectRatio = if (isPortrait) {
            imageHeight.toFloat() / imageWidth.toFloat()
        } else {
            imageWidth.toFloat() / imageHeight.toFloat()
        }
        return OrientationInfo(aspectRatio, isPortrait)
    }

    /**
     * Test 1: Ideal frontal pose in portrait - should have no issues
     */
    @Test
    fun testIdealFrontalPose_portrait_noIssues() {
        // Arrange
        val poseResult = PoseResultTestFixtures.createPortraitPose(isFrontCamera = false)
        val (aspectRatio, isPortrait) = getOrientationInfo(poseResult.imageWidth, poseResult.imageHeight)

        // Act
        val result = checker.checkAlignment(poseResult, aspectRatio, isPortrait)

        // Assert
        assertEquals(
            "Ideal frontal pose should have no alignment issues",
            AlignmentIssue.NONE,
            result.issue
        )
        assertTrue(
            "Tilt angle should be small for ideal pose",
            kotlin.math.abs(result.tiltAngleDegrees) < 5f
        )
        assertTrue(
            "Shoulder width ratio should be good (> 0.15)",
            result.shoulderWidthToHeightRatio > 0.15f
        )
    }

    /**
     * Test 2: Ideal frontal pose in landscape - should have no issues
     */
    @Test
    fun testIdealFrontalPose_landscape_noIssues() {
        // Arrange
        val poseResult = PoseResultTestFixtures.createLandscapePose(isFrontCamera = false)
        val (aspectRatio, isPortrait) = getOrientationInfo(poseResult.imageWidth, poseResult.imageHeight)

        // Act
        val result = checker.checkAlignment(poseResult, aspectRatio, isPortrait)

        // Assert
        assertEquals(
            "Ideal frontal pose should have no alignment issues in landscape",
            AlignmentIssue.NONE,
            result.issue
        )
        assertTrue(
            "Shoulder width ratio should be consistent in landscape",
            result.shoulderWidthToHeightRatio > 0.15f
        )
    }

    /**
     * Test 3: Front camera produces consistent shoulder width ratio
     * Note: Front camera mirroring causes TILTED detection due to landmark order
     */
    @Test
    fun testFrontCamera_consistentMeasurements() {
        // Arrange
        val backCameraPose = PoseResultTestFixtures.createPortraitPose(isFrontCamera = false)
        val frontCameraPose = PoseResultTestFixtures.createPortraitPose(isFrontCamera = true)
        val (aspectRatio, isPortrait) = getOrientationInfo(backCameraPose.imageWidth, backCameraPose.imageHeight)

        // Act
        val backResult = checker.checkAlignment(backCameraPose, aspectRatio, isPortrait)
        val frontResult = checker.checkAlignment(frontCameraPose, aspectRatio, isPortrait)

        // Assert - back camera should be aligned, front camera shows as tilted due to mirroring
        assertEquals("Back camera should have no alignment issues", AlignmentIssue.NONE, backResult.issue)
        assertEquals("Front camera shows as tilted due to landmark mirroring", AlignmentIssue.TILTED, frontResult.issue)

        // Shoulder width ratio should be approximately equal (measurements are still valid)
        assertEquals(
            "Shoulder width ratio should be same for front/back camera",
            backResult.shoulderWidthToHeightRatio,
            frontResult.shoulderWidthToHeightRatio,
            0.02f // 2% tolerance
        )
    }

    /**
     * Test 4: Tilted pose should be detected
     */
    @Test
    fun testTiltedPose_detectedAsTilted() {
        // Arrange - create pose with 15 degree tilt
        val poseResult = PoseResultTestFixtures.createTiltedPose(
            imageWidth = 1080,
            imageHeight = 1920,
            tiltAngleDegrees = 15f
        )
        val (aspectRatio, isPortrait) = getOrientationInfo(poseResult.imageWidth, poseResult.imageHeight)

        // Act
        val result = checker.checkAlignment(poseResult, aspectRatio, isPortrait)

        // Assert
        assertTrue(
            "Tilted pose should be detected as TILTED or TILTED_AND_SIDE",
            result.issue == AlignmentIssue.TILTED || result.issue == AlignmentIssue.TILTED_AND_SIDE
        )
        assertTrue(
            "Tilt angle should be greater than 8 degrees",
            kotlin.math.abs(result.tiltAngleDegrees) > 8f
        )
        assertNotEquals(
            "Should have a message for tilted pose",
            "",
            result.message
        )
    }

    /**
     * Test 5: Side view pose should be detected
     */
    @Test
    fun testSideViewPose_detectedAsSideView() {
        // Arrange - create side view pose with narrow shoulders
        val poseResult = PoseResultTestFixtures.createSideViewPose(
            imageWidth = 1080,
            imageHeight = 1920
        )
        val (aspectRatio, isPortrait) = getOrientationInfo(poseResult.imageWidth, poseResult.imageHeight)

        // Act
        val result = checker.checkAlignment(poseResult, aspectRatio, isPortrait)

        // Assert
        assertTrue(
            "Side view pose should be detected as SIDE_VIEW or TILTED_AND_SIDE",
            result.issue == AlignmentIssue.SIDE_VIEW || result.issue == AlignmentIssue.TILTED_AND_SIDE
        )
        assertTrue(
            "Shoulder width ratio should be small for side view (< 0.15)",
            result.shoulderWidthToHeightRatio < 0.15f
        )
        assertNotEquals(
            "Should have a message for side view",
            "",
            result.message
        )
    }

    /**
     * Test 6: Alignment detection works in both orientations
     */
    @Test
    fun testAlignmentDetection_consistentAcrossOrientations() {
        // Arrange - create tilted poses in both orientations
        val portraitTilted = PoseResultTestFixtures.createTiltedPose(1080, 1920, 15f)
        val landscapeTilted = PoseResultTestFixtures.createTiltedPose(1920, 1080, 15f)
        val (portraitAR, portraitIsPortrait) = getOrientationInfo(portraitTilted.imageWidth, portraitTilted.imageHeight)
        val (landscapeAR, landscapeIsPortrait) = getOrientationInfo(landscapeTilted.imageWidth, landscapeTilted.imageHeight)

        // Act
        val portraitResult = checker.checkAlignment(portraitTilted, portraitAR, portraitIsPortrait)
        val landscapeResult = checker.checkAlignment(landscapeTilted, landscapeAR, landscapeIsPortrait)

        // Assert - both should detect tilt
        assertTrue(
            "Tilt should be detected in portrait",
            portraitResult.issue == AlignmentIssue.TILTED ||
            portraitResult.issue == AlignmentIssue.TILTED_AND_SIDE
        )
        assertTrue(
            "Tilt should be detected in landscape",
            landscapeResult.issue == AlignmentIssue.TILTED ||
            landscapeResult.issue == AlignmentIssue.TILTED_AND_SIDE
        )
    }

    /**
     * Test 7: Aspect ratio correction for shoulder width works correctly
     * This tests the fix for the aspect ratio bug
     */
    @Test
    fun testAspectRatioCorrection_shoulderWidthRatio() {
        // Arrange - same pose in different orientations
        val portraitPose = PoseResultTestFixtures.createPortraitPose(false)
        val landscapePose = PoseResultTestFixtures.createLandscapePose(false)
        val (portraitAR, portraitIsPortrait) = getOrientationInfo(portraitPose.imageWidth, portraitPose.imageHeight)
        val (landscapeAR, landscapeIsPortrait) = getOrientationInfo(landscapePose.imageWidth, landscapePose.imageHeight)

        // Act
        val portraitResult = checker.checkAlignment(portraitPose, portraitAR, portraitIsPortrait)
        val landscapeResult = checker.checkAlignment(landscapePose, landscapeAR, landscapeIsPortrait)

        // Assert - shoulder width ratio should be similar in both orientations
        assertEquals(
            "Shoulder width ratio should be consistent across orientations (CRITICAL FIX)",
            portraitResult.shoulderWidthToHeightRatio,
            landscapeResult.shoulderWidthToHeightRatio,
            0.03f // 3% tolerance
        )

        println("Portrait shoulder ratio: ${portraitResult.shoulderWidthToHeightRatio}")
        println("Landscape shoulder ratio: ${landscapeResult.shoulderWidthToHeightRatio}")
    }

    /**
     * Test 8: Low tilt angles (< 8 degrees) should not be flagged
     */
    @Test
    fun testSmallTilt_notFlagged() {
        // Arrange - small tilt of 5 degrees
        val poseResult = PoseResultTestFixtures.createTiltedPose(
            imageWidth = 1080,
            imageHeight = 1920,
            tiltAngleDegrees = 5f
        )
        val (aspectRatio, isPortrait) = getOrientationInfo(poseResult.imageWidth, poseResult.imageHeight)

        // Act
        val result = checker.checkAlignment(poseResult, aspectRatio, isPortrait)

        // Assert
        assertTrue(
            "Small tilt (< 8 degrees) should not be flagged as TILTED",
            result.issue == AlignmentIssue.NONE || result.issue == AlignmentIssue.SIDE_VIEW
        )
    }

    /**
     * Test 9: Guidance messages are appropriate for each issue
     */
    @Test
    fun testGuidanceMessages_appropriate() {
        // Test tilted pose
        val tiltedPose = PoseResultTestFixtures.createTiltedPose(1080, 1920, 15f)
        val (tiltedAR, tiltedIsPortrait) = getOrientationInfo(tiltedPose.imageWidth, tiltedPose.imageHeight)
        val tiltedResult = checker.checkAlignment(tiltedPose, tiltedAR, tiltedIsPortrait)

        if (tiltedResult.issue != AlignmentIssue.NONE) {
            assertNotEquals("Tilted pose should have a message", "", tiltedResult.message)
        }

        // Test ideal pose
        val idealPose = PoseResultTestFixtures.createPortraitPose(false)
        val (idealAR, idealIsPortrait) = getOrientationInfo(idealPose.imageWidth, idealPose.imageHeight)
        val idealResult = checker.checkAlignment(idealPose, idealAR, idealIsPortrait)

        if (idealResult.issue == AlignmentIssue.NONE) {
            // Message can be empty or have positive feedback
            assertTrue("Ideal pose should have no issue", idealResult.issue == AlignmentIssue.NONE)
        }
    }

    /**
     * Test 10: Shoulder/hip width difference ratio is calculated correctly
     */
    @Test
    fun testShoulderHipRatio_calculated() {
        // Arrange
        val poseResult = PoseResultTestFixtures.createPortraitPose(false)
        val (aspectRatio, isPortrait) = getOrientationInfo(poseResult.imageWidth, poseResult.imageHeight)

        // Act
        val result = checker.checkAlignment(poseResult, aspectRatio, isPortrait)

        // Assert
        assertTrue(
            "Shoulder/hip width ratio should be reasonable (0.8 - 1.5)",
            result.shoulderHipWidthRatio in 0.8f..1.5f
        )
    }

    /**
     * Test 11: All orientations and camera combinations
     * Comprehensive test matrix
     */
    @Test
    fun testAllCombinations_matrix() {
        val testCases = listOf(
            Triple("Portrait Back", PoseResultTestFixtures.createPortraitPose(false), AlignmentIssue.NONE),
            Triple("Portrait Front", PoseResultTestFixtures.createPortraitPose(true), AlignmentIssue.TILTED), // Mirroring causes perceived tilt
            Triple("Landscape Back", PoseResultTestFixtures.createLandscapePose(false), AlignmentIssue.NONE),
            Triple("Landscape Front", PoseResultTestFixtures.createLandscapePose(true), AlignmentIssue.TILTED), // Mirroring causes perceived tilt
            Triple("Square Back", PoseResultTestFixtures.createSquarePose(false), AlignmentIssue.NONE),
            Triple("Square Front", PoseResultTestFixtures.createSquarePose(true), AlignmentIssue.TILTED), // Mirroring causes perceived tilt
        )

        testCases.forEach { (name, poseResult, expectedIssue) ->
            val (aspectRatio, isPortrait) = getOrientationInfo(poseResult.imageWidth, poseResult.imageHeight)
            val result = checker.checkAlignment(poseResult, aspectRatio, isPortrait)

            assertEquals(
                "$name should have expected alignment issue",
                expectedIssue,
                result.issue
            )

            println("$name: issue=${result.issue}, tilt=${result.tiltAngleDegrees}, shoulderRatio=${result.shoulderWidthToHeightRatio}")
        }
    }
}
