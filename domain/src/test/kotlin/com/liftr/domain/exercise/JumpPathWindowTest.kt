package com.liftr.domain.exercise

import com.liftr.domain.model.BarPathPoint
import com.liftr.domain.model.MovementPhase
import com.liftr.domain.value.MetersPerSecond
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpPathWindowTest {

    private fun point(t: Long) = BarPathPoint(0.5f, 0.5f, 0f, 0f, MetersPerSecond(0f), t)

    /** Feeds one point every 33 ms up to [landingMs], with [phaseAt] giving each frame's phase. */
    private fun feed(window: JumpPathWindow, landingMs: Long, phaseAt: (Long) -> MovementPhase) {
        var t = 0L
        while (t <= landingMs) {
            window.onFrame(point(t), phaseAt(t), t)
            t += 33
        }
    }

    @Test
    fun `jump without a dip starts the path shortly before takeoff`() {
        val window = JumpPathWindow()
        feed(window, landingMs = 2508) { t -> if (t >= 2000) MovementPhase.TOP else MovementPhase.IDLE }

        val path = window.pathForLanding(nowMs = 2508, flightMs = 508f).points

        assertTrue(path.first().timestamp in 1500L..1533L)
        assertEquals(2508L, path.last().timestamp)
    }

    @Test
    fun `jump with a dip starts the path at the dip`() {
        val window = JumpPathWindow()
        feed(window, landingMs = 2508) { t ->
            when {
                t >= 2000 -> MovementPhase.TOP
                t >= 1600 -> MovementPhase.ECCENTRIC
                else -> MovementPhase.IDLE
            }
        }

        val path = window.pathForLanding(nowMs = 2508, flightMs = 508f).points

        assertEquals(1617L, path.first().timestamp) // first frame at or after the dip start (1617)
    }

    @Test
    fun `a dip long before takeoff is not part of the jump`() {
        val window = JumpPathWindow()
        feed(window, landingMs = 2508) { t ->
            when {
                t >= 2000 -> MovementPhase.TOP
                t in 200..400 -> MovementPhase.ECCENTRIC
                else -> MovementPhase.IDLE
            }
        }

        val path = window.pathForLanding(nowMs = 2508, flightMs = 508f).points

        assertTrue(path.first().timestamp >= 1500L)
    }

    @Test
    fun `each jump gets only its own frames`() {
        val window = JumpPathWindow()
        feed(window, landingMs = 2508) { t -> if (t >= 2000) MovementPhase.TOP else MovementPhase.IDLE }
        window.pathForLanding(nowMs = 2508, flightMs = 508f).points

        var t = 2541L
        while (t <= 5508) {
            window.onFrame(point(t), if (t >= 5000) MovementPhase.TOP else MovementPhase.IDLE, t)
            t += 33
        }
        val second = window.pathForLanding(nowMs = 5508 - (5508 - 2541) % 33, flightMs = 500f).points

        assertTrue(second.all { it.timestamp > 2508L })
    }

    private fun pointAt(t: Long, y: Float) = BarPathPoint(0.5f, y, 0f, y * 300f, MetersPerSecond(0f), t)

    /** Stands at y=0.5, dips to 0.55, takes off at 2000 ms, peaks at 0.40, lands at 2500 ms. */
    private fun jump(window: JumpPathWindow, cmPerUnitY: Float?, standStillUntilMs: Long = 1000) {
        var t = 0L
        while (t <= 2500) {
            val (y, phase) = when {
                t < standStillUntilMs -> 0.5f to MovementPhase.IDLE
                t < 1000 -> (if ((t / 33) % 2 == 0L) 0.48f else 0.52f) to MovementPhase.IDLE
                t < 1500 -> 0.5f + 0.05f * (t - 1000) / 500f to MovementPhase.ECCENTRIC
                t < 2000 -> 0.55f - 0.07f * (t - 1500) / 500f to MovementPhase.CONCENTRIC
                else -> {
                    val u = (t - 2250) / 250f
                    0.40f + 0.08f * u * u to MovementPhase.TOP
                }
            }
            window.onFrame(pointAt(t, y), phase, t, cmPerUnitY)
            t += 10
        }
    }

    @Test
    fun `dip and peak are measured from standing`() {
        val window = JumpPathWindow()
        jump(window, cmPerUnitY = 200f)

        val excursion = window.pathForLanding(nowMs = 2500, flightMs = 500f).excursion!!

        assertEquals(10f, excursion.countermovementCm, 0.3f)
        assertEquals(20f, excursion.peakRiseCm, 0.3f)
        assertEquals(150f, excursion.standingYCm, 0.5f)
        // Takeoff at y=0.48, top of the flight at 0.40.
        assertEquals(16f, excursion.flightRiseCm!!, 0.3f)
    }

    @Test
    fun `without a body scale the calibrated coordinates are used`() {
        val window = JumpPathWindow()
        jump(window, cmPerUnitY = null)

        val excursion = window.pathForLanding(nowMs = 2500, flightMs = 500f).excursion!!

        assertEquals(15f, excursion.countermovementCm, 0.5f)
        assertEquals(30f, excursion.peakRiseCm, 0.5f)
    }

    @Test
    fun `no excursion when the athlete was never still`() {
        val window = JumpPathWindow()
        jump(window, cmPerUnitY = 200f, standStillUntilMs = 0)

        assertNull(window.pathForLanding(nowMs = 2500, flightMs = 500f).excursion)
    }
}
