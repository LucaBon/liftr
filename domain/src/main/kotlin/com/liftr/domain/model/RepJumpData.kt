package com.liftr.domain.model

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
 * @property countermovementCm Dip of the center of mass below standing before takeoff
 * @property peakRiseCm Top of the flight above standing
 * @property standingYCm Standing height, in the path's units
 * @property path Center-of-mass path from the dip to the landing
 */
@Serializable
data class RepJumpData(
    val flightHeightCm: Float,
    val apexHeightCm: Float? = null,
    val comFlightRiseCm: Float? = null,
    val countermovementCm: Float? = null,
    val peakRiseCm: Float? = null,
    val standingYCm: Float? = null,
    val path: List<JumpPathSample> = emptyList()
) {
    /** The path as [BarPathData], for the replay card. */
    fun toBarPathData(repNumber: Int): BarPathData {
        val excursion = if (countermovementCm != null && peakRiseCm != null && standingYCm != null) {
            JumpExcursion(countermovementCm, peakRiseCm, standingYCm, comFlightRiseCm)
        } else null
        val points = path.map {
            BarPathPoint(it.x, it.y, it.xCm, it.yCm, MetersPerSecond(it.velocity), it.t)
        }
        return BarPathAnalyzer.analyze(repNumber, points, excursion)
    }

    companion object {
        fun from(flightHeightCm: Float, apexHeightCm: Float?, barPath: BarPathData?): RepJumpData {
            val excursion = barPath?.excursion
            return RepJumpData(
                flightHeightCm = flightHeightCm,
                apexHeightCm = apexHeightCm,
                comFlightRiseCm = excursion?.flightRiseCm,
                countermovementCm = excursion?.countermovementCm,
                peakRiseCm = excursion?.peakRiseCm,
                standingYCm = excursion?.standingYCm,
                path = barPath?.points.orEmpty().map {
                    JumpPathSample(it.timestamp, it.normalizedX, it.normalizedY, it.realWorldXCm, it.realWorldYCm, it.velocity.value)
                }
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
