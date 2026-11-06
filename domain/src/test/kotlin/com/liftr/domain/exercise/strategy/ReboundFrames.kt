package com.liftr.domain.exercise.strategy

import com.liftr.domain.exercise.ExerciseThresholds
import com.liftr.domain.model.PoseResult
import com.liftr.domain.model.PoseResultTestFixtures

/** Synthetic rebound jumps (a 1.75 m athlete) with known flights and ground contacts. */
object ReboundFrames {

    private const val USER_HEIGHT = 1.75f
    private val basePose = PoseResultTestFixtures.createIdealFrontalPose(1080, 1920, USER_HEIGHT)

    // The fixture's nose-to-toe span is 0.85 of the frame; in meters it is the
    // nose height of a 1.75 m athlete.
    private val normPerMeter = 0.85f / (ExerciseThresholds.Jump.NOSE_HEIGHT_RATIO * USER_HEIGHT)
    private val g = 9.81

    /** The whole body raised by [rise] and the hips (and everything above) lowered by [dip], in meters. */
    private fun pose(timeUs: Long, rise: Double, dip: Double): PoseResult {
        val landmarks = basePose.landmarks.mapIndexed { i, lm ->
            val upperBody = i <= PoseResult.RIGHT_HIP
            lm.copy(y = lm.y - (rise * normPerMeter).toFloat() + if (upperBody) (dip * normPerMeter).toFloat() else 0f)
        }
        return basePose.copy(landmarks = landmarks, timestamp = timeUs / 1000, timestampUs = timeUs)
    }

    /**
     * Standing 0.5 s, then ballistic flights of [flightsS] separated by
     * contacts of [contactsS] (one fewer), then standing 0.6 s, sampled at
     * [fps]. The first takeoff falls [phase] of a frame after a frame. During
     * each contact the hips sink by [contactDipM] and come back (half a sine).
     */
    fun rebounds(
        fps: Int,
        flightsS: List<Double>,
        contactsS: List<Double>,
        phase: Double = 0.4,
        contactDipM: Double = 0.03
    ): List<PoseResult> {
        require(contactsS.size == flightsS.size - 1)
        val periodUs = 1_000_000.0 / fps
        val takeoffs = mutableListOf(0.5 + phase * periodUs / 1e6)
        for (i in contactsS.indices) takeoffs += takeoffs[i] + flightsS[i] + contactsS[i]
        val endS = takeoffs.last() + flightsS.last() + 0.6

        fun bodyAt(s: Double): Pair<Double, Double> {
            for (i in takeoffs.indices) {
                val since = s - takeoffs[i]
                if (since > 0 && since < flightsS[i]) {
                    val v0 = g * flightsS[i] / 2
                    return (v0 * since - g * since * since / 2) to 0.0
                }
                val landedFor = since - flightsS[i]
                if (i < contactsS.size && landedFor >= 0 && landedFor < contactsS[i]) {
                    return 0.0 to contactDipM * kotlin.math.sin(Math.PI * landedFor / contactsS[i])
                }
            }
            return 0.0 to 0.0
        }

        val frames = mutableListOf<PoseResult>()
        var n = 0
        while (true) {
            val us = n * periodUs
            if (us / 1e6 >= endS) break
            val (rise, dip) = bodyAt(us / 1e6)
            frames += pose(Math.round(1_000_000 + us), rise, dip)
            n++
        }
        return frames
    }
}
