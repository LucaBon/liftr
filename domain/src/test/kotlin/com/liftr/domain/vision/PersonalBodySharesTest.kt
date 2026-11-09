package com.liftr.domain.vision

import com.liftr.domain.model.AnthropometricData
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.CalibrationQuality
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/** With a body capture the partial-body scales use the person's own proportions. */
class PersonalBodySharesTest {

    private val height = 1.90f
    private val imageHeight = 1000
    private val calculator = BodyPositionCalculator(mockk(relaxed = true))

    private fun pose(vararg yByIds: Pair<Float, IntArray>): PoseResult {
        val lm = MutableList(33) { PoseKeypoint(0.5f, 0.5f, 0f, 0f) }
        yByIds.forEach { (y, ids) -> ids.forEach { lm[it] = PoseKeypoint(0.5f, y, 0f, 1f) } }
        return PoseResult(lm, 0L, 600, imageHeight, true)
    }

    private val head = intArrayOf(PoseResult.NOSE, PoseResult.LEFT_EAR, PoseResult.RIGHT_EAR)
    private val hips = intArrayOf(PoseResult.LEFT_HIP, PoseResult.RIGHT_HIP)
    private val ankles = intArrayOf(PoseResult.LEFT_ANKLE, PoseResult.RIGHT_ANKLE)

    private val torsoOnly = pose(0.20f to head, 0.60f to hips)
    private val legsOnly = pose(0.50f to hips, 0.90f to ankles)
    private val fullBody = pose(0.10f to head, 0.90f to ankles)

    private fun calibration(anthro: AnthropometricData?) =
        CalibrationData(pixelsPerMeter = 500f, userHeight = height, shoulderWidth = 0.45f, anthropometricData = anthro)

    /** Long-legged: head and neck 0.17, torso 0.27, thigh 0.27, shin 0.25. */
    private fun captured(quality: CalibrationQuality = CalibrationQuality.OPTIMAL) =
        AnthropometricData.getDefaultRatios(height).copy(
            headHeightRatio = 0.12f, neckHeightRatio = 0.05f, torsoHeightRatio = 0.27f,
            upperLegHeightRatio = 0.27f, lowerLegHeightRatio = 0.25f, captureQuality = quality
        )

    private fun ppm(p: PoseResult, c: CalibrationData) = calculator.calculatePixelsPerMeter(p, c).pixelsPerMeter

    @Test
    fun `head to hips uses the captured proportions`() {
        // 0.17 + 0.27 from the head top to the hips, less the 0.065 above the face.
        assertEquals(400f / 0.375f / height, ppm(torsoOnly, calibration(captured())), 0.5f)
    }

    @Test
    fun `hips to ankles uses the captured proportions`() {
        assertEquals(400f / 0.52f / height, ppm(legsOnly, calibration(captured())), 0.5f)
    }

    @Test
    fun `head to ankles is the same with or without a capture`() {
        val expected = 800f / 0.895f / height
        assertEquals(expected, ppm(fullBody, calibration(captured())), 0.5f)
        assertEquals(expected, ppm(fullBody, calibration(null)), 0.5f)
    }

    @Test
    fun `without a capture the average proportions apply`() {
        assertEquals(400f / 0.415f / height, ppm(torsoOnly, calibration(null)), 0.5f)
        assertEquals(400f / 0.48f / height, ppm(legsOnly, calibration(null)), 0.5f)
    }

    @Test
    fun `a capture that fell back to default ratios counts as none`() {
        val fallback = captured(CalibrationQuality.FALLBACK)
        assertEquals(400f / 0.415f / height, ppm(torsoOnly, calibration(fallback)), 0.5f)
    }

    @Test
    fun `implausible captured proportions are ignored`() {
        val broken = captured().copy(torsoHeightRatio = 0.60f, upperLegHeightRatio = 0.05f)
        assertEquals(400f / 0.415f / height, ppm(torsoOnly, calibration(broken)), 0.5f)
        assertEquals(400f / 0.48f / height, ppm(legsOnly, calibration(broken)), 0.5f)
    }
}
