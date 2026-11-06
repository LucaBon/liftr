package com.liftr.domain.exercise.strategy

import com.liftr.domain.exercise.ExercisePhaseState
import com.liftr.domain.exercise.ExerciseStrategy
import com.liftr.domain.exercise.Severity
import com.liftr.domain.usecase.AnalyzeRepUseCase
import com.liftr.domain.model.AnthropometricData
import com.liftr.domain.model.BodyPartPositions
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.MovementPhase
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import com.liftr.domain.vision.IPositionCalculator
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Heel lift and torso angle checks shared by the squat variants. */
class SquatFormChecksTest {

    private lateinit var back: BackSquatStrategy
    private lateinit var front: FrontSquatStrategy

    private val calibration = CalibrationData(
        pixelsPerMeter = 500f,
        userHeight = 1.75f,
        shoulderWidth = 0.45f,
        anthropometricData = AnthropometricData.getDefaultRatios(1.75f, 75f),
        timestamp = 0L
    )

    @Before
    fun setup() {
        val positionCalculator = mockk<IPositionCalculator>()
        every { positionCalculator.getBodyPartPositions(any()) } returns BodyPartPositions(
            headY = 1f, shouldersY = 2f, hipsY = 3f, kneesY = 4f
        )
        every { positionCalculator.pixelsToMeters(any(), any(), any()) } returns 1f
        back = BackSquatStrategy(positionCalculator, mockk(relaxed = true))
        front = FrontSquatStrategy(positionCalculator, mockk(relaxed = true))
    }

    private fun pose(
        width: Int = 1920,
        height: Int = 1080,
        heelY: Float = 0.9f,
        heelVisibility: Float = 0.9f,
        shoulderX: Float = 0.5f
    ): PoseResult {
        val landmarks = MutableList(33) { PoseKeypoint(0f, 0f, 0f, 0f) }
        // Right side faces the camera: shoulder, hip, heel, toe.
        landmarks[PoseResult.RIGHT_SHOULDER] = PoseKeypoint(shoulderX, 0.3f, 0f, 0.9f)
        landmarks[PoseResult.RIGHT_HIP] = PoseKeypoint(0.5f, 0.6f, 0f, 0.9f)
        landmarks[PoseResult.RIGHT_HEEL] = PoseKeypoint(0.45f, heelY, 0f, heelVisibility)
        landmarks[PoseResult.RIGHT_FOOT_INDEX] = PoseKeypoint(0.5f, 0.9f, 0f, 0.9f)
        return PoseResult(landmarks, timestamp = 0L, imageWidth = width, imageHeight = height)
    }

    private fun rep(vararg heelYs: Float) =
        heelYs.mapIndexed { i, y ->
            AnalyzeRepUseCase.RepFrame(pose(heelY = y).copy(timestamp = i * 33L), MovementPhase.ECCENTRIC)
        }

    private fun heelIssues(strategy: ExerciseStrategy, frames: List<AnalyzeRepUseCase.RepFrame>) =
        strategy.validateRep(frames, calibration, ExercisePhaseState.initial()).filter { it.kind == "foot_stability" }

    @Test
    fun `heel lift fires once for a rep where the heel rises against the toe`() {
        val issues = heelIssues(back, rep(0.9f, 0.88f, 0.85f, 0.84f, 0.85f))
        assertEquals(1, issues.size)
        assertEquals(Severity.WARNING, issues.single().severity)
        assertEquals(1, heelIssues(front, rep(0.9f, 0.85f)).size)
    }

    @Test
    fun `flat footed rep does not flag heel lift even when heel visibility drops`() {
        assertTrue(heelIssues(back, rep(0.9f, 0.9f, 0.9f)).isEmpty())
        val lowVisibility = listOf(
            AnalyzeRepUseCase.RepFrame(pose(), MovementPhase.ECCENTRIC),
            AnalyzeRepUseCase.RepFrame(pose(heelY = 0.8f, heelVisibility = 0.1f), MovementPhase.BOTTOM)
        )
        assertTrue(heelIssues(back, lowVisibility).isEmpty())
    }

    @Test
    fun `heel lift is not judged without a readable foot`() {
        assertTrue(heelIssues(back, emptyList()).isEmpty())
        assertTrue(heelIssues(back, rep(0.85f)).isEmpty())
    }

    @Test
    fun `torso angle uses the aspect scaled x on a non square frame`() {
        // dx = 0.22 normalized, dy = 0.3. Unscaled that is 36 degrees (warning);
        // scaled by 16:9 it is 52 degrees from vertical (critical).
        val wide = back.validateForm(pose(shoulderX = 0.72f), calibration)
        val wideIssue = wide.issues.single { it.kind == "torso_angle" }
        assertEquals(Severity.CRITICAL, wideIssue.severity)

        val square = back.validateForm(pose(width = 1080, height = 1080, shoulderX = 0.72f), calibration)
        assertEquals(Severity.WARNING, square.issues.single { it.kind == "torso_angle" }.severity)
    }

    @Test
    fun `upright torso is not flagged and front squat uses stricter limits`() {
        assertTrue(back.validateForm(pose(shoulderX = 0.5f), calibration).issues.none { it.kind == "torso_angle" })
        // 0.11 * 1.78 = 0.196 over 0.3 is about 33 degrees: front warns, back stays silent.
        val lean = pose(shoulderX = 0.61f)
        assertTrue(back.validateForm(lean, calibration).issues.none { it.kind == "torso_angle" })
        assertTrue(front.validateForm(lean, calibration).issues.any { it.kind == "torso_uprightness" })
    }
}
