package com.liftr.domain.vision

import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class BodyPositionCalculatorTest {

    private val calibration = CalibrationData(pixelsPerMeter = 500f, userHeight = 1.75f, shoulderWidth = 0.45f)
    private val imageHeight = 1280

    /**
     * Side-on squatter at [depth] (0 standing, 1 bottom): head and shoulders
     * drop 0.28 of the frame, hips 0.22, knees a little; the feet stay put.
     */
    private fun squatPose(depth: Float, timeMs: Long): PoseResult {
        val lm = MutableList(33) { PoseKeypoint(0.5f, 0.5f, 0f, 0f) }
        fun set(y: Float, vararg ids: Int) = ids.forEach { lm[it] = PoseKeypoint(0.5f, y, 0f, 1f) }
        set(0.20f + 0.28f * depth, PoseResult.NOSE, PoseResult.LEFT_EYE, PoseResult.RIGHT_EYE, PoseResult.LEFT_EAR, PoseResult.RIGHT_EAR)
        set(0.26f + 0.28f * depth, PoseResult.LEFT_SHOULDER, PoseResult.RIGHT_SHOULDER)
        set(0.45f + 0.22f * depth, PoseResult.LEFT_HIP, PoseResult.RIGHT_HIP)
        set(0.62f + 0.05f * depth, PoseResult.LEFT_KNEE, PoseResult.RIGHT_KNEE)
        set(0.80f, PoseResult.LEFT_ANKLE, PoseResult.RIGHT_ANKLE)
        return PoseResult(lm, timeMs, 720, imageHeight, true)
    }

    /** px/m the calculator is using, read back through a 1.0 normalized-y conversion. */
    private fun BodyPositionCalculator.scaleAt(pose: PoseResult) = imageHeight / pixelsToMeters(1f, pose, calibration)

    @Test
    fun `the locked scale stays constant through a squat`() {
        val calculator = BodyPositionCalculator(mockk(relaxed = true))
        // 1 s standing, then 2 s down and up, at 30 fps.
        val standing = (0 until 30).map { squatPose(0f, it * 33L) }
        val squat = (0 until 60).map { i ->
            val phase = i / 59f
            squatPose(if (phase < 0.5f) phase * 2 else (1 - phase) * 2, 1000L + i * 33L)
        }
        standing.forEach { calculator.scaleAt(it) }
        val standingScale = calculator.scaleAt(standing.last())
        // Head-to-ankle span 0.60 of 1280 px for 89% of 1.75 m.
        assertEquals(0.60f * imageHeight / (0.89f * 1.75f), standingScale, 1f)

        squat.forEach { assertEquals(standingScale, calculator.scaleAt(it), 1f) }
    }

    @Test
    fun `a squat's displacement converts with the standing scale`() {
        val calculator = BodyPositionCalculator(mockk(relaxed = true))
        (0 until 30).forEach { calculator.scaleAt(squatPose(0f, it * 33L)) }
        val bottom = squatPose(1f, 1500L)
        val top = squatPose(0f, 1533L)
        val shoulderDrop = calculator.pixelsToMeters(0.54f, bottom, calibration) - calculator.pixelsToMeters(0.26f, top, calibration)
        // 0.28 of the frame at the standing scale (0.60 of the frame = 89% of 1.75 m).
        assertEquals(0.28f / 0.60f * 0.89f * 1.75f, shoulderDrop, 0.01f)
    }

    @Test
    fun `reset forgets the locked scale`() {
        val calculator = BodyPositionCalculator(mockk(relaxed = true))
        (0 until 30).forEach { calculator.scaleAt(squatPose(0f, it * 33L)) }
        calculator.resetScale()
        // A smaller person (farther from the camera) after the reset: their own scale, at once.
        val far = squatPose(0f, 0L).let { p ->
            p.copy(landmarks = p.landmarks.map { if (it.visibility > 0f) it.copy(y = 0.5f + (it.y - 0.5f) / 2) else it })
        }
        assertEquals(0.30f * imageHeight / (0.89f * 1.75f), calculator.scaleAt(far), 1f)
    }

    @Test
    fun `a new session keeps its own scale`() {
        val shared = BodyPositionCalculator(mockk(relaxed = true))
        val session = shared.newSession()
        (0 until 30).forEach { session.pixelsToMeters(1f, squatPose(0f, it * 33L), calibration) }
        val locked = session.pixelsToMeters(1f, squatPose(0f, 1000L), calibration)
        // A live set resets the shared calculator and measures someone else.
        shared.resetScale()
        val far = squatPose(0f, 0L).let { p ->
            p.copy(landmarks = p.landmarks.map { if (it.visibility > 0f) it.copy(y = 0.5f + (it.y - 0.5f) / 2) else it })
        }
        (0 until 30).forEach { shared.pixelsToMeters(1f, far, calibration) }

        assertEquals(locked, session.pixelsToMeters(1f, squatPose(1f, 1033L), calibration), 1e-4f)
    }

    @Test
    fun `an upside-down pose is not flipped`() {
        val calculator = BodyPositionCalculator(mockk(relaxed = true))
        val upright = squatPose(0f, 0L)
        val inverted = upright.copy(landmarks = upright.landmarks.map { it.copy(y = 1f - it.y) })
        val positions = calculator.getBodyPartPositions(inverted)!!
        assertEquals(0.74f, positions.shouldersY!!, 1e-4f)
        assertEquals(0.20f, positions.anklesY!!, 1e-4f)
    }
}
