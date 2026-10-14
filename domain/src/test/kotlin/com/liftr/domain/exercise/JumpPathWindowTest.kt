package com.liftr.domain.exercise

import com.liftr.domain.model.BarPathPoint
import com.liftr.domain.model.MovementPhase
import com.liftr.domain.value.MetersPerSecond
import org.junit.Assert.assertEquals
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

        val path = window.pathForLanding(nowMs = 2508, flightMs = 508f)

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

        val path = window.pathForLanding(nowMs = 2508, flightMs = 508f)

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

        val path = window.pathForLanding(nowMs = 2508, flightMs = 508f)

        assertTrue(path.first().timestamp >= 1500L)
    }

    @Test
    fun `each jump gets only its own frames`() {
        val window = JumpPathWindow()
        feed(window, landingMs = 2508) { t -> if (t >= 2000) MovementPhase.TOP else MovementPhase.IDLE }
        window.pathForLanding(nowMs = 2508, flightMs = 508f)

        var t = 2541L
        while (t <= 5508) {
            window.onFrame(point(t), if (t >= 5000) MovementPhase.TOP else MovementPhase.IDLE, t)
            t += 33
        }
        val second = window.pathForLanding(nowMs = 5508 - (5508 - 2541) % 33, flightMs = 500f)

        assertTrue(second.all { it.timestamp > 2508L })
    }
}
