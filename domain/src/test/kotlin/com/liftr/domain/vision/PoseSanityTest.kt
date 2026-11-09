package com.liftr.domain.vision

import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseSanityTest {

    /** Head, shoulders, hips, knees and ankles at the given normalized y; everything else hidden. */
    private fun pose(head: Float?, shoulders: Float?, hips: Float?, knees: Float = 0.7f, ankles: Float = 0.9f): PoseResult {
        val lm = MutableList(33) { PoseKeypoint(0.5f, 0.5f, 0f, 0f) }
        fun set(y: Float?, vararg ids: Int) = ids.forEach { lm[it] = PoseKeypoint(0.5f, y ?: 0f, 0f, if (y == null) 0f else 1f) }
        set(head, PoseResult.NOSE, PoseResult.LEFT_EYE, PoseResult.RIGHT_EYE, PoseResult.LEFT_EAR, PoseResult.RIGHT_EAR)
        set(shoulders, PoseResult.LEFT_SHOULDER, PoseResult.RIGHT_SHOULDER)
        set(hips, PoseResult.LEFT_HIP, PoseResult.RIGHT_HIP)
        set(knees, PoseResult.LEFT_KNEE, PoseResult.RIGHT_KNEE)
        set(ankles, PoseResult.LEFT_ANKLE, PoseResult.RIGHT_ANKLE)
        return PoseResult(lm, 0L, 720, 1280, true)
    }

    @Test
    fun `a standing pose is plausible`() {
        assertTrue(PoseSanity.isPlausible(pose(head = 0.30f, shoulders = 0.36f, hips = 0.51f)))
    }

    @Test
    fun `a squat bottom with the torso still upright is plausible against the standing torso`() {
        assertTrue(PoseSanity.isPlausible(pose(head = 0.58f, shoulders = 0.63f, hips = 0.73f), standingTorso = 0.15f))
    }

    @Test
    fun `an upside-down skeleton is rejected`() {
        // As MediaPipe gave it under the front squat plate: nose low, ankles high.
        assertFalse(PoseSanity.isPlausible(pose(head = 0.65f, shoulders = 0.66f, hips = 0.56f, knees = 0.46f, ankles = 0.39f)))
    }

    @Test
    fun `a head below the shoulders is rejected`() {
        assertFalse(PoseSanity.isPlausible(pose(head = 0.68f, shoulders = 0.64f, hips = 0.70f)))
    }

    @Test
    fun `a skeleton folded onto the legs is rejected against the standing torso only`() {
        val folded = pose(head = 0.53f, shoulders = 0.556f, hips = 0.59f)
        assertTrue(PoseSanity.isPlausible(folded))
        assertFalse(PoseSanity.isPlausible(folded, standingTorso = 0.15f))
    }

    @Test
    fun `missing shoulders or hips is rejected, a hidden head is not`() {
        assertFalse(PoseSanity.isPlausible(pose(head = 0.30f, shoulders = null, hips = 0.51f)))
        assertFalse(PoseSanity.isPlausible(pose(head = 0.30f, shoulders = 0.36f, hips = null)))
        assertTrue(PoseSanity.isPlausible(pose(head = null, shoulders = 0.36f, hips = 0.51f)))
    }

    @Test
    fun `torso height is the shoulder to hip drop`() {
        assertEquals(0.15f, PoseSanity.torsoHeight(pose(head = 0.30f, shoulders = 0.36f, hips = 0.51f))!!, 1e-4f)
        assertNull(PoseSanity.torsoHeight(pose(head = 0.30f, shoulders = null, hips = 0.51f)))
    }
}
