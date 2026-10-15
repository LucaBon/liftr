package com.liftr.domain.exercise

import com.liftr.domain.model.BarPathPoint
import com.liftr.domain.model.MovementPhase
import com.liftr.domain.value.MetersPerSecond
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private fun jump(window: JumpPathWindow, cmPerUnitY: Float?, standStillUntilMs: Long = 1000, glitchAtMs: Long? = null) {
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
            window.onFrame(pointAt(t, y + if (t == glitchAtMs) 0.05f else 0f), phase, t, cmPerUnitY)
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

    /** Stands at y=0.5, takes off at 1000 ms with [v0] m/s and flies under gravity, 200 cm per unit of y. */
    private fun ballisticJump(window: JumpPathWindow, v0: Double, jitter: Float = 0f): Long {
        val flightMs = (2 * v0 / 9.81 * 1000).toLong()
        var t = 0L
        var i = 0
        while (t <= 1000 + flightMs) {
            val (y, phase) = if (t < 1000) {
                0.5f to MovementPhase.IDLE
            } else {
                val tau = (t - 1000) / 1000.0
                val riseM = v0 * tau - 9.81 * tau * tau / 2
                val noise = if (i++ % 2 == 0) jitter else -jitter
                0.5f - (riseM * 100 / 200).toFloat() + noise to MovementPhase.TOP
            }
            window.onFrame(pointAt(t, y), phase, t, 200f)
            t += 17
        }
        return t - 17
    }

    @Test
    fun `takeoff velocity comes from the flight parabola`() {
        val window = JumpPathWindow()
        val landingMs = ballisticJump(window, v0 = 2.5)

        val excursion = window.pathForLanding(nowMs = landingMs, flightMs = (landingMs - 1000).toFloat()).excursion!!

        assertEquals(2.5f, excursion.takeoffVelocity!!, 0.02f)
        assertEquals(31.9f, excursion.velocityHeightCm!!, 0.5f)
    }

    @Test
    fun `landmark jitter barely moves the takeoff velocity`() {
        val window = JumpPathWindow()
        // ±1 cm of alternating jitter on every frame.
        val landingMs = ballisticJump(window, v0 = 2.5, jitter = 0.005f)

        val excursion = window.pathForLanding(nowMs = landingMs, flightMs = (landingMs - 1000).toFloat()).excursion!!

        assertEquals(2.5f, excursion.takeoffVelocity!!, 0.08f)
    }

    @Test
    fun `no excursion when the athlete was never still`() {
        val window = JumpPathWindow()
        jump(window, cmPerUnitY = 200f, standStillUntilMs = 0)

        assertNull(window.pathForLanding(nowMs = 2500, flightMs = 500f).excursion)
    }

    @Test
    fun `a free-fall flight passes the fit check`() {
        val window = JumpPathWindow()
        val landingMs = ballisticJump(window, v0 = 2.5)

        val check = window.pathForLanding(landingMs, (landingMs - 1000).toFloat()).excursion!!.fitCheck!!

        assertEquals(9.81f, check.gravityEstimate, 0.05f)
        assertTrue(check.passed)
    }

    @Test
    fun `one mistracked frame in the dip does not deepen it`() {
        val window = JumpPathWindow()
        jump(window, cmPerUnitY = 200f, glitchAtMs = 1500)

        val excursion = window.pathForLanding(nowMs = 2500, flightMs = 500f).excursion!!

        assertEquals(10f, excursion.countermovementCm, 0.3f)
    }

    /**
     * Stands at y=0.5, takes off at 1000 ms with 2.5 m/s and lands [dropM]
     * lower than it took off (on bent legs), 200 cm per unit of y, 60 fps.
     * Returns the touchdown time.
     */
    private fun jumpLandingLower(window: JumpPathWindow, dropM: Double): Long {
        val v0 = 2.5
        val landingMs = 1000 + ((v0 + kotlin.math.sqrt(v0 * v0 + 2 * 9.81 * dropM)) / 9.81 * 1000).toLong()
        var t = 0L
        while (t <= landingMs + 50) {
            val tau = ((minOf(t, landingMs) - 1000).coerceAtLeast(0)) / 1000.0
            val riseM = v0 * tau - 9.81 * tau * tau / 2
            val phase = if (t < 1000) MovementPhase.IDLE else MovementPhase.TOP
            window.onFrame(pointAt(t, 0.5f - (riseM * 100 / 200).toFloat()), phase, t, 200f)
            t += 17
        }
        return landingMs
    }

    @Test
    fun `landing on bent legs agrees with the longer fall`() {
        val window = JumpPathWindow()
        val landingMs = jumpLandingLower(window, dropM = 0.10)

        val check = window.pathForLanding(landingMs + 50, (landingMs - 1000).toFloat(), 1000, landingMs)
            .excursion!!.fitCheck!!

        assertEquals(10f, check.landingDropCm!!, 1f)
        assertEquals(10f, check.landingDropFromTimesCm!!, 1f)
        assertTrue(check.timingOk)
    }

    @Test
    fun `a landing timed two frames late fails the timing check`() {
        val window = JumpPathWindow()
        val landingMs = jumpLandingLower(window, dropM = 0.10)

        val check = window.pathForLanding(landingMs + 50, (landingMs - 1000).toFloat(), 1000, landingMs + 34)
            .excursion!!.fitCheck!!

        assertFalse(check.timingOk)
        assertFalse(check.passed)
    }

    @Test
    fun `without measured takeoff and landing timing is not checked`() {
        val window = JumpPathWindow()
        val landingMs = jumpLandingLower(window, dropM = 0.10)

        val check = window.pathForLanding(landingMs + 50, (landingMs - 1000).toFloat()).excursion!!.fitCheck!!

        assertNull(check.landingDropCm)
        assertTrue(check.timingOk)
    }
}
