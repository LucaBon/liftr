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
 * Integration tests for CleanStrategy.
 *
 * Tests the complete workflow of phase detection, form validation,
 * and metrics calculation for the Olympic Clean exercise.
 */
class CleanStrategyIntegrationTest {

    private lateinit var positionCalculator: IPositionCalculator
    private lateinit var logger: Logger
    private lateinit var strategy: CleanStrategy

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
        strategy = CleanStrategy(positionCalculator, logger)
    }

    @Test
    fun `exercise type should be CLEAN`() {
        assertEquals(ExerciseType.CLEAN, strategy.exerciseType)
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
    fun `complete clean rep should progress through all phases correctly`() {
        // Frame 1 (t=1000): floor position — sets starting positions
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

        // Frame 2 (t=1066): wrists rise to 2.90 (rise of 0.10 > 0.05 threshold) → ECCENTRIC
        every { positionCalculator.getBodyPartPositions(any()) } returns BodyPartPositions(
            headY = 100f, shouldersY = 200f, hipsY = 500f, kneesY = 600f,
            wristsY = 690f, anklesY = 650f
        )
        every { positionCalculator.pixelsToMeters(690f, any(), any()) } returns 2.90f  // wrists risen

        result = strategy.detectPhase(createMockPoseResult(1066L), calibrationData, state)
        assertEquals(MovementPhase.ECCENTRIC, result.newState.currentPhase)
        assertFalse(result.repCompleted)
        state = result.newState

        // Frame 3 (t=1132): hips rise explosively (hipsY 2.00 → 1.50, delta=0.50 in 66ms = 7.6 m/s > 0.5) → BOTTOM
        every { positionCalculator.getBodyPartPositions(any()) } returns BodyPartPositions(
            headY = 100f, shouldersY = 200f, hipsY = 480f, kneesY = 600f,
            wristsY = 690f, anklesY = 650f
        )
        every { positionCalculator.pixelsToMeters(480f, any(), any()) } returns 1.50f  // hips risen sharply

        result = strategy.detectPhase(createMockPoseResult(1132L), calibrationData, state)
        assertEquals(MovementPhase.BOTTOM, result.newState.currentPhase)
        assertFalse(result.repCompleted)
        state = result.newState

        // Frame 4 (t=1198): wrist velocity goes negative (still moving upward fast) → CONCENTRIC (catch)
        // previousWristsY = 2.90 (frame 2/3), current = 2.50 (still rising)
        // velocity = (2.50 - 2.90) / 0.066 = -6.06 m/s < -0.2 threshold
        every { positionCalculator.getBodyPartPositions(any()) } returns BodyPartPositions(
            headY = 100f, shouldersY = 200f, hipsY = 480f, kneesY = 600f,
            wristsY = 675f, anklesY = 650f
        )
        every { positionCalculator.pixelsToMeters(675f, any(), any()) } returns 2.50f  // wrists continuing up fast

        result = strategy.detectPhase(createMockPoseResult(1198L), calibrationData, state)
        assertEquals(MovementPhase.CONCENTRIC, result.newState.currentPhase)
        assertFalse(result.repCompleted)
        state = result.newState

        // Frame 5 (t=1264): recovery — hips return near starting position (fallback path, low-visibility landmarks)
        // startingHipsY = 2.00, currentHipsY = 2.05 → abs(2.05 - 2.00) = 0.05 <= 0.10 threshold
        every { positionCalculator.getBodyPartPositions(any()) } returns BodyPartPositions(
            headY = 100f, shouldersY = 200f, hipsY = 502f, kneesY = 600f,
            wristsY = 675f, anklesY = 650f
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
            instantaneous = MetersPerSecond(1.2f),
            average = MetersPerSecond(0.9f),
            timestamp = System.currentTimeMillis(),
            position = Meters(1.50f)
        )
        val weight = Kilograms(80f)
        val metrics = strategy.calculateMetrics(poseResult, velocityData, weight)

        assertNotNull(metrics)
        assertEquals(1.50f, metrics.displacement.value, 0.01f)
        assertEquals(1.2f, metrics.peakVelocity.value, 0.01f)
        assertTrue(metrics.avgPower.value > 0f)
        assertTrue(metrics.peakPower.value > 0f)
        assertTrue(metrics.work.value > 0f)

        val expectedForce = weight.value * 9.81f
        assertEquals(expectedForce * 0.9f, metrics.avgPower.value, 0.1f)
        assertEquals(expectedForce * 1.2f, metrics.peakPower.value, 0.1f)
    }

    @Test
    fun `form validation should detect no issues for good form`() {
        // Landmarks with low visibility (< 0.5) so form checks requiring high-vis landmarks return null
        every { positionCalculator.getBodyPartPositions(any()) } returns BodyPartPositions(
            headY = 100f, shouldersY = 200f, hipsY = 500f, kneesY = 600f,
            wristsY = 700f, anklesY = 650f,
            hipsX = 300f, anklesX = 310f
        )
        every { positionCalculator.pixelsToMeters(any(), any(), any()) } returns 1.5f
        every { positionCalculator.pixelsToMeters(300f, any(), any()) } returns 1.4f  // hipsX
        every { positionCalculator.pixelsToMeters(310f, any(), any()) } returns 1.42f // anklesX (close to hipsX)

        val poseResult = createMockPoseResult(1000L)
        val validation = strategy.validateForm(poseResult, calibrationData)

        assertNotNull(validation)
        // With low-visibility landmarks (0.3f < 0.5f threshold), angle-based checks return null
        // and the vertical-drop heuristic also requires visibility; result should be valid
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
