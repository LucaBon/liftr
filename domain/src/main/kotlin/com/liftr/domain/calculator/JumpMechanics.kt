package com.liftr.domain.calculator

import com.liftr.domain.exercise.ExerciseThresholds
import com.liftr.domain.model.JumpExcursion
import kotlin.math.sqrt

/**
 * Mean push-off mechanics of one jump, by the simple method of Samozino et al.
 * (2008), "A simple method for measuring force, velocity and power output
 * during squat jump", J Biomech 41(14):2940-2945:
 *
 * - F̄ = m·g·(h/hPO + 1)
 * - v̄ = √(g·h/2)
 * - P̄ = F̄·v̄
 *
 * with h the jump height from flight time, hPO the push-off distance (center
 * of mass travel from the lowest point of the dip to takeoff) and m the system
 * mass. It needs only those three and was validated against force plates on
 * squat jumps with loads; unlike Sayers' peak power it rests on Newton's laws
 * rather than a regression, so loaded jumps are no extrapolation. It assumes
 * a push-off from a still start, so on a countermovement jump it reports the
 * mean over the upward push only.
 *
 * The method usually takes hPO from a one-off measurement of leg length and
 * squat depth. Here it is measured on every jump from the center-of-mass path,
 * so a shallower or deeper dip changes the force as it should.
 *
 * @property pushOffM Push-off distance hPO, in meters
 * @property meanForceN Mean force over the push-off, in newtons
 * @property meanVelocity Mean center-of-mass velocity over the push-off, in m/s
 * @property meanPowerW Mean power over the push-off, in watts
 * @property systemMassKg Body mass plus any external load, in kilograms
 */
data class JumpMechanics(
    val pushOffM: Float,
    val meanForceN: Float,
    val meanVelocity: Float,
    val meanPowerW: Float,
    val systemMassKg: Float
) {
    /** Mean force per kilogram of system mass, in N/kg. */
    val relativeForce: Float get() = meanForceN / systemMassKg

    /** Mean power per kilogram of system mass, in W/kg. */
    val relativePower: Float get() = meanPowerW / systemMassKg

    companion object {
        private const val G = 9.81f

        /**
         * Push-off mechanics of a jump [jumpHeightM] high, pushed off over
         * [pushOffM], by a system of [systemMassKg]. Null when any input is
         * missing or not positive, or when the push-off is outside
         * [ExerciseThresholds.Jump.MIN_PUSH_OFF_M]..[ExerciseThresholds.Jump.MAX_PUSH_OFF_M]:
         * no number beats a wrong one.
         */
        fun of(jumpHeightM: Float, pushOffM: Float, systemMassKg: Float): JumpMechanics? {
            if (jumpHeightM <= 0f || systemMassKg <= 0f) return null
            if (pushOffM !in ExerciseThresholds.Jump.MIN_PUSH_OFF_M..ExerciseThresholds.Jump.MAX_PUSH_OFF_M) return null
            val force = systemMassKg * G * (jumpHeightM / pushOffM + 1f)
            val velocity = sqrt(G * jumpHeightM / 2f)
            return JumpMechanics(pushOffM, force, velocity, force * velocity, systemMassKg)
        }

        /**
         * Push-off distance, in cm: from the lowest point of the dip to takeoff.
         * The dip is [JumpExcursion.countermovementCm] below standing; takeoff
         * is [JumpExcursion.peakRiseCm] − [JumpExcursion.flightRiseCm] above it
         * (the top of the flight above standing, less the rise from takeoff to
         * that top). Null when the rise from takeoff was not measured.
         */
        fun pushOffCm(excursion: JumpExcursion): Float? {
            val flightRise = excursion.flightRiseCm ?: return null
            return excursion.countermovementCm + excursion.peakRiseCm - flightRise
        }
    }
}
