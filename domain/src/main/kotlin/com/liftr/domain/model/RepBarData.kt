package com.liftr.domain.model

import kotlinx.serialization.Serializable

/**
 * One frame of the bar series: the tracked barbell endcap, independent of
 * the pose-based tracking point.
 *
 * @property t Frame timestamp (ms)
 * @property x Endcap center, normalized by the image width
 * @property y Endcap center, normalized by the image height
 * @property positionMeters Vertical position, calibrated like the pose tracking point
 * @property velocity Smoothed bar speed (m/s, magnitude like the pose velocity);
 *   null when the run of consecutive endcap frames is too short to measure it
 * @property acceleration Rate of change of [velocity] (m/s²); null at the ends of a run
 * @property held The endcap was missed in this frame and its last position held
 */
@Serializable
data class BarSample(
    val t: Long,
    val x: Float,
    val y: Float,
    val positionMeters: Float,
    val velocity: Float? = null,
    val acceleration: Float? = null,
    val held: Boolean = false
)

/**
 * What the bar series measured over one rep, the rep's boundaries and phases
 * coming from the pose.
 *
 * @property meanConcentricVelocity Mean bar speed over the concentric phases (m/s);
 *   null when the bar series covers too little of them
 * @property peakConcentricVelocity Peak bar speed over the concentric phases (m/s); null likewise
 * @property concentricCoverage Share of the rep's concentric frames with a bar velocity (0..1)
 * @property samples Bar samples over the rep's active phases
 */
@Serializable
data class RepBarData(
    val meanConcentricVelocity: Float?,
    val peakConcentricVelocity: Float?,
    val concentricCoverage: Float,
    val samples: List<BarSample> = emptyList()
)
