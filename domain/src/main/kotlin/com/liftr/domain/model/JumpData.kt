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
 * @property contactTimeMs Ground contact before this jump: from the previous
 *   jump's touchdown to this takeoff, when the athlete rebounded straight into
 *   it (see below); null for the first jump, after resting, and for approach jumps
 *
 * ## Contact time and reactive strength
 *
 * In rebound (repeated, pogo) jumps the athlete takes off again without
 * settling. The ground contact is the time from one landing to the next
 * takeoff, timed by the same toe crossings as the flights, so both share their
 * timing level and its errors largely cancel in the ratio.
 *
 * Those crossings sit a timing level L above the floor, which clips both ends
 * of each flight by [timingLevelClipMs] and adds the same time to the contacts
 * either side: the toes are on their way down to the floor, or up from it, at
 * the crossing. The contact is shortened by the previous flight's clip at its
 * landing and this flight's clip at its takeoff, the time from takeoff to
 * takeoff stays as measured: contact plus flight add up to the jump's cycle.
 *
 * [reactiveStrengthIndex] (flight time over contact time) is the classic
 * rebound-jump RSI; [rsiHeight] (height over contact time) its m/s variant.
 * Jumps dropped from a box would time their contact the same way, from the
 * landing off the box; telling the box height is not done yet.
 */
data class JumpData(
    val flightTimeMs: Float,
    val height: Meters,
    val takeoffVelocity: MetersPerSecond,
    val apexHeight: Meters? = null,
    val takeoffAtMs: Double? = null,
    val landingAtMs: Double? = null,
    val contactTimeMs: Float? = null
) {
    /** Reactive strength index: flight time over the contact time before it; null without a contact. */
    val reactiveStrengthIndex: Float?
        get() = contactTimeMs?.takeIf { it > 0f }?.let { flightTimeMs / it }

    /** Jump height over the contact time before it, in m/s ("RSI (height)"); null without a contact. */
    val rsiHeight: MetersPerSecond?
        get() = contactTimeMs?.takeIf { it > 0f }?.let { MetersPerSecond(height.value * 1000f / it) }

    companion object {
        private const val GRAVITY = 9.81f

        /**
         * Time (ms) a toe crossing at [timingLevel] (m) above the floor lags the
         * real takeoff, and leads the real touchdown, for a flight whose
         * crossings are [crossingFlightMs] apart: half of what
         * [fromFlightTime] adds back, (T − T') / 2 with T² = T'² + 8L/g.
         */
        fun timingLevelClipMs(crossingFlightMs: Float, timingLevel: Float): Float {
            val measured = crossingFlightMs / 1000f
            val t = kotlin.math.sqrt(measured * measured + 8f * timingLevel / GRAVITY)
            return (t - measured) * 1000f / 2f
        }

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
