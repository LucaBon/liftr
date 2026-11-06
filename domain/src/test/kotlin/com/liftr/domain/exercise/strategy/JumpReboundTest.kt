package com.liftr.domain.exercise.strategy

import com.liftr.domain.exercise.ExercisePhaseState
import com.liftr.domain.exercise.ExerciseThresholds
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.AnthropometricData
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.JumpData
import com.liftr.domain.model.PoseResult
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ground contact time and RSI of rebound jumps, from synthetic poses whose
 * flights and contacts are known exactly.
 */
class JumpReboundTest {

    private val userHeight = 1.75f
    private val calibration = CalibrationData(
        500f, userHeight, 0.45f, AnthropometricData.getDefaultRatios(userHeight, 75f)
    )

    private fun rebounds(
        fps: Int,
        flightsS: List<Double>,
        contactsS: List<Double>,
        phase: Double = 0.4,
        contactDipM: Double = 0.03
    ) = ReboundFrames.rebounds(fps, flightsS, contactsS, phase, contactDipM)

    private fun run(frames: List<PoseResult>, type: ExerciseType = ExerciseType.VERTICAL_JUMP): List<JumpData> {
        val strategy = JumpStrategy(type, mockk<Logger>(relaxed = true))
        var state = ExercisePhaseState.initial()
        val jumps = mutableListOf<JumpData>()
        for (frame in frames) {
            val result = strategy.detectPhase(frame, calibration, state)
            state = result.newState
            if (result.repCompleted) jumps += result.jump!!
        }
        return jumps
    }

    private val flights = listOf(0.45, 0.42, 0.40, 0.38, 0.36, 0.40)
    private val contacts = listOf(0.25, 0.20, 0.30, 0.22, 0.18)

    /** Contact time within [toleranceMs] of truth, and RSI to match, for every rebound. */
    private fun assertRebounds(fps: Int, toleranceMs: Double, phase: Double) {
        val jumps = run(rebounds(fps, flights, contacts, phase))

        assertEquals("fps $fps phase $phase", flights.size, jumps.size)
        assertNull("first jump, fps $fps", jumps.first().contactTimeMs)
        assertNull(jumps.first().reactiveStrengthIndex)
        for (i in 1 until jumps.size) {
            val jump = jumps[i]
            val trueContactMs = contacts[i - 1] * 1000
            assertNotNull("jump $i fps $fps", jump.contactTimeMs)
            val contact = jump.contactTimeMs!!.toDouble()
            assertEquals("contact of jump $i at $fps fps, phase $phase", trueContactMs, contact, toleranceMs)
            assertEquals(jump.flightTimeMs / jump.contactTimeMs!!, jump.reactiveStrengthIndex!!, 1e-4f)
            // RSI against truth: flight and contact errors both within their tolerance.
            val trueRsi = flights[i] / contacts[i - 1]
            assertEquals("RSI of jump $i at $fps fps", trueRsi, jump.reactiveStrengthIndex!!.toDouble(), trueRsi * 0.06)
            assertEquals(
                jump.height.value * 1000f / jump.contactTimeMs!!, jump.rsiHeight!!.value, 1e-4f
            )
        }
    }

    @Test
    fun `rebound contact time at 30 fps is within a third of a frame`() {
        for (phase in listOf(0.1, 0.4, 0.7)) assertRebounds(30, 1000.0 / 30 / 3, phase)
    }

    @Test
    fun `rebound contact time at 60 fps is within a third of a frame`() {
        for (phase in listOf(0.1, 0.4, 0.7)) assertRebounds(60, 1000.0 / 60 / 3, phase)
    }

    @Test
    fun `rebound contact time at 240 fps is within 2 ms, which needs the timing level correction`() {
        // Uncorrected, the crossings would read each contact ~24 ms long.
        for (phase in listOf(0.1, 0.4, 0.7)) assertRebounds(240, 2.0, phase)
    }

    @Test
    fun `pogo contacts around 130 ms are timed within a frame at 30, 60 and 240 fps`() {
        val pogoFlights = listOf(0.30, 0.30, 0.28, 0.30, 0.29)
        val pogoContacts = listOf(0.12, 0.14, 0.13, 0.15)
        for (fps in listOf(30, 60, 240)) {
            val jumps = run(rebounds(fps, pogoFlights, pogoContacts, contactDipM = 0.02))

            assertEquals("fps $fps", pogoFlights.size, jumps.size)
            assertNull(jumps.first().contactTimeMs)
            for (i in 1 until jumps.size) {
                assertEquals(
                    "pogo contact $i at $fps fps", pogoContacts[i - 1] * 1000,
                    jumps[i].contactTimeMs!!.toDouble(), 1000.0 / fps
                )
            }
        }
    }

    @Test
    fun `resting longer than STILL_MS between jumps gives no contact time`() {
        val pauseS = ExerciseThresholds.Jump.STILL_MS / 1000.0 + 0.3
        val jumps = run(rebounds(60, listOf(0.45, 0.42, 0.40), listOf(0.2, pauseS), contactDipM = 0.0))

        assertEquals(3, jumps.size)
        assertNotNull(jumps[1].contactTimeMs)
        assertNull(jumps[2].contactTimeMs)
        assertNull(jumps[2].reactiveStrengthIndex)
    }

    @Test
    fun `repeated countermovement jumps keep their longer contact while moving`() {
        // 600 ms on the ground with a 20 cm dip: longer than STILL_MS, but never at rest.
        val jumps = run(rebounds(60, listOf(0.45, 0.45), listOf(0.6), contactDipM = 0.2))

        assertEquals(2, jumps.size)
        assertEquals(600.0, jumps[1].contactTimeMs!!.toDouble(), 1000.0 / 60 / 2)
    }

    @Test
    fun `contact longer than MAX_CONTACT_MS gives no contact time`() {
        val contactS = ExerciseThresholds.Jump.MAX_CONTACT_MS / 1000.0 + 0.2
        val jumps = run(rebounds(60, listOf(0.45, 0.45), listOf(contactS), contactDipM = 0.2))

        assertEquals(2, jumps.size)
        assertNull(jumps[1].contactTimeMs)
    }

    @Test
    fun `approach jumps have no contact time`() {
        val jumps = run(rebounds(60, listOf(0.5, 0.5, 0.5), listOf(0.3, 0.3)), ExerciseType.APPROACH_JUMP)

        assertTrue("approach jumps counted: ${jumps.size}", jumps.size >= 2)
        assertTrue(jumps.all { it.contactTimeMs == null && it.reactiveStrengthIndex == null })
    }

    @Test
    fun `timing level clip is half of what fromFlightTime restores`() {
        val clip = JumpData.timingLevelClipMs(400f, 0.025f)
        val restored = JumpData.fromFlightTime(400f, 0.025f).flightTimeMs

        assertEquals(restored - 400f, 2 * clip, 1e-3f)
    }
}
