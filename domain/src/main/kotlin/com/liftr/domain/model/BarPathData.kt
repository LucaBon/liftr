package com.liftr.domain.model

import com.liftr.domain.value.MetersPerSecond

/**
 * A single frame's bar position with velocity context.
 *
 * @property normalizedX X position in normalized screen coordinates (0.0-1.0)
 * @property normalizedY Y position in normalized screen coordinates (0.0-1.0)
 * @property realWorldXCm X position in centimeters (calibrated)
 * @property realWorldYCm Y position in centimeters (calibrated)
 * @property velocity Instantaneous velocity at this point
 * @property timestamp Frame timestamp in milliseconds
 */
data class BarPathPoint(
    val normalizedX: Float,
    val normalizedY: Float,
    val realWorldXCm: Float,
    val realWorldYCm: Float,
    val velocity: MetersPerSecond,
    val timestamp: Long
)

/**
 * Analyzed bar path for a single rep.
 *
 * @property repNumber 1-indexed rep number within the set
 * @property points All captured bar positions for this rep
 * @property maxDriftCm Maximum horizontal drift from the starting X position (cm)
 * @property linearityScore Path linearity score (0-100, 100 = perfectly vertical)
 * @property driftDirection Which direction the bar drifted on average
 */
data class BarPathData(
    val repNumber: Int,
    val points: List<BarPathPoint>,
    val maxDriftCm: Float,
    val linearityScore: Int,
    val driftDirection: DriftDirection
) {
    val isValid: Boolean get() = points.size >= 3
}

enum class DriftDirection(val label: String) {
    FORWARD("Forward"),
    BACKWARD("Backward"),
    NONE("Centered")
}
