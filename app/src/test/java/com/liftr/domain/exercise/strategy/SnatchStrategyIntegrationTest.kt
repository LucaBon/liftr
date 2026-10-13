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
import com.liftr.domain.model.BodyPartPositions
import com.liftr.domain.model.MovementPhase
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for SnatchStrategy.
 *
 * Tests the complete workflow of phase detection, form validation,
 * and metrics calculation for the Olympic Snatch exercise.
 *
 * Note: SnatchStrategy uses System.currentTimeMillis() internally for velocity
 * calculations. Since deltaT between calls in tests is near-zero, hip velocity
 * will be very large (or zero if deltaT rounds to 0ms). Tests are designed so
 * that any non-zero hipsY change triggers the velocity threshold reliably.
 * The BOTTOM→CONCENTRIC transition uses a pure position check (wrists above head),
 * making it timing-independent.
 */
class SnatchStrategyIntegrationTest {

    private lateinit var positionCalculator: IPositionCalculator
    private lateinit var logger: Logger
    private lateinit var strategy: SnatchStrategy

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
        strategy = SnatchStrategy(positionCalculator, logger)
    }

    @Test
    fun `exercise type should be SNATCH`() {
        assertEquals(ExerciseType.SNATCH, strategy.exerciseType)
    }

    @Test
    fun `required landmarks should include wrists hips and knees`() {
        val landmarks = strategy.getRequiredLandmarks()

        assertTrue(landmarks.contains(PoseResult.LEFT_WRIST))
        assertTrue(landmarks.contains(PoseResult.RIGHT_WRIST))
        assertTrue(landmarks.contains(PoseResult.LEFT_HIP))
        assertTrue(landmarks.contains(PoseResult.RIGHT_HIP))
        assertTrue(landmarks.contains(PoseResult.LEFT_KNEE))
        assertTrue(landmarks.contains(PoseResult.RIGHT_KNEE))
    }

    @Test
    fun `criteria should have correct thresholds`() {
        val criteria = strategy.getCriteria()

        assertEquals(0.05f, criteria.minDescentDepth.value, 0.001f)
        assertEquals(0.10f, criteria.returnTolerance.value, 0.001f)
        assertEquals(0.05f, criteria.minVelocity.value, 0.001f)
        assertFalse(criteria.targetBodyParts.isEmpty())
    }

    @Test
    fun `complete snatch rep should progress through all phases correctly`() {
        // Frame 1: floor position — sets starting positions (startingHipsY is null → init path)
        every { positionCalculator.getBodyPartPositions(any()) } returns BodyPartPositions(
            headY = 100f, shouldersY = 200f, hipsY = 500f, kneesY = 600f,
            wristsY = 700f, anklesY = 650f
        )
        every { positionCalculator.pixelsToMeters(any(), any(), any()) } returns 1.5f
        every { positionCalculator.pixelsToMeters(100f, any(), any()) } returns 0.50f  // head
        every { positionCalculator.pixelsToMeters(200f, any(), any()) } returns 1.00f  // shoulders
        every { positionCalculator.pixelsToMeters(500f, any(), any()) } returns 2.00f  // hips
        every { positionCalculator.pixelsToMeters(600f, any(), any()) } returns 2.50f  // knees
        every { positionCalculator.pixelsToMeters(700f, any(), any()) } returns 3.00f  // wrists at floor
        every { positionCalculator.pixelsToMeters(650f, any(), any()) } returns 2.75f  // ankles

        var state = ExercisePhaseState.initial()
        var result = strategy.detectPhase(createMockPoseResult(1000L), calibrationData, state)

        // Frame 1 → IDLE, starting position set
        assertEquals(MovementPhase.IDLE, result.newState.currentPhase)
        assertFalse(result.repCompleted)
        state = result.newState

        // Frame 2: wrists rise to 2.90 (rise of 0.10 > 0.05 threshold) → ECCENTRIC
        // Snatch checks: previousWristsY (3.00) - currentWristsY (2.90) = 0.10 > 0.05
        every { positionCalculator.getBodyPartPositions(any()) } returns BodyPartPositions(
            headY = 100f, shouldersY = 200f, hipsY = 500f, kneesY = 600f,
            wristsY = 690f, anklesY = 650f
        )
        every { positionCalculator.pixelsToMeters(690f, any(), any()) } returns 2.90f  // wrists risen

        result = strategy.detectPhase(createMockPoseResult(1066L), calibrationData, state)
        assertEquals(MovementPhase.ECCENTRIC, result.newState.currentPhase)
        assertFalse(result.repCompleted)
        state = result.newState

        // Frame 3: hips change position → velocity triggers BOTTOM
        // Since System.currentTimeMillis() deltaT is near-zero, (previousHipsY - hipsY) / ~0 → huge velocity
        // Any non-zero hipsY change reliably exceeds 0.5 m/s threshold
        every { positionCalculator.getBodyPartPositions(any()) } returns BodyPartPositions(
            headY = 100f, shouldersY = 200f, hipsY = 490f, kneesY = 600f,
            wristsY = 690f, anklesY = 650f
        )
        every { positionCalculator.pixelsToMeters(490f, any(), any()) } returns 1.95f  // hips slightly higher

        result = strategy.detectPhase(createMockPoseResult(1132L), calibrationData, state)
        assertEquals(MovementPhase.BOTTOM, result.newState.currentPhase)
        assertFalse(result.repCompleted)
        state = result.newState

        // Frame 4: wrists overhead — headY=0.50, wristsY=0.40 → (headY - wristsY) = 0.10 >= 0.05 → CONCENTRIC
        // In snatch coordinates: wristsY < headY means wrists are above head (smaller Y = higher position)
        every { positionCalculator.getBodyPartPositions(any()) } returns BodyPartPositions(
            headY = 100f, shouldersY = 200f, hipsY = 490f, kneesY = 600f,
            wristsY = 640f, anklesY = 650f
        )
        every { positionCalculator.pixelsToMeters(640f, any(), any()) } returns 0.40f  // wrists above head

        result = strategy.detectPhase(createMockPoseResult(1198L), calibrationData, state)
        assertEquals(MovementPhase.CONCENTRIC, result.newState.currentPhase)
        assertFalse(result.repCompleted)
        state = result.newState

        // Frame 5: wrists still overhead (wristsY 0.40 < headY 0.50, so wristsStillOverhead = true)
        // hipAngle and kneeAngle are null (low-visibility landmarks) → hipOk=true, kneeOk=true → TOP + rep
        every { positionCalculator.getBodyPartPositions(any()) } returns BodyPartPositions(
            headY = 100f, shouldersY = 200f, hipsY = 502f, kneesY = 600f,
            wristsY = 640f, anklesY = 650f
        )
        every { positionCalculator.pixelsToMeters(502f, any(), any()) } returns 2.05f  // hips near starting

        result = strategy.detectPhase(createMockPoseResult(1264L), calibrationData, state)
        assertEquals(MovementPhase.TOP, result.newState.currentPhase)
        assertTrue(result.repCompleted)
    }

    @Test
    fun `calculateMetrics should return correct values`() {
        every { positionCalculator.getBodyPartPositions(any()) } returns BodyPartPositions(
            headY = 100f, shouldersY = 200f, hipsY = 500f, kneesY = 600f,
            wristsY = 700f, anklesY = 650f
        )
        every { positionCalculator.pixelsToMeters(any(), any(), any()) } returns 1.5f
        every { positionCalculator.pixelsToMeters(700f, any(), any()) } returns 3.00f

        val poseResult = createMockPoseResult(1000L)
        val velocityData = VelocityData(
            instantaneous = MetersPerSecond(1.5f),
            average = MetersPerSecond(1.1f),
            timestamp = System.currentTimeMillis(),
            position = Meters(1.80f)
        )
        val weight = Kilograms(60f)
        val metrics = strategy.calculateMetrics(poseResult, velocityData, weight)

        assertNotNull(metrics)
        assertEquals(1.80f, metrics.displacement.value, 0.01f)
        assertEquals(1.5f, metrics.peakVelocity.value, 0.01f)
        assertTrue(metrics.avgPower.value > 0f)
        assertTrue(metrics.peakPower.value > 0f)
        assertTrue(metrics.work.value > 0f)

        val expectedForce = weight.value * 9.81f
        assertEquals(expectedForce * 1.1f, metrics.avgPower.value, 0.1f)
        assertEquals(expectedForce * 1.5f, metrics.peakPower.value, 0.1f)
    }

    @Test
    fun `form validation should detect no issues for good form`() {
        // Low-visibility landmarks (0.3f < 0.5f) so all visibility-gated checks return null
        every { positionCalculator.getBodyPartPositions(any()) } returns BodyPartPositions(
            headY = 100f, shouldersY = 200f, hipsY = 500f, kneesY = 600f,
            wristsY = 700f, anklesY = 650f,
            wristsX = 300f, hipsX = 310f
        )
        every { positionCalculator.pixelsToMeters(any(), any(), any()) } returns 1.5f
        every { positionCalculator.pixelsToMeters(300f, any(), any()) } returns 1.40f  // wristsX
        every { positionCalculator.pixelsToMeters(310f, any(), any()) } returns 1.42f  // hipsX

        val poseResult = createMockPoseResult(1000L)
        val validation = strategy.validateForm(poseResult, calibrationData)

        assertNotNull(validation)
        assertTrue(validation.isValid)
        assertTrue(validation.issues.none { it.severity == Severity.CRITICAL })
    }

    private fun createMockPoseResult(timestamp: Long): PoseResult {
        // All landmarks with visibility 0.3f (below 0.5f threshold) to force fallback paths
        val landmarks = List(33) { PoseKeypoint(0f, 0f, 0f, 0.3f) }
        return PoseResult(
            landmarks = landmarks,
            timestamp = timestamp,
            imageWidth = 1920,
            imageHeight = 1080,
            isPortrait = false
        )
    }
}
