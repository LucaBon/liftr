package com.lifthelper.domain.exercise.strategy

import com.lifthelper.domain.exercise.ExercisePhaseState
import com.lifthelper.domain.exercise.Severity
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.model.AnthropometricData
import com.lifthelper.domain.model.CalibrationData
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.PoseKeypoint
import com.lifthelper.domain.model.PoseResult
import com.lifthelper.domain.model.VelocityData
import com.lifthelper.domain.value.Kilograms
import com.lifthelper.domain.value.Meters
import com.lifthelper.domain.value.MetersPerSecond
import com.lifthelper.domain.vision.IPositionCalculator
import com.lifthelper.vision.calculator.BodyPartPositions
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
 * Integration tests for DeadliftStrategy.
 *
 * Tests the complete workflow of phase detection, form validation,
 * and metrics calculation with realistic hip-based tracking data.
 * Deadlift uses a reversed phase order: IDLE -> CONCENTRIC -> TOP -> ECCENTRIC -> IDLE.
 */
class DeadliftStrategyIntegrationTest {

    private lateinit var positionCalculator: IPositionCalculator
    private lateinit var logger: Logger
    private lateinit var strategy: DeadliftStrategy

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
        strategy = DeadliftStrategy(positionCalculator, logger)
    }

    @Test
    fun `complete deadlift rep should progress through concentric-first phases`() {
        // Setup: lifter bent over, bar on floor
        every { positionCalculator.getBodyPartPositions(any()) } returns BodyPartPositions(
            headY = 100f, shouldersY = 200f, hipsY = 400f, kneesY = 500f,
            hipsX = 250f, anklesY = 600f, anklesX = 260f, shouldersX = 240f
        )

        // Starting position (bent over, hips high relative to floor position)
        every { positionCalculator.pixelsToMeters(400f, any(), any()) } returns 0.80f // hips Y
        every { positionCalculator.pixelsToMeters(200f, any(), any()) } returns 0.40f // shoulders Y
        every { positionCalculator.pixelsToMeters(100f, any(), any()) } returns 0.20f // head Y
        every { positionCalculator.pixelsToMeters(500f, any(), any()) } returns 1.00f // knees Y
        every { positionCalculator.pixelsToMeters(600f, any(), any()) } returns 1.20f // ankles Y
        every { positionCalculator.pixelsToMeters(250f, any(), any()) } returns 0.50f // hips X
        every { positionCalculator.pixelsToMeters(260f, any(), any()) } returns 0.52f // ankles X
        every { positionCalculator.pixelsToMeters(240f, any(), any()) } returns 0.48f // shoulders X

        val poseResult = createMockPoseResult()

        // Phase 1: IDLE - Initialize starting position
        var state = ExercisePhaseState.initial()
        var result = strategy.detectPhase(poseResult, calibrationData, state)
        assertEquals(MovementPhase.IDLE, result.newState.currentPhase)
        assertFalse(result.repCompleted)
        state = result.newState

        // Phase 2: Hips rise (lifter pulls bar off floor) -> CONCENTRIC
        // In our coord system, hips moving up = Y decreases
        every { positionCalculator.pixelsToMeters(400f, any(), any()) } returns 0.72f // hips rose 8cm
        result = strategy.detectPhase(poseResult, calibrationData, state)
        assertEquals(MovementPhase.CONCENTRIC, result.newState.currentPhase)
        assertFalse(result.repCompleted)
        state = result.newState

        // Phase 3: Hips continue rising to lockout -> TOP (rep complete)
        // Need sufficient height (>=40cm) AND lockout (hip + knee angles >= 170°)
        // Use a PoseResult with extended hip/knee angles for lockout detection
        val lockoutPoseResult = createLockoutPoseResult()
        every { positionCalculator.pixelsToMeters(400f, any(), any()) } returns 0.35f // hips rose 45cm total
        result = strategy.detectPhase(lockoutPoseResult, calibrationData, state)
        assertEquals(MovementPhase.TOP, result.newState.currentPhase)
        assertTrue(result.repCompleted)
        state = result.newState

        // Phase 4: Lifter lowers bar -> ECCENTRIC
        every { positionCalculator.pixelsToMeters(400f, any(), any()) } returns 0.42f // hips descending
        result = strategy.detectPhase(poseResult, calibrationData, state)
        assertEquals(MovementPhase.ECCENTRIC, result.newState.currentPhase)
        assertFalse(result.repCompleted)
        state = result.newState

        // Phase 5: Bar returns to floor -> IDLE
        every { positionCalculator.pixelsToMeters(400f, any(), any()) } returns 0.78f // near starting position
        result = strategy.detectPhase(poseResult, calibrationData, state)
        assertEquals(MovementPhase.IDLE, result.newState.currentPhase)
        assertFalse(result.repCompleted)
    }

    @Test
    fun `insufficient lift height should prevent transition to TOP`() {
        every { positionCalculator.getBodyPartPositions(any()) } returns BodyPartPositions(
            headY = 100f, shouldersY = 200f, hipsY = 400f, kneesY = 500f,
            hipsX = 250f, anklesY = 600f, anklesX = 260f, shouldersX = 240f
        )

        // Starting position
        every { positionCalculator.pixelsToMeters(400f, any(), any()) } returns 0.80f
        every { positionCalculator.pixelsToMeters(200f, any(), any()) } returns 0.40f
        every { positionCalculator.pixelsToMeters(100f, any(), any()) } returns 0.20f
        every { positionCalculator.pixelsToMeters(500f, any(), any()) } returns 1.00f
        every { positionCalculator.pixelsToMeters(600f, any(), any()) } returns 1.20f
        every { positionCalculator.pixelsToMeters(250f, any(), any()) } returns 0.50f
        every { positionCalculator.pixelsToMeters(260f, any(), any()) } returns 0.52f
        every { positionCalculator.pixelsToMeters(240f, any(), any()) } returns 0.48f

        val poseResult = createMockPoseResult()

        // Initialize
        var state = ExercisePhaseState.initial()
        var result = strategy.detectPhase(poseResult, calibrationData, state)
        state = result.newState

        // Start pulling -> CONCENTRIC
        every { positionCalculator.pixelsToMeters(400f, any(), any()) } returns 0.72f
        result = strategy.detectPhase(poseResult, calibrationData, state)
        assertEquals(MovementPhase.CONCENTRIC, result.newState.currentPhase)
        state = result.newState

        // Only 20cm lift (need 40cm) -> stays in CONCENTRIC
        every { positionCalculator.pixelsToMeters(400f, any(), any()) } returns 0.60f // only 20cm
        result = strategy.detectPhase(poseResult, calibrationData, state)
        assertEquals(MovementPhase.CONCENTRIC, result.newState.currentPhase)
        assertFalse(result.repCompleted)
    }

    @Test
    fun `BOTTOM phase should skip directly to IDLE`() {
        every { positionCalculator.getBodyPartPositions(any()) } returns BodyPartPositions(
            headY = 100f, shouldersY = 200f, hipsY = 400f, kneesY = 500f,
            hipsX = 250f, anklesY = 600f, anklesX = 260f, shouldersX = 240f
        )

        every { positionCalculator.pixelsToMeters(any(), any(), any()) } returns 0.5f

        val poseResult = createMockPoseResult()

        // Force state into BOTTOM phase (which deadlift doesn't use)
        val state = ExercisePhaseState.initial().withCurrentPhase(MovementPhase.BOTTOM)
        val result = strategy.detectPhase(poseResult, calibrationData, state)

        assertEquals(MovementPhase.IDLE, result.newState.currentPhase)
        assertFalse(result.repCompleted)
    }

    @Test
    fun `exercise type should be DEADLIFT`() {
        assertEquals(ExerciseType.DEADLIFT, strategy.exerciseType)
    }

    @Test
    fun `required landmarks should include nose shoulders hips knees ankles`() {
        val landmarks = strategy.getRequiredLandmarks()

        assertTrue(landmarks.contains(PoseResult.NOSE))
        assertTrue(landmarks.contains(PoseResult.LEFT_SHOULDER))
        assertTrue(landmarks.contains(PoseResult.RIGHT_SHOULDER))
        assertTrue(landmarks.contains(PoseResult.LEFT_HIP))
        assertTrue(landmarks.contains(PoseResult.RIGHT_HIP))
        assertTrue(landmarks.contains(PoseResult.LEFT_KNEE))
        assertTrue(landmarks.contains(PoseResult.RIGHT_KNEE))
        assertTrue(landmarks.contains(PoseResult.LEFT_ANKLE))
        assertTrue(landmarks.contains(PoseResult.RIGHT_ANKLE))
    }

    @Test
    fun `criteria should have sensible defaults`() {
        val criteria = strategy.getCriteria()

        assertEquals(0.40f, criteria.minDescentDepth.value, 0.01f) // MIN_LIFT_HEIGHT
        assertEquals(0.15f, criteria.returnTolerance.value, 0.01f)
        assertEquals(0.05f, criteria.minVelocity.value, 0.01f)
        assertFalse(criteria.targetBodyParts.isEmpty())
    }

    @Test
    fun `calculateMetrics should return correct power values`() {
        every { positionCalculator.getBodyPartPositions(any()) } returns BodyPartPositions(
            headY = 100f, shouldersY = 200f, hipsY = 400f, kneesY = 500f
        )

        every { positionCalculator.pixelsToMeters(any(), any(), any()) } returns 0.5f

        val poseResult = createMockPoseResult()
        val velocityData = VelocityData(
            instantaneous = MetersPerSecond(0.6f),
            average = MetersPerSecond(0.45f),
            timestamp = System.currentTimeMillis(),
            position = Meters(0.40f)
        )

        val weight = Kilograms(140f)
        val metrics = strategy.calculateMetrics(poseResult, velocityData, weight)

        assertNotNull(metrics)
        assertEquals(0.6f, metrics.peakVelocity.value, 0.01f)

        // P = F * v = (140 * 9.81) * velocity
        val expectedForce = 140f * 9.81f
        assertEquals(expectedForce * 0.45f, metrics.avgPower.value, 0.1f)
        assertEquals(expectedForce * 0.6f, metrics.peakPower.value, 0.1f)
    }

    @Test
    fun `good form deadlift should pass validation`() {
        // Upright posture: shoulders nearly directly above hips (small torso angle)
        val landmarks = createLandmarksWithDeadliftForm(
            shoulderY = 0.25f,
            hipY = 0.50f,
            shoulderX = 0.50f,
            hipX = 0.50f, // vertical torso
            kneeAngle = 175f,
            hipAngle = 175f
        )

        val poseResult = PoseResult(
            landmarks = landmarks,
            timestamp = System.currentTimeMillis(),
            imageWidth = 1920,
            imageHeight = 1080,
            isPortrait = false
        )

        every { positionCalculator.getBodyPartPositions(any()) } returns BodyPartPositions(
            headY = 100f, shouldersY = 200f, hipsY = 400f, kneesY = 500f,
            hipsX = 250f, anklesX = 252f
        )
        every { positionCalculator.pixelsToMeters(any(), any(), any()) } returns 0.5f

        val validation = strategy.validateForm(poseResult, calibrationData)
        assertTrue(validation.isValid)
    }

    @Test
    fun `back rounding should produce CRITICAL form issue`() {
        // Excessive forward lean: shoulders far in front of hips
        val landmarks = createLandmarksWithDeadliftForm(
            shoulderY = 0.30f,
            hipY = 0.50f,
            shoulderX = 0.25f, // shoulders very far forward
            hipX = 0.50f,
            kneeAngle = 175f,
            hipAngle = 175f
        )

        val poseResult = PoseResult(
            landmarks = landmarks,
            timestamp = System.currentTimeMillis(),
            imageWidth = 1920,
            imageHeight = 1080,
            isPortrait = false
        )

        every { positionCalculator.getBodyPartPositions(any()) } returns BodyPartPositions(
            headY = 100f, shouldersY = 200f, hipsY = 400f, kneesY = 500f,
            hipsX = 250f, anklesX = 252f
        )
        every { positionCalculator.pixelsToMeters(any(), any(), any()) } returns 0.5f

        // Back rounding is a *change* from the setup posture, so the check needs
        // the angle recorded when the lift was armed. Here the torso is at
        // ~51° from vertical against a 20° setup — a 31° increase under load.
        val state = ExercisePhaseState.initial().withStartingTorsoAngle(20f)

        val validation = strategy.validateForm(poseResult, calibrationData, state)
        // Should have a critical or warning issue for back rounding
        val backIssues = validation.issues.filter {
            it.message.contains("rounding", ignoreCase = true) ||
                it.message.contains("lean", ignoreCase = true) ||
                it.message.contains("tipping", ignoreCase = true)
        }
        assertTrue(backIssues.isNotEmpty())
    }

    @Test
    fun `correct deadlift setup lean is not reported as back rounding`() {
        // A conventional deadlift sets up 45-70° from vertical. That is correct
        // form, not rounding: only an increase relative to the setup counts.
        // Regression guard for the absolute-threshold check that fired on every
        // properly executed rep.
        val landmarks = createLandmarksWithDeadliftForm(
            shoulderY = 0.30f,
            hipY = 0.50f,
            shoulderX = 0.25f,
            hipX = 0.50f,
            kneeAngle = 175f,
            hipAngle = 175f
        )

        val poseResult = PoseResult(
            landmarks = landmarks,
            timestamp = System.currentTimeMillis(),
            imageWidth = 1920,
            imageHeight = 1080,
            isPortrait = false
        )

        every { positionCalculator.getBodyPartPositions(any()) } returns BodyPartPositions(
            headY = 100f, shouldersY = 200f, hipsY = 400f, kneesY = 500f,
            hipsX = 250f, anklesX = 252f
        )
        every { positionCalculator.pixelsToMeters(any(), any(), any()) } returns 0.5f

        // Setup was already at this same steep angle — nothing has changed.
        val state = ExercisePhaseState.initial().withStartingTorsoAngle(51f)

        val validation = strategy.validateForm(poseResult, calibrationData, state)
        val backIssues = validation.issues.filter {
            it.message.contains("rounding", ignoreCase = true) ||
                it.message.contains("tipping", ignoreCase = true)
        }
        assertTrue("Correct setup lean must not be flagged: $backIssues", backIssues.isEmpty())
    }

    @Test
    fun `reset should clear state and allow new rep`() {
        every { positionCalculator.getBodyPartPositions(any()) } returns BodyPartPositions(
            headY = 100f, shouldersY = 200f, hipsY = 400f, kneesY = 500f,
            hipsX = 250f, anklesY = 600f, anklesX = 260f, shouldersX = 240f
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
     * Creates a PoseResult with body landmarks in upright standing position
     * (shoulder-hip-knee and hip-knee-ankle nearly collinear = ~180° angles for lockout).
     */
    private fun createLockoutPoseResult(): PoseResult {
        val landmarks = MutableList(33) { PoseKeypoint(0f, 0f, 0f, 1f) }

        // Standing upright: all landmarks in a vertical line
        landmarks[PoseResult.NOSE] = PoseKeypoint(0.5f, 0.10f, 0f, 1f)
        landmarks[PoseResult.LEFT_SHOULDER] = PoseKeypoint(0.45f, 0.20f, 0f, 1f)
        landmarks[PoseResult.RIGHT_SHOULDER] = PoseKeypoint(0.55f, 0.20f, 0f, 1f)
        landmarks[PoseResult.LEFT_HIP] = PoseKeypoint(0.47f, 0.50f, 0f, 1f)
        landmarks[PoseResult.RIGHT_HIP] = PoseKeypoint(0.53f, 0.50f, 0f, 1f)
        landmarks[PoseResult.LEFT_KNEE] = PoseKeypoint(0.47f, 0.70f, 0f, 1f)
        landmarks[PoseResult.RIGHT_KNEE] = PoseKeypoint(0.53f, 0.70f, 0f, 1f)
        landmarks[PoseResult.LEFT_ANKLE] = PoseKeypoint(0.47f, 0.90f, 0f, 1f)
        landmarks[PoseResult.RIGHT_ANKLE] = PoseKeypoint(0.53f, 0.90f, 0f, 1f)

        return PoseResult(
            landmarks = landmarks,
            timestamp = System.currentTimeMillis(),
            imageWidth = 1920,
            imageHeight = 1080,
            isPortrait = false
        )
    }

    /**
     * Creates landmarks simulating a deadlift position.
     * Configurable shoulder/hip positions control torso angle for back rounding tests.
     * Knee and hip angles control lockout validation.
     */
    private fun createLandmarksWithDeadliftForm(
        shoulderY: Float,
        hipY: Float,
        shoulderX: Float,
        hipX: Float,
        kneeAngle: Float, // not directly used for position but guides ankle placement
        hipAngle: Float   // not directly used for position but guides landmark layout
    ): List<PoseKeypoint> {
        val landmarks = MutableList(33) { PoseKeypoint(0.5f, 0.5f, 0f, 0.1f) }

        // Shoulders
        landmarks[PoseResult.LEFT_SHOULDER] = PoseKeypoint(shoulderX - 0.05f, shoulderY, 0f, 1f)
        landmarks[PoseResult.RIGHT_SHOULDER] = PoseKeypoint(shoulderX + 0.05f, shoulderY, 0f, 1f)

        // Hips
        landmarks[PoseResult.LEFT_HIP] = PoseKeypoint(hipX - 0.04f, hipY, 0f, 1f)
        landmarks[PoseResult.RIGHT_HIP] = PoseKeypoint(hipX + 0.04f, hipY, 0f, 1f)

        // Knees (below hips, nearly straight for good lockout)
        val kneeY = hipY + 0.20f
        landmarks[PoseResult.LEFT_KNEE] = PoseKeypoint(hipX - 0.03f, kneeY, 0f, 1f)
        landmarks[PoseResult.RIGHT_KNEE] = PoseKeypoint(hipX + 0.03f, kneeY, 0f, 1f)

        // Ankles (below knees, nearly vertical for good lockout)
        val ankleY = kneeY + 0.20f
        landmarks[PoseResult.LEFT_ANKLE] = PoseKeypoint(hipX - 0.03f, ankleY, 0f, 1f)
        landmarks[PoseResult.RIGHT_ANKLE] = PoseKeypoint(hipX + 0.03f, ankleY, 0f, 1f)

        // Nose (above shoulders)
        landmarks[PoseResult.NOSE] = PoseKeypoint(shoulderX, shoulderY - 0.10f, 0f, 1f)

        return landmarks
    }
}
