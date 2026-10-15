package com.liftr.domain.model

import com.liftr.domain.value.Meters
import com.liftr.domain.value.MetersPerSecond

/**
 * One measured jump.
 *
 * Height and takeoff velocity are derived from flight time alone (projectile
 * motion, takeoff and landing at the same height), which needs only *when* the
 * feet leave and touch the ground — not distances, so it is immune to the
 * per-frame scale drift of pixel-to-meter conversion.
 *
 * Folding the legs before landing makes the toes touch down later than the
 * flight's arc implies, stretching the flight time and the height from it.
 * [apexHeight] uses only the rising half (takeoff to the top of the flight),
 * which landing posture can't affect.
 *
 * @property flightTimeMs Time between toe-off and toe-contact
 * @property height Rise of the center of mass: h = g·t² / 8
 * @property takeoffVelocity Vertical velocity at toe-off: v = g·t / 2
 * @property apexHeight Rise from the time between toe-off and the top of the flight: h = g·t² / 2
 * @property takeoffAtMs When the toes left the floor, on the pose clock
 * @property landingAtMs When the toes touched down again, on the pose clock
 */
data class JumpData(
    val flightTimeMs: Float,
    val height: Meters,
    val takeoffVelocity: MetersPerSecond,
    val apexHeight: Meters? = null,
    val takeoffAtMs: Double? = null,
    val landingAtMs: Double? = null
) {
    companion object {
        private const val GRAVITY = 9.81f

        /**
         * @param flightTimeMs Time between the takeoff and landing crossings
         * @param timingLevel Height (m) above the ground at which those crossings
         *   were timed. Timing above the ground clips the start and end of the
         *   flight; for a ballistic path the true flight T satisfies
         *   T² = T'² + 8L/g, which this corrects for exactly.
         */
        /**
         * Height from the rise time: [riseTimeMs] from the takeoff crossing at
         * [timingLevel] (m) above the ground to the top of the flight, which
         * covers the remaining g·t²/2.
         */
        fun heightFromRiseTime(riseTimeMs: Float, timingLevel: Float = 0f): Meters {
            val t = riseTimeMs / 1000f
            return Meters(GRAVITY * t * t / 2f + timingLevel)
        }

        fun fromFlightTime(flightTimeMs: Float, timingLevel: Float = 0f): JumpData {
            val measured = flightTimeMs / 1000f
            val t = kotlin.math.sqrt(measured * measured + 8f * timingLevel / GRAVITY)
            return JumpData(
                flightTimeMs = t * 1000f,
                height = Meters(GRAVITY * t * t / 8f),
                takeoffVelocity = MetersPerSecond(GRAVITY * t / 2f)
            )
        }
    }
}
