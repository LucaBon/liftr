package com.liftr.domain.calculator

import com.liftr.domain.model.PoseResult
import com.liftr.domain.model.PoseResultTestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CameraViewClassifierTest {

    private fun pose(shoulderWidthRatio: Float, width: Int = 1080, height: Int = 1920) =
        PoseResultTestFixtures.createIdealFrontalPose(width, height, shoulderWidthRatio = shoulderWidthRatio)

    private fun ratios(pose: PoseResult, count: Int = 10) =
        List(count) { CameraViewClassifier.shoulderWidthRatio(pose)!! }

    @Test
    fun `athlete facing the camera is a front view`() {
        assertEquals(CameraView.FRONT, CameraViewClassifier.classify(ratios(pose(0.23f))))
    }

    @Test
    fun `athlete side-on is a side view`() {
        assertEquals(CameraView.SIDE, CameraViewClassifier.classify(ratios(pose(0.03f))))
    }

    @Test
    fun `athlete turned 45 degrees is a front view`() {
        assertEquals(CameraView.FRONT, CameraViewClassifier.classify(ratios(pose(0.16f))))
    }

    @Test
    fun `ratio does not depend on frame orientation`() {
        val portrait = CameraViewClassifier.shoulderWidthRatio(pose(0.23f, 1080, 1920))!!
        val landscape = CameraViewClassifier.shoulderWidthRatio(pose(0.23f, 1920, 1080))!!

        assertEquals(portrait, landscape, 0.001f)
    }

    @Test
    fun `hidden far shoulder still counts`() {
        val side = pose(0.03f)
        val hidden = side.copy(
            landmarks = side.landmarks.mapIndexed { i, lm ->
                if (i == PoseResult.RIGHT_SHOULDER) lm.copy(visibility = 0.1f) else lm
            }
        )

        assertNotNull(CameraViewClassifier.shoulderWidthRatio(hidden))
    }

    @Test
    fun `no ankles in frame cannot be measured`() {
        val cropped = pose(0.23f).let { p ->
            p.copy(landmarks = p.landmarks.mapIndexed { i, lm ->
                if (i == PoseResult.LEFT_ANKLE || i == PoseResult.RIGHT_ANKLE) lm.copy(visibility = 0.1f) else lm
            })
        }

        assertNull(CameraViewClassifier.shoulderWidthRatio(cropped))
    }

    @Test
    fun `too few samples is unknown`() {
        assertEquals(CameraView.UNKNOWN, CameraViewClassifier.classify(ratios(pose(0.23f), count = 3)))
    }

    @Test
    fun `brief turns toward the camera do not change a side view`() {
        val samples = ratios(pose(0.03f), count = 7) + ratios(pose(0.23f), count = 3)

        assertEquals(CameraView.SIDE, CameraViewClassifier.classify(samples))
    }
}
