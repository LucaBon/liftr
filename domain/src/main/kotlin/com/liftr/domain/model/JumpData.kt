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
 * @property flightTimeMs Time between toe-off and toe-contact
 * @property height Rise of the center of mass: h = g·t² / 8
 * @property takeoffVelocity Vertical velocity at toe-off: v = g·t / 2
 */
data class JumpData(
    val flightTimeMs: Float,
    val height: Meters,
    val takeoffVelocity: MetersPerSecond
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
