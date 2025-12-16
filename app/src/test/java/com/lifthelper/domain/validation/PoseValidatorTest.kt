package com.lifthelper.domain.validation

import com.lifthelper.domain.model.PoseKeypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PoseValidatorTest {

    private lateinit var validator: PoseValidator

    @Before
    fun setup() {
        validator = PoseValidator()
    }

    // Critical Landmarks Validation Tests
    @Test
    fun `should return Valid when all critical landmarks are visible`() {
        // Given
        val landmarks = createValidLandmarks()

        // When
        val result = validator.validateCriticalLandmarks(landmarks)

        // Then
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `should return Invalid when shoulders are not visible`() {
        // Given
        val landmarks = createValidLandmarks()
        landmarks[11] = PoseKeypoint(0.5f, 0.3f, 0f, 0.3f)  // Left shoulder low visibility
        landmarks[12] = PoseKeypoint(0.5f, 0.3f, 0f, 0.3f)  // Right shoulder low visibility

        // When
        val result = validator.validateCriticalLandmarks(landmarks)

        // Then
        assertTrue(result is ValidationResult.Invalid)
        val invalid = result as ValidationResult.Invalid
        assertEquals("Missing critical body landmarks", invalid.reason)
        assertTrue(invalid.missingLandmarks.contains(11))
        assertTrue(invalid.missingLandmarks.contains(12))
    }

    @Test
    fun `should return Invalid when hips are not visible`() {
        // Given
        val landmarks = createValidLandmarks()
        landmarks[23] = PoseKeypoint(0.5f, 0.5f, 0f, 0.3f)  // Left hip low visibility
        landmarks[24] = PoseKeypoint(0.5f, 0.5f, 0f, 0.3f)  // Right hip low visibility

        // When
        val result = validator.validateCriticalLandmarks(landmarks)

        // Then
        assertTrue(result is ValidationResult.Invalid)
        val invalid = result as ValidationResult.Invalid
        assertTrue(invalid.missingLandmarks.contains(23))
        assertTrue(invalid.missingLandmarks.contains(24))
    }

    @Test
    fun `should return Invalid when knees are not visible`() {
        // Given
        val landmarks = createValidLandmarks()
        landmarks[25] = PoseKeypoint(0.5f, 0.7f, 0f, 0.3f)  // Left knee low visibility
        landmarks[26] = PoseKeypoint(0.5f, 0.7f, 0f, 0.3f)  // Right knee low visibility

        // When
        val result = validator.validateCriticalLandmarks(landmarks)

        // Then
        assertTrue(result is ValidationResult.Invalid)
        val invalid = result as ValidationResult.Invalid
        assertTrue(invalid.missingLandmarks.contains(25))
        assertTrue(invalid.missingLandmarks.contains(26))
    }

    @Test
    fun `should include landmark names in Invalid result`() {
        // Given
        val landmarks = createValidLandmarks()
        landmarks[11] = PoseKeypoint(0.5f, 0.3f, 0f, 0.3f)  // Left shoulder low visibility

        // When
        val result = validator.validateCriticalLandmarks(landmarks)

        // Then
        assertTrue(result is ValidationResult.Invalid)
        val invalid = result as ValidationResult.Invalid
        assertTrue(invalid.landmarkNames.contains("left_shoulder"))
    }

    @Test
    fun `should include visibilities in Invalid result`() {
        // Given
        val landmarks = createValidLandmarks()
        landmarks[11] = PoseKeypoint(0.5f, 0.3f, 0f, 0.3f)  // Left shoulder with 0.3 visibility

        // When
        val result = validator.validateCriticalLandmarks(landmarks)

        // Then
        assertTrue(result is ValidationResult.Invalid)
        val invalid = result as ValidationResult.Invalid
        assertTrue(invalid.visibilities.contains(0.3f))
    }

    @Test
    fun `should handle missing landmarks as invalid`() {
        // Given - create landmarks list with fewer elements
        val landmarks = MutableList(10) { PoseKeypoint(0.5f, 0.5f, 0f, 0.9f) }

        // When
        val result = validator.validateCriticalLandmarks(landmarks)

        // Then
        assertTrue(result is ValidationResult.Invalid)
        val invalid = result as ValidationResult.Invalid
        // Should detect all critical landmarks as missing since list is too short
        assertTrue(invalid.missingLandmarks.size >= 6)
    }

    // Head Landmarks Validation Tests
    @Test
    fun `should return true when nose is visible`() {
        // Given
        val landmarks = createValidLandmarks()

        // When
        val hasHead = validator.validateHeadLandmarks(landmarks)

        // Then
        assertTrue(hasHead)
    }

    @Test
    fun `should return true when left ear is visible`() {
        // Given
        val landmarks = createValidLandmarks()
        landmarks[0] = PoseKeypoint(0.5f, 0.1f, 0f, 0.3f)  // Nose not visible

        // When
        val hasHead = validator.validateHeadLandmarks(landmarks)

        // Then
        assertTrue(hasHead)  // Still valid because ear is visible
    }

    @Test
    fun `should return true when right ear is visible`() {
        // Given
        val landmarks = createValidLandmarks()
        landmarks[0] = PoseKeypoint(0.5f, 0.1f, 0f, 0.3f)  // Nose not visible
        landmarks[7] = PoseKeypoint(0.4f, 0.1f, 0f, 0.3f)  // Left ear not visible

        // When
        val hasHead = validator.validateHeadLandmarks(landmarks)

        // Then
        assertTrue(hasHead)  // Still valid because right ear is visible
    }

    @Test
    fun `should return false when no head landmarks are visible`() {
        // Given
        val landmarks = createValidLandmarks()
        landmarks[0] = PoseKeypoint(0.5f, 0.1f, 0f, 0.3f)  // Nose not visible
        landmarks[7] = PoseKeypoint(0.4f, 0.1f, 0f, 0.3f)  // Left ear not visible
        landmarks[8] = PoseKeypoint(0.6f, 0.1f, 0f, 0.3f)  // Right ear not visible

        // When
        val hasHead = validator.validateHeadLandmarks(landmarks)

        // Then
        assertFalse(hasHead)
    }

    @Test
    fun `should handle missing head landmarks in list`() {
        // Given - create landmarks list with fewer elements (all with low visibility)
        val landmarks = MutableList(5) { PoseKeypoint(0.5f, 0.5f, 0f, 0.3f) }

        // When
        val hasHead = validator.validateHeadLandmarks(landmarks)

        // Then
        assertFalse(hasHead)
    }

    // Ankle Visibility Tests
    @Test
    fun `should return true when both ankles are visible`() {
        // Given
        val landmarks = createValidLandmarks()

        // When
        val hasAnkles = validator.validateAnklesVisible(landmarks)

        // Then
        assertTrue(hasAnkles)
    }

    @Test
    fun `should return false when left ankle is not visible`() {
        // Given
        val landmarks = createValidLandmarks()
        landmarks[27] = PoseKeypoint(0.4f, 0.9f, 0f, 0.3f)  // Left ankle low visibility

        // When
        val hasAnkles = validator.validateAnklesVisible(landmarks)

        // Then
        assertFalse(hasAnkles)
    }

    @Test
    fun `should return false when right ankle is not visible`() {
        // Given
        val landmarks = createValidLandmarks()
        landmarks[28] = PoseKeypoint(0.6f, 0.9f, 0f, 0.3f)  // Right ankle low visibility

        // When
        val hasAnkles = validator.validateAnklesVisible(landmarks)

        // Then
        assertFalse(hasAnkles)
    }

    @Test
    fun `should return false when both ankles are not visible`() {
        // Given
        val landmarks = createValidLandmarks()
        landmarks[27] = PoseKeypoint(0.4f, 0.9f, 0f, 0.3f)  // Left ankle low visibility
        landmarks[28] = PoseKeypoint(0.6f, 0.9f, 0f, 0.3f)  // Right ankle low visibility

        // When
        val hasAnkles = validator.validateAnklesVisible(landmarks)

        // Then
        assertFalse(hasAnkles)
    }

    // Visible Head Landmarks Tests
    @Test
    fun `should return all visible head landmarks`() {
        // Given
        val landmarks = createValidLandmarks()

        // When
        val visibleHead = validator.getVisibleHeadLandmarks(landmarks)

        // Then
        assertEquals(3, visibleHead.size)
        assertTrue(visibleHead.contains(0))  // nose
        assertTrue(visibleHead.contains(7))  // left ear
        assertTrue(visibleHead.contains(8))  // right ear
    }

    @Test
    fun `should return only visible head landmarks when some are hidden`() {
        // Given
        val landmarks = createValidLandmarks()
        landmarks[7] = PoseKeypoint(0.4f, 0.1f, 0f, 0.3f)  // Left ear not visible

        // When
        val visibleHead = validator.getVisibleHeadLandmarks(landmarks)

        // Then
        assertEquals(2, visibleHead.size)
        assertTrue(visibleHead.contains(0))  // nose
        assertTrue(visibleHead.contains(8))  // right ear
        assertFalse(visibleHead.contains(7))  // left ear not in list
    }

    @Test
    fun `should return empty list when no head landmarks are visible`() {
        // Given
        val landmarks = createValidLandmarks()
        landmarks[0] = PoseKeypoint(0.5f, 0.1f, 0f, 0.3f)  // Nose not visible
        landmarks[7] = PoseKeypoint(0.4f, 0.1f, 0f, 0.3f)  // Left ear not visible
        landmarks[8] = PoseKeypoint(0.6f, 0.1f, 0f, 0.3f)  // Right ear not visible

        // When
        val visibleHead = validator.getVisibleHeadLandmarks(landmarks)

        // Then
        assertEquals(0, visibleHead.size)
    }

    // Left Elbow Visibility Tests
    @Test
    fun `should return true when left elbow is visible`() {
        // Given
        val landmarks = createValidLandmarks()

        // When
        val hasElbow = validator.validateLeftElbowVisible(landmarks)

        // Then
        assertTrue(hasElbow)
    }

    @Test
    fun `should return false when left elbow is not visible`() {
        // Given
        val landmarks = createValidLandmarks()
        landmarks[13] = PoseKeypoint(0.4f, 0.4f, 0f, 0.3f)  // Left elbow low visibility

        // When
        val hasElbow = validator.validateLeftElbowVisible(landmarks)

        // Then
        assertFalse(hasElbow)
    }

    @Test
    fun `should return false when left elbow is missing from list`() {
        // Given
        val landmarks = MutableList(10) { PoseKeypoint(0.5f, 0.5f, 0f, 0.9f) }

        // When
        val hasElbow = validator.validateLeftElbowVisible(landmarks)

        // Then
        assertFalse(hasElbow)
    }

    // Left Wrist Visibility Tests
    @Test
    fun `should return true when left wrist is visible`() {
        // Given
        val landmarks = createValidLandmarks()

        // When
        val hasWrist = validator.validateLeftWristVisible(landmarks)

        // Then
        assertTrue(hasWrist)
    }

    @Test
    fun `should return false when left wrist is not visible`() {
        // Given
        val landmarks = createValidLandmarks()
        landmarks[15] = PoseKeypoint(0.3f, 0.5f, 0f, 0.3f)  // Left wrist low visibility

        // When
        val hasWrist = validator.validateLeftWristVisible(landmarks)

        // Then
        assertFalse(hasWrist)
    }

    @Test
    fun `should return false when left wrist is missing from list`() {
        // Given
        val landmarks = MutableList(10) { PoseKeypoint(0.5f, 0.5f, 0f, 0.9f) }

        // When
        val hasWrist = validator.validateLeftWristVisible(landmarks)

        // Then
        assertFalse(hasWrist)
    }

    // Edge Cases
    @Test
    fun `should handle exact visibility threshold`() {
        // Given
        val landmarks = createValidLandmarks()
        landmarks[11] = PoseKeypoint(0.5f, 0.3f, 0f, 0.5f)  // Exactly at threshold

        // When
        val result = validator.validateCriticalLandmarks(landmarks)

        // Then
        assertTrue(result is ValidationResult.Valid)  // 0.5 should be considered valid
    }

    @Test
    fun `should handle just below visibility threshold`() {
        // Given
        val landmarks = createValidLandmarks()
        landmarks[11] = PoseKeypoint(0.5f, 0.3f, 0f, 0.49f)  // Just below threshold

        // When
        val result = validator.validateCriticalLandmarks(landmarks)

        // Then
        assertTrue(result is ValidationResult.Invalid)
    }

    // Helper function to create valid landmarks
    private fun createValidLandmarks(): MutableList<PoseKeypoint> {
        val landmarks = MutableList(33) { PoseKeypoint(0.5f, 0.5f, 0f, 0.9f) }

        // Set head landmarks with high visibility
        landmarks[0] = PoseKeypoint(0.5f, 0.1f, 0f, 0.9f)   // nose
        landmarks[7] = PoseKeypoint(0.4f, 0.1f, 0f, 0.9f)   // left_ear
        landmarks[8] = PoseKeypoint(0.6f, 0.1f, 0f, 0.9f)   // right_ear

        // Set critical body landmarks with high visibility
        landmarks[11] = PoseKeypoint(0.4f, 0.3f, 0f, 0.9f)  // left_shoulder
        landmarks[12] = PoseKeypoint(0.6f, 0.3f, 0f, 0.9f)  // right_shoulder
        landmarks[13] = PoseKeypoint(0.4f, 0.4f, 0f, 0.9f)  // left_elbow
        landmarks[15] = PoseKeypoint(0.3f, 0.5f, 0f, 0.9f)  // left_wrist
        landmarks[23] = PoseKeypoint(0.4f, 0.5f, 0f, 0.9f)  // left_hip
        landmarks[24] = PoseKeypoint(0.6f, 0.5f, 0f, 0.9f)  // right_hip
        landmarks[25] = PoseKeypoint(0.4f, 0.7f, 0f, 0.9f)  // left_knee
        landmarks[26] = PoseKeypoint(0.6f, 0.7f, 0f, 0.9f)  // right_knee
        landmarks[27] = PoseKeypoint(0.4f, 0.9f, 0f, 0.9f)  // left_ankle
        landmarks[28] = PoseKeypoint(0.6f, 0.9f, 0f, 0.9f)  // right_ankle

        return landmarks
    }
}
