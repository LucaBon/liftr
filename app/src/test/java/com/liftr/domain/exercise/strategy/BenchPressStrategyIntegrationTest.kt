package com.liftr.domain.exercise.strategy

import com.liftr.domain.exercise.ExercisePhaseState
import com.liftr.domain.exercise.Severity
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.AnthropometricData
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import com.liftr.domain.model.VelocityData
import com.liftr.domain.value.Kilograms
import com.liftr.domain.value.Meters
import com.liftr.domain.value.MetersPerSecond
import com.liftr.domain.vision.IPositionCalculator
import com.liftr.vision.calculator.BodyPartPositions
import com.liftr.vision.tracking.MovementPhase
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for BenchPressStrategy.
 *
 * Tests the complete workflow of phase detection, form validation,
 * and metrics calculation with realistic wrist-based tracking data.
 */
class BenchPressStrategyIntegrationTest {

    private lateinit var positionCalculator: IPositionCalculator
    private lateinit var logger: Logger
    private lateinit var strategy: BenchPressStrategy

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
        strategy = BenchPressStrategy(positionCalculator, logger)
    }

    @Test
    fun `complete bench press rep should progress through all phases correctly`() {
        // Setup: lifter lying on bench, bar at lockout position
        every { positionCalculator.getBodyPartPositions(any()) } returns BodyPartPositions(
            headY = 100f, shouldersY = 200f, hipsY = 300f, kneesY = null,
            wristsY = 180f, wristsX = 250f, elbowsY = 190f, elbowsX = 240f
        )

        // Starting position (bar at lockout)
        every { positionCalculator.pixelsToMeters(180f, any(), any()) } returns 0.50f // wrists Y
        every { positionCalculator.pixelsToMeters(250f, any(), any()) } returns 0.70f // wrists X
        every { positionCalculator.pixelsToMeters(190f, any(), any()) } returns 0.55f // elbows Y
        every { positionCalculator.pixelsToMeters(240f, any(), any()) } returns 0.65f // elbows X
        every { positionCalculator.pixelsToMeters(100f, any(), any()) } returns 0.30f // head
        every { positionCalculator.pixelsToMeters(200f, any(), any()) } returns 0.40f // shoulders
        every { positionCalculator.pixelsToMeters(300f, any(), any()) } returns 0.60f // hips

        val poseResult = createMockPoseResult()

        // Phase 1: IDLE - Initialize starting position
        var state = ExercisePhaseState.initial()
        var result = strategy.detectPhase(poseResult, calibrationData, state)
        assertEquals(MovementPhase.IDLE, result.newState.currentPhase)
        assertFalse(result.repCompleted)
        state = result.newState

        // Phase 2: Bar descends toward chest -> ECCENTRIC
        every { positionCalculator.pixelsToMeters(180f, any(), any()) } returns 0.58f // wrists descended 8cm
        result = strategy.detectPhase(poseResult, calibrationData, state)
        assertEquals(MovementPhase.ECCENTRIC, result.newState.currentPhase)
        assertFalse(result.repCompleted)
        state = result.newState

        // Phase 3: Bar reaches chest (sufficient depth) -> BOTTOM
        every { positionCalculator.pixelsToMeters(180f, any(), any()) } returns 0.70f // wrists descended 20cm total
        result = strategy.detectPhase(poseResult, calibrationData, state)
        assertEquals(MovementPhase.BOTTOM, result.newState.currentPhase)
        assertFalse(result.repCompleted)
        state = result.newState

        // Phase 4: Bar starts moving up -> CONCENTRIC
        every { positionCalculator.pixelsToMeters(180f, any(), any()) } returns 0.63f // ascending from lowest
        result = strategy.detectPhase(poseResult, calibrationData, state)
        assertEquals(MovementPhase.CONCENTRIC, result.newState.currentPhase)
        assertFalse(result.repCompleted)
        state = result.newState

        // Phase 5: Bar returns to lockout -> TOP (rep complete)
        // Use a PoseResult with extended arms (elbow angle >= 150°) for lockout detection
        val lockoutPoseResult = createLockoutPoseResult()
        every { positionCalculator.pixelsToMeters(180f, any(), any()) } returns 0.52f // near starting position
        result = strategy.detectPhase(lockoutPoseResult, calibrationData, state)
        assertEquals(MovementPhase.TOP, result.newState.currentPhase)
        assertTrue(result.repCompleted)
    }

    @Test
    fun `insufficient descent should prevent transition to BOTTOM phase`() {
        every { positionCalculator.getBodyPartPositions(any()) } returns BodyPartPositions(
            headY = 100f, shouldersY = 200f, hipsY = 300f, kneesY = null,
            wristsY = 180f, wristsX = 250f, elbowsY = 190f, elbowsX = 240f
        )

        // Starting position
        every { positionCalculator.pixelsToMeters(180f, any(), any()) } returns 0.50f
        every { positionCalculator.pixelsToMeters(250f, any(), any()) } returns 0.70f
        every { positionCalculator.pixelsToMeters(190f, any(), any()) } returns 0.55f
        every { positionCalculator.pixelsToMeters(240f, any(), any()) } returns 0.65f
        every { positionCalculator.pixelsToMeters(100f, any(), any()) } returns 0.30f
        every { positionCalculator.pixelsToMeters(200f, any(), any()) } returns 0.40f
        every { positionCalculator.pixelsToMeters(300f, any(), any()) } returns 0.60f

        val poseResult = createMockPoseResult()

        // Initialize
        var state = ExercisePhaseState.initial()
        var result = strategy.detectPhase(poseResult, calibrationData, state)
        state = result.newState

        // Shallow descent -> ECCENTRIC
        every { positionCalculator.pixelsToMeters(180f, any(), any()) } returns 0.58f
        result = strategy.detectPhase(poseResult, calibrationData, state)
        assertEquals(MovementPhase.ECCENTRIC, result.newState.currentPhase)
        state = result.newState

        // Still not enough depth (only 10cm, need 15cm)
        every { positionCalculator.pixelsToMeters(180f, any(), any()) } returns 0.60f
        result = strategy.detectPhase(poseResult, calibrationData, state)
        assertEquals(MovementPhase.ECCENTRIC, result.newState.currentPhase) // Still ECCENTRIC
        assertFalse(result.repCompleted)
    }

    @Test
    fun `exercise type should be BENCH_PRESS`() {
        assertEquals(ExerciseType.BENCH_PRESS, strategy.exerciseType)
    }

    @Test
    fun `required landmarks should include shoulders elbows wrists and hips`() {
        val landmarks = strategy.getRequiredLandmarks()

        assertTrue(landmarks.contains(PoseResult.LEFT_SHOULDER))
        assertTrue(landmarks.contains(PoseResult.RIGHT_SHOULDER))
        assertTrue(landmarks.contains(PoseResult.LEFT_ELBOW))
        assertTrue(landmarks.contains(PoseResult.RIGHT_ELBOW))
        assertTrue(landmarks.contains(PoseResult.LEFT_WRIST))
        assertTrue(landmarks.contains(PoseResult.RIGHT_WRIST))
        assertTrue(landmarks.contains(PoseResult.LEFT_HIP))
        assertTrue(landmarks.contains(PoseResult.RIGHT_HIP))
    }

    @Test
    fun `criteria should have sensible defaults`() {
        val criteria = strategy.getCriteria()

        assertEquals(0.15f, criteria.minDescentDepth.value, 0.01f)
        assertEquals(0.10f, criteria.returnTolerance.value, 0.01f)
        assertEquals(0.05f, criteria.minVelocity.value, 0.01f)
        assertFalse(criteria.targetBodyParts.isEmpty())
    }

    @Test
    fun `calculateMetrics should return correct power values`() {
        every { positionCalculator.getBodyPartPositions(any()) } returns BodyPartPositions(
            headY = 100f, shouldersY = 200f, hipsY = 300f, kneesY = null,
            wristsY = 180f, wristsX = 250f, elbowsY = 190f
        )

        every { positionCalculator.pixelsToMeters(any(), any(), any()) } returns 0.5f

        val poseResult = createMockPoseResult()
        val velocityData = VelocityData(
            instantaneous = MetersPerSecond(0.5f),
            average = MetersPerSecond(0.4f),
            timestamp = System.currentTimeMillis(),
            position = Meters(0.20f)
        )

        val weight = Kilograms(80f)
        val metrics = strategy.calculateMetrics(poseResult, velocityData, weight)

        assertNotNull(metrics)
        assertEquals(0.5f, metrics.peakVelocity.value, 0.01f)

        // P = F * v = (80 * 9.81) * velocity
        val expectedForce = 80f * 9.81f
        assertEquals(expectedForce * 0.4f, metrics.avgPower.value, 0.1f)
        assertEquals(expectedForce * 0.5f, metrics.peakPower.value, 0.1f)
    }

    @Test
    fun `good form bench press should pass validation`() {
        // Proper bench form: elbows tucked, bar path vertical, symmetric press
        val landmarks = createLandmarksWithBenchForm(
            shoulderWidth = 0.20f,
            elbowWidth = 0.22f, // slightly wider than shoulders = good tuck
            leftWristY = 0.5f,
            rightWristY = 0.5f  // symmetric
        )

        val poseResult = PoseResult(
            landmarks = landmarks,
            timestamp = System.currentTimeMillis(),
            imageWidth = 1920,
            imageHeight = 1080,
            isPortrait = false
        )

        every { positionCalculator.getBodyPartPositions(any()) } returns BodyPartPositions(
            headY = 100f, shouldersY = 200f, hipsY = 300f, kneesY = null,
            wristsY = 180f, wristsX = 250f
        )
        every { positionCalculator.pixelsToMeters(any(), any(), any()) } returns 0.5f

        val validation = strategy.validateForm(poseResult, calibrationData)
        assertTrue(validation.isValid)
    }

    @Test
    fun `reset should clear state and allow new rep`() {
        every { positionCalculator.getBodyPartPositions(any()) } returns BodyPartPositions(
            headY = 100f, shouldersY = 200f, hipsY = 300f, kneesY = null,
            wristsY = 180f, wristsX = 250f, elbowsY = 190f
        )

        every { positionCalculator.pixelsToMeters(any(), any(), any()) } returns 0.5f

        val poseResult = createMockPoseResult()

        // Initialize and start a rep
        var state = ExercisePhaseState.initial()
        strategy.detectPhase(poseResult, calibrationData, state)

        // Reset
        state = ExercisePhaseState.initial()
        val result = strategy.detectPhase(poseResult, calibrationData, state)

        assertEquals(MovementPhase.IDLE, result.newState.currentPhase)
        assertTrue(result.debugInfo.contains("Starting position set"))
    }

    private fun createMockPoseResult(): PoseResult {
        val landmarks = List(33) { PoseKeypoint(0f, 0f, 0f, 1f) }
        return PoseResult(
            landmarks = landmarks,
            timestamp = System.currentTimeMillis(),
            imageWidth = 1920,
            imageHeight = 1080,
            isPortrait = false
        )
    }

    /**
     * Creates a PoseResult with arm landmarks positioned for full lockout
     * (shoulder-elbow-wrist nearly collinear = ~180° elbow angle).
     */
    private fun createLockoutPoseResult(): PoseResult {
        val landmarks = MutableList(33) { PoseKeypoint(0f, 0f, 0f, 1f) }

        // Arms fully extended: shoulder, elbow, wrist in a vertical line
        landmarks[PoseResult.LEFT_SHOULDER] = PoseKeypoint(0.4f, 0.3f, 0f, 1f)
        landmarks[PoseResult.RIGHT_SHOULDER] = PoseKeypoint(0.6f, 0.3f, 0f, 1f)
        landmarks[PoseResult.LEFT_ELBOW] = PoseKeypoint(0.4f, 0.5f, 0f, 1f)
        landmarks[PoseResult.RIGHT_ELBOW] = PoseKeypoint(0.6f, 0.5f, 0f, 1f)
        landmarks[PoseResult.LEFT_WRIST] = PoseKeypoint(0.4f, 0.7f, 0f, 1f)
        landmarks[PoseResult.RIGHT_WRIST] = PoseKeypoint(0.6f, 0.7f, 0f, 1f)
        landmarks[PoseResult.LEFT_HIP] = PoseKeypoint(0.45f, 0.6f, 0f, 1f)
        landmarks[PoseResult.RIGHT_HIP] = PoseKeypoint(0.55f, 0.6f, 0f, 1f)

        return PoseResult(
            landmarks = landmarks,
            timestamp = System.currentTimeMillis(),
            imageWidth = 1920,
            imageHeight = 1080,
            isPortrait = false
        )
    }

    private fun createLandmarksWithBenchForm(
        shoulderWidth: Float,
        elbowWidth: Float,
        leftWristY: Float,
        rightWristY: Float
    ): List<PoseKeypoint> {
        val landmarks = MutableList(33) { PoseKeypoint(0.5f, 0.5f, 0f, 0.1f) }

        // Shoulders
        landmarks[PoseResult.LEFT_SHOULDER] = PoseKeypoint(0.5f - shoulderWidth / 2, 0.3f, 0f, 1f)
        landmarks[PoseResult.RIGHT_SHOULDER] = PoseKeypoint(0.5f + shoulderWidth / 2, 0.3f, 0f, 1f)

        // Elbows
        landmarks[PoseResult.LEFT_ELBOW] = PoseKeypoint(0.5f - elbowWidth / 2, 0.4f, 0f, 1f)
        landmarks[PoseResult.RIGHT_ELBOW] = PoseKeypoint(0.5f + elbowWidth / 2, 0.4f, 0f, 1f)

        // Wrists
        landmarks[PoseResult.LEFT_WRIST] = PoseKeypoint(0.45f, leftWristY, 0f, 1f)
        landmarks[PoseResult.RIGHT_WRIST] = PoseKeypoint(0.55f, rightWristY, 0f, 1f)

        // Hips
        landmarks[PoseResult.LEFT_HIP] = PoseKeypoint(0.45f, 0.6f, 0f, 1f)
        landmarks[PoseResult.RIGHT_HIP] = PoseKeypoint(0.55f, 0.6f, 0f, 1f)

        return landmarks
    }
}
