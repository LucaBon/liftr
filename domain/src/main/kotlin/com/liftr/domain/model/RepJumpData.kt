package com.liftr.domain.model

import com.liftr.domain.calculator.JumpMechanics
import com.liftr.domain.exercise.BarPathAnalyzer
import com.liftr.domain.value.MetersPerSecond
import kotlinx.serialization.Serializable

/**
 * What one jump measured, kept with its rep so history and analytics can show
 * it after the set summary is gone.
 *
 * @property flightHeightCm Jump height from flight time
 * @property apexHeightCm Jump height from the time between takeoff and the top of the flight
 * @property comFlightRiseCm Center-of-mass rise from takeoff to the top of the flight
 * @property takeoffVelocity Upward center-of-mass velocity at takeoff, in m/s
 * @property velocityHeightCm Jump height from the takeoff velocity
 * @property countermovementCm Dip of the center of mass below standing before takeoff
 * @property peakRiseCm Top of the flight above standing
 * @property standingYCm Standing height, in the path's units
 * @property fitCheck Whether the center-of-mass flight looked like free fall
 * @property path Center-of-mass path from the dip to the landing
 * @property peakPowerW Peak power in watts (Sayers, from jump height and system
 *   mass); null without a body mass, and in rows saved before it was measured
 * @property pushOffCm Push-off distance: center-of-mass travel from the lowest
 *   point of the dip to takeoff
 * @property meanForceN Mean push-off force in newtons (Samozino, see [JumpMechanics])
 * @property meanVelocity Mean push-off velocity in m/s
 * @property meanPowerW Mean push-off power in watts
 * @property systemMassKg Body mass plus any load the mechanics were computed for
 *
 * The push-off fields are all null together: without a body mass, without a
 * measured dip and takeoff height, when the push-off was implausible, and in
 * rows saved before they were measured.
 */
@Serializable
data class RepJumpData(
    val flightHeightCm: Float,
    val apexHeightCm: Float? = null,
    val comFlightRiseCm: Float? = null,
    val takeoffVelocity: Float? = null,
    val velocityHeightCm: Float? = null,
    val countermovementCm: Float? = null,
    val peakRiseCm: Float? = null,
    val standingYCm: Float? = null,
    val fitCheck: JumpFitCheck? = null,
    val path: List<JumpPathSample> = emptyList(),
    val peakPowerW: Float? = null,
    val pushOffCm: Float? = null,
    val meanForceN: Float? = null,
    val meanVelocity: Float? = null,
    val meanPowerW: Float? = null,
    val systemMassKg: Float? = null
) {
    /** Mean push-off force per kilogram of system mass, in N/kg. */
    val relativeForce: Float? get() = systemMassKg?.let { m -> meanForceN?.div(m) }

    /** Mean push-off power per kilogram of system mass, in W/kg. */
    val relativePower: Float? get() = systemMassKg?.let { m -> meanPowerW?.div(m) }

    /** The path as [BarPathData], for the replay card. */
    fun toBarPathData(repNumber: Int): BarPathData {
        val excursion = if (countermovementCm != null && peakRiseCm != null && standingYCm != null) {
            JumpExcursion(
                countermovementCm, peakRiseCm, standingYCm, comFlightRiseCm, takeoffVelocity, velocityHeightCm, fitCheck
            )
        } else null
        val points = path.map {
            BarPathPoint(it.x, it.y, it.xCm, it.yCm, MetersPerSecond(it.velocity), it.t)
        }
        return BarPathAnalyzer.analyze(repNumber, points, excursion)
    }

    companion object {
        fun from(
            flightHeightCm: Float,
            apexHeightCm: Float?,
            barPath: BarPathData?,
            peakPowerW: Float? = null,
            mechanics: JumpMechanics? = null
        ): RepJumpData {
            val excursion = barPath?.excursion
            return RepJumpData(
                flightHeightCm = flightHeightCm,
                apexHeightCm = apexHeightCm,
                comFlightRiseCm = excursion?.flightRiseCm,
                takeoffVelocity = excursion?.takeoffVelocity,
                velocityHeightCm = excursion?.velocityHeightCm,
                countermovementCm = excursion?.countermovementCm,
                peakRiseCm = excursion?.peakRiseCm,
                standingYCm = excursion?.standingYCm,
                fitCheck = excursion?.fitCheck,
                path = barPath?.points.orEmpty().map {
                    JumpPathSample(it.timestamp, it.normalizedX, it.normalizedY, it.realWorldXCm, it.realWorldYCm, it.velocity.value)
                },
                peakPowerW = peakPowerW,
                pushOffCm = mechanics?.let { it.pushOffM * 100f },
                meanForceN = mechanics?.meanForceN,
                meanVelocity = mechanics?.meanVelocity,
                meanPowerW = mechanics?.meanPowerW,
                systemMassKg = mechanics?.systemMassKg
            )
        }
    }
}

/** One center-of-mass sample of a stored jump path. */
@Serializable
data class JumpPathSample(
    val t: Long,
    val x: Float,
    val y: Float,
    val xCm: Float,
    val yCm: Float,
    val velocity: Float
)
