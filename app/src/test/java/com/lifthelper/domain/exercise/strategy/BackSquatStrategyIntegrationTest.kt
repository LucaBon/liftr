package com.lifthelper.domain.exercise.strategy

import com.lifthelper.domain.exercise.Severity
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.model.AnthropometricData
import com.lifthelper.domain.model.CalibrationData
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.PoseKeypoint
import com.lifthelper.domain.model.PoseResult
import com.lifthelper.domain.model.VelocityData
import com.lifthelper.domain.value.Joules
import com.lifthelper.domain.value.Kilograms
import com.lifthelper.domain.value.Meters
import com.lifthelper.domain.value.MetersPerSecond
import com.lifthelper.domain.value.Watts
import com.lifthelper.domain.vision.IPositionCalculator
import com.lifthelper.vision.tracking.MovementPhase
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for BackSquatStrategy.
 *
 * Tests the complete workflow of phase detection, form validation,
 * and metrics calculation with realistic pose data.
 */
class BackSquatStrategyIntegrationTest {

    private lateinit var positionCalculator: IPositionCalculator
    private lateinit var logger: Logger
    private lateinit var strategy: BackSquatStrategy

    private val calibrationData = CalibrationData(
        pixelsPerMeter = 500f,
        userHeight = 1.75f,
        shoulderWidth = 0.45f,
        anthropometricData = AnthropometricData.getDefaultRatios(1.75f, 75f),
        timestamp = System.currentTimeMillis()
    )

    @Before
    fun setup() {
        positionCalculator = mockk()
        logger = mockk(relaxed = true)
        strategy = BackSquatStrategy(positionCalculator, logger)
    }

    @Test
    fun `complete squat rep should progress through all phases correctly`() {
        // Setup position calculator to return realistic body positions
        every { positionCalculator.getBodyPartPositions(any()) } returns mockk {
            every { headY } returns 100f
            every { shouldersY } returns 200f
            every { hipsY } returns 500f
            every { kneesY } returns 800f
        }

        // Starting position
        every { positionCalculator.pixelsToMeters(100f, any(), any()) } returns 0.5f
        every { positionCalculator.pixelsToMeters(200f, any(), any()) } returns 1.0f
        every { positionCalculator.pixelsToMeters(500f, any(), any()) } returns 2.0f
        every { positionCalculator.pixelsToMeters(800f, any(), any()) } returns 3.0f

        val poseResult = createMockPoseResult()

        // Phase 1: IDLE - Initialize
        var result = strategy.detectPhase(poseResult, calibrationData, MovementPhase.IDLE)
        assertEquals(MovementPhase.IDLE, result.newPhase)
        assertFalse(result.repCompleted)

        // Phase 2: Start descent - transition to ECCENTRIC
        every { positionCalculator.pixelsToMeters(100f, any(), any()) } returns 0.9f // Descended 40cm
        every { positionCalculator.pixelsToMeters(200f, any(), any()) } returns 1.4f

        result = strategy.detectPhase(poseResult, calibrationData, MovementPhase.IDLE)
        assertEquals(MovementPhase.ECCENTRIC, result.newPhase)
        assertFalse(result.repCompleted)

        // Phase 3: Continue descent - reach proper depth
        every { positionCalculator.pixelsToMeters(100f, any(), any()) } returns 1.1f // Descended 60cm
        every { positionCalculator.pixelsToMeters(200f, any(), any()) } returns 1.6f
        every { positionCalculator.pixelsToMeters(500f, any(), any()) } returns 3.3f // Hips at or below knees
        every { positionCalculator.pixelsToMeters(800f, any(), any()) } returns 3.2f // Knees

        result = strategy.detectPhase(poseResult, calibrationData, MovementPhase.ECCENTRIC)
        assertEquals(MovementPhase.BOTTOM, result.newPhase)
        assertFalse(result.repCompleted)

        // Phase 4: At bottom - transition to CONCENTRIC
        every { positionCalculator.pixelsToMeters(100f, any(), any()) } returns 1.05f // Started ascending
        every { positionCalculator.pixelsToMeters(200f, any(), any()) } returns 1.55f

        result = strategy.detectPhase(poseResult, calibrationData, MovementPhase.BOTTOM)
        assertEquals(MovementPhase.CONCENTRIC, result.newPhase)
        assertFalse(result.repCompleted)

        // Phase 5: Return to starting position - complete rep
        every { positionCalculator.pixelsToMeters(100f, any(), any()) } returns 0.55f // Near starting position
        every { positionCalculator.pixelsToMeters(200f, any(), any()) } returns 1.05f
        every { positionCalculator.pixelsToMeters(500f, any(), any()) } returns 2.0f
        every { positionCalculator.pixelsToMeters(800f, any(), any()) } returns 3.0f

        result = strategy.detectPhase(poseResult, calibrationData, MovementPhase.CONCENTRIC)
        assertEquals(MovementPhase.TOP, result.newPhase)
        assertTrue(result.repCompleted)
    }

    @Test
    fun `insufficient depth should be detected in form validation`() {
        // Setup shallow squat
        every { positionCalculator.getBodyPartPositions(any()) } returns mockk {
            every { headY } returns 100f
            every { shouldersY } returns 200f
            every { hipsY } returns 400f // Hips not low enough
            every { kneesY } returns 500f // Hips above knees
        }

        every { positionCalculator.pixelsToMeters(100f, any(), any()) } returns 0.75f
        every { positionCalculator.pixelsToMeters(200f, any(), any()) } returns 1.25f
        every { positionCalculator.pixelsToMeters(400f, any(), any()) } returns 1.8f
        every { positionCalculator.pixelsToMeters(500f, any(), any()) } returns 2.0f

        val poseResult = createMockPoseResult()

        // Initialize strategy
        strategy.detectPhase(poseResult, calibrationData, MovementPhase.IDLE)

        // Move to eccentric phase
        strategy.detectPhase(poseResult, calibrationData, MovementPhase.ECCENTRIC)

        // Validate form at insufficient depth
        val validation = strategy.validateForm(poseResult, calibrationData)

        assertFalse(validation.issues.isEmpty())
        assertTrue(validation.issues.any { it.message.contains("depth") })
    }

    @Test
    fun `calculateMetrics should return correct values with velocity data`() {
        // Setup positions for metrics calculation
        every { positionCalculator.getBodyPartPositions(any()) } returns mockk {
            every { headY } returns 100f
            every { shouldersY } returns 200f
            every { hipsY } returns 500f
            every { kneesY } returns 800f
        }

        every { positionCalculator.pixelsToMeters(100f, any(), any()) } returns 0.5f
        every { positionCalculator.pixelsToMeters(200f, any(), any()) } returns 1.0f
        every { positionCalculator.pixelsToMeters(500f, any(), any()) } returns 2.0f
        every { positionCalculator.pixelsToMeters(800f, any(), any()) } returns 3.0f

        val poseResult = createMockPoseResult()

        // Initialize starting position
        strategy.detectPhase(poseResult, calibrationData, MovementPhase.IDLE)

        // Move to a different position
        every { positionCalculator.pixelsToMeters(100f, any(), any()) } returns 1.0f
        every { positionCalculator.pixelsToMeters(200f, any(), any()) } returns 1.5f

        strategy.detectPhase(poseResult, calibrationData, MovementPhase.ECCENTRIC)

        val velocityData = VelocityData(
            instantaneous = MetersPerSecond(0.8f),
            average = MetersPerSecond(0.6f),
            timestamp = System.currentTimeMillis(),
            position = Meters(1.25f)
        )

        val weight = Kilograms(100f)
        val metrics = strategy.calculateMetrics(poseResult, velocityData, weight)

        // Verify metrics are calculated
        assertNotNull(metrics)
        assertTrue(metrics.displacement.value > 0f)
        assertEquals(0.8f, metrics.peakVelocity.value, 0.01f)
        assertTrue(metrics.avgPower.value > 0f)
        assertTrue(metrics.peakPower.value > 0f)
        assertTrue(metrics.work.value > 0f)

        // Verify power calculation: P = F * v = (m * g) * v
        val expectedForce = weight.value * 9.81f
        val expectedAvgPower = expectedForce * 0.6f
        val expectedPeakPower = expectedForce * 0.8f
        assertEquals(expectedAvgPower, metrics.avgPower.value, 0.1f)
        assertEquals(expectedPeakPower, metrics.peakPower.value, 0.1f)
    }

    @Test
    fun `reset should clear all state and allow new rep`() {
        // Setup and perform partial rep
        every { positionCalculator.getBodyPartPositions(any()) } returns mockk {
            every { headY } returns 100f
            every { shouldersY } returns 200f
            every { hipsY } returns 500f
            every { kneesY } returns 800f
        }

        every { positionCalculator.pixelsToMeters(100f, any(), any()) } returns 0.5f
        every { positionCalculator.pixelsToMeters(200f, any(), any()) } returns 1.0f
        every { positionCalculator.pixelsToMeters(500f, any(), any()) } returns 2.0f
        every { positionCalculator.pixelsToMeters(800f, any(), any()) } returns 3.0f

        val poseResult = createMockPoseResult()

        // Initialize and start movement
        strategy.detectPhase(poseResult, calibrationData, MovementPhase.IDLE)

        every { positionCalculator.pixelsToMeters(100f, any(), any()) } returns 1.0f
        every { positionCalculator.pixelsToMeters(200f, any(), any()) } returns 1.5f
        strategy.detectPhase(poseResult, calibrationData, MovementPhase.ECCENTRIC)

        // Reset strategy
        strategy.reset()

        // Verify we can start fresh
        every { positionCalculator.pixelsToMeters(100f, any(), any()) } returns 0.5f
        every { positionCalculator.pixelsToMeters(200f, any(), any()) } returns 1.0f

        val result = strategy.detectPhase(poseResult, calibrationData, MovementPhase.IDLE)
        assertEquals(MovementPhase.IDLE, result.newPhase)
        assertTrue(result.debugInfo.contains("Starting position set"))
    }

    @Test
    fun `exercise type should be BACK_SQUAT`() {
        assertEquals(ExerciseType.BACK_SQUAT, strategy.exerciseType)
    }

    @Test
    fun `required landmarks should include key body parts`() {
        val landmarks = strategy.getRequiredLandmarks()

        assertTrue(landmarks.contains(PoseResult.NOSE))
        assertTrue(landmarks.contains(PoseResult.LEFT_SHOULDER))
        assertTrue(landmarks.contains(PoseResult.RIGHT_SHOULDER))
        assertTrue(landmarks.contains(PoseResult.LEFT_HIP))
        assertTrue(landmarks.contains(PoseResult.RIGHT_HIP))
        assertTrue(landmarks.contains(PoseResult.LEFT_KNEE))
        assertTrue(landmarks.contains(PoseResult.RIGHT_KNEE))
    }

    @Test
    fun `criteria should have sensible defaults`() {
        val criteria = strategy.getCriteria()

        assertEquals(0.30f, criteria.minDescentDepth.value, 0.01f)
        assertEquals(0.15f, criteria.returnTolerance.value, 0.01f)
        assertEquals(0.1f, criteria.minVelocity.value, 0.01f)
        assertFalse(criteria.targetBodyParts.isEmpty())
    }

    @Test
    fun `good form squat should pass validation`() {
        // Setup proper depth squat
        every { positionCalculator.getBodyPartPositions(any()) } returns mockk {
            every { headY } returns 100f
            every { shouldersY } returns 200f
            every { hipsY } returns 850f // Hips below knees
            every { kneesY } returns 800f
        }

        every { positionCalculator.pixelsToMeters(100f, any(), any()) } returns 1.1f
        every { positionCalculator.pixelsToMeters(200f, any(), any()) } returns 1.6f
        every { positionCalculator.pixelsToMeters(850f, any(), any()) } returns 3.3f
        every { positionCalculator.pixelsToMeters(800f, any(), any()) } returns 3.2f

        val poseResult = createMockPoseResult()

        // Initialize
        strategy.detectPhase(poseResult, calibrationData, MovementPhase.IDLE)
        strategy.detectPhase(poseResult, calibrationData, MovementPhase.ECCENTRIC)

        // Validate form at bottom
        val validation = strategy.validateForm(poseResult, calibrationData)

        assertTrue(validation.isValid)
        assertTrue(validation.issues.none { it.severity == Severity.CRITICAL })
    }

    private fun createMockPoseResult(): PoseResult {
        val landmarks = List(33) { PoseKeypoint(0f, 0f, 0f, 1f) }
        return PoseResult(
            landmarks = landmarks,
            timestamp = System.currentTimeMillis()
        )
    }
}
