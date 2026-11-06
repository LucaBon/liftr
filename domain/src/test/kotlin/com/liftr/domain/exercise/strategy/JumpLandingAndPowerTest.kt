package com.liftr.domain.exercise.strategy

import com.liftr.domain.exercise.ExerciseThresholds
import com.liftr.domain.exercise.Severity
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.AnthropometricData
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.JumpData
import com.liftr.domain.model.MovementPhase
import com.liftr.domain.model.PoseResult
import com.liftr.domain.model.PoseResultTestFixtures
import com.liftr.domain.usecase.AnalyzeRepUseCase
import com.liftr.domain.value.Kilograms
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jump peak power (Sayers, system mass) and the stiff-landing check. */
class JumpLandingAndPowerTest {

    private val userHeight = 1.75f
    private fun calibration(bodyMass: Float?) = CalibrationData(
        500f, userHeight, 0.45f, AnthropometricData.getDefaultRatios(userHeight, bodyMass)
    )

    private fun strategy(type: ExerciseType = ExerciseType.VERTICAL_JUMP) =
        JumpStrategy(type, mockk<Logger>(relaxed = true))

    // --- Power ---

    private fun sayers(heightCm: Float, massKg: Float) = 60.7f * heightCm + 45.3f * massKg - 2055f

    @Test
    fun `peak power for a known height and body mass matches the Sayers formula`() {
        val jump = JumpData.fromFlightTime(500f) // 30.66 cm

        val power = strategy().peakPower(jump, calibration(75f), Kilograms(0f))!!

        assertEquals(sayers(jump.height.value * 100f, 75f), power.value, 0.5f)
    }

    @Test
    fun `loaded jump adds the external load to the system mass`() {
        val jump = JumpData.fromFlightTime(400f)

        val power = strategy(ExerciseType.LOADED_JUMP).peakPower(jump, calibration(75f), Kilograms(20f))!!

        assertEquals(sayers(jump.height.value * 100f, 95f), power.value, 0.5f)
    }

    @Test
    fun `bodyweight jump ignores a load left over in the weight field`() {
        val jump = JumpData.fromFlightTime(500f)

        val power = strategy().peakPower(jump, calibration(75f), Kilograms(20f))!!

        assertEquals(sayers(jump.height.value * 100f, 75f), power.value, 0.5f)
    }

    @Test
    fun `missing body mass gives no power`() {
        val jump = JumpData.fromFlightTime(500f)

        assertNull(strategy().peakPower(jump, calibration(null), Kilograms(0f)))
        assertNull(strategy().peakPower(jump, CalibrationData(500f, userHeight, 0.45f), Kilograms(0f)))
        assertNull(strategy(ExerciseType.LOADED_JUMP).peakPower(jump, calibration(null), Kilograms(20f)))
    }

    // --- Landing ---

    private val basePose = PoseResultTestFixtures.createIdealFrontalPose(1080, 1920, userHeight)

    /**
     * A side-on pose whose near-side (left) knee is flexed by [flexionDeg]:
     * shin and thigh each lean [flexionDeg]/2 off vertical, the shin forward,
     * the thigh back. The right leg is hidden behind the body.
     */
    private fun legPose(timeUs: Long, flexionDeg: Double): PoseResult {
        val sx = basePose.aspectScaleX
        val half = Math.toRadians(flexionDeg / 2)
        val segment = 0.2f
        val ankleX = 0.5f; val ankleY = 0.9f
        // Offsets in image-height units, converted to normalized x.
        val kneeX = ankleX + (segment * kotlin.math.sin(half)).toFloat() / sx
        val kneeY = ankleY - (segment * kotlin.math.cos(half)).toFloat()
        val hipX = kneeX - (segment * kotlin.math.sin(half)).toFloat() / sx
        val hipY = kneeY - (segment * kotlin.math.cos(half)).toFloat()
        val landmarks = basePose.landmarks.mapIndexed { i, lm ->
            when (i) {
                PoseResult.LEFT_HIP -> lm.copy(x = hipX, y = hipY)
                PoseResult.LEFT_KNEE -> lm.copy(x = kneeX, y = kneeY)
                PoseResult.LEFT_ANKLE -> lm.copy(x = ankleX, y = ankleY)
                PoseResult.RIGHT_HIP, PoseResult.RIGHT_KNEE, PoseResult.RIGHT_ANKLE -> lm.copy(visibility = 0.1f)
                else -> lm
            }
        }
        return basePose.copy(landmarks = landmarks, timestamp = timeUs / 1000, timestampUs = timeUs)
    }

    /**
     * Rep frames at [fps]: the last 0.3 s of flight with the knees nearly
     * straight, then landing, the knees bending to [peakFlexionDeg] over
     * 150 ms, then 0.4 s standing back up with [laterFlexionDeg] (a squat
     * well after the landing, which must not count).
     */
    private fun landingFrames(fps: Int, peakFlexionDeg: Double, laterFlexionDeg: Double = 10.0): List<AnalyzeRepUseCase.RepFrame> {
        val periodUs = 1_000_000.0 / fps
        val frames = mutableListOf<AnalyzeRepUseCase.RepFrame>()
        var n = 0
        fun t() = Math.round(1_000_000 + n * periodUs)
        val flight = (0.3 * fps).toInt()
        repeat(flight) { frames += AnalyzeRepUseCase.RepFrame(legPose(t(), 8.0), MovementPhase.TOP); n++ }
        val landingUs = t()
        while (t() - landingUs < 700_000) {
            val sinceMs = (t() - landingUs) / 1000.0
            val flexion = when {
                sinceMs <= 150 -> 8.0 + (peakFlexionDeg - 8.0) * sinceMs / 150
                sinceMs <= 300 -> peakFlexionDeg + (10.0 - peakFlexionDeg) * (sinceMs - 150) / 150
                sinceMs >= 550 -> laterFlexionDeg
                else -> 10.0
            }
            frames += AnalyzeRepUseCase.RepFrame(legPose(t(), flexion), MovementPhase.IDLE); n++
        }
        return frames
    }

    private fun stiffLandings(frames: List<AnalyzeRepUseCase.RepFrame>) =
        strategy().validateRep(frames, calibration(75f), null).filter { it.kind == "stiff_landing" }

    @Test
    fun `soft landing is no fault at 30 and 240 fps`() {
        for (fps in listOf(30, 240)) {
            assertTrue("fps $fps", stiffLandings(landingFrames(fps, peakFlexionDeg = 70.0)).isEmpty())
        }
    }

    @Test
    fun `stiff landing is exactly one stiff_landing warning at 30 and 240 fps`() {
        for (fps in listOf(30, 240)) {
            val issues = stiffLandings(landingFrames(fps, peakFlexionDeg = 25.0))
            assertEquals("fps $fps", 1, issues.size)
            assertEquals(Severity.WARNING, issues.single().severity)
        }
    }

    @Test
    fun `deep knee bend well after the landing does not soften a stiff one`() {
        val frames = landingFrames(30, peakFlexionDeg = 25.0, laterFlexionDeg = 90.0)

        assertEquals(1, stiffLandings(frames).size)
    }

    @Test
    fun `rep without a flight has no landing to check`() {
        val frames = landingFrames(30, peakFlexionDeg = 25.0).filter { it.phase != MovementPhase.TOP }

        assertTrue(stiffLandings(frames).isEmpty())
    }

    @Test
    fun `stiff landing threshold sits between the test landings`() {
        val threshold = ExerciseThresholds.Jump.STIFF_LANDING_KNEE_FLEXION_DEG
        assertTrue(threshold > 25f && threshold < 70f)
    }
}
