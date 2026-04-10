package com.lifthelper.domain.exercise

import com.lifthelper.domain.model.BarPathData
import com.lifthelper.domain.model.BarPathPoint
import com.lifthelper.domain.model.DriftDirection
import com.lifthelper.domain.value.MetersPerSecond
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Stateless analyzer for bar path quality metrics.
 * All functions are pure — no side effects, no state.
 *
 * Follows the same pattern as [RepQualityScorer] and [VelocityLossTracker].
 */
object BarPathAnalyzer {

    private const val DRIFT_THRESHOLD_CM = 0.5f
    private const val MIN_POINTS = 3

    // Velocity color thresholds (m/s)
    private const val VELOCITY_FAST = 0.75f
    private const val VELOCITY_MODERATE = 0.50f
    private const val VELOCITY_SLOW = 0.30f

    // ARGB color values (no Android Color dependency)
    const val COLOR_GREEN = 0xCC4CAF50L   // >0.75 m/s — fast/explosive
    const val COLOR_YELLOW = 0xCCFFEB3BL  // 0.50-0.75 — moderate
    const val COLOR_ORANGE = 0xCCFF9800L  // 0.30-0.50 — slow
    const val COLOR_RED = 0xCCF44336L     // <0.30 — grinding

    /**
     * Analyzes a rep's bar path points and produces an analyzed [BarPathData].
     */
    fun analyze(repNumber: Int, points: List<BarPathPoint>): BarPathData {
        if (points.size < MIN_POINTS) {
            return BarPathData(
                repNumber = repNumber,
                points = points,
                maxDriftCm = 0f,
                linearityScore = 0,
                driftDirection = DriftDirection.NONE
            )
        }

        return BarPathData(
            repNumber = repNumber,
            points = points,
            maxDriftCm = calculateMaxDriftCm(points),
            linearityScore = calculateLinearityScore(points),
            driftDirection = calculateDriftDirection(points)
        )
    }

    /**
     * Maximum horizontal drift from starting X position in centimeters.
     * Uses real-world coordinates for physical measurement.
     */
    internal fun calculateMaxDriftCm(points: List<BarPathPoint>): Float {
        if (points.size < 2) return 0f
        val startX = points.first().realWorldXCm
        return points.maxOf { abs(it.realWorldXCm - startX) }
    }

    /**
     * Linearity score 0-100. Measures how close the path is to a vertical line.
     * Formula: 100 - (stddev(X) / rangeY * 500), clamped to [0, 100].
     */
    internal fun calculateLinearityScore(points: List<BarPathPoint>): Int {
        if (points.size < MIN_POINTS) return 0

        val xValues = points.map { it.realWorldXCm }
        val yValues = points.map { it.realWorldYCm }

        val meanX = xValues.average().toFloat()
        val stdDevX = sqrt(xValues.map { (it - meanX) * (it - meanX) }.average().toFloat())

        val rangeY = (yValues.max() - yValues.min()).coerceAtLeast(0.01f)

        val rawScore = 100f - (stdDevX / rangeY * 500f)
        return rawScore.coerceIn(0f, 100f).toInt()
    }

    /**
     * Determines the dominant drift direction.
     * Forward = positive X drift, Backward = negative X drift.
     * Uses a 0.5cm threshold to avoid noise.
     */
    internal fun calculateDriftDirection(points: List<BarPathPoint>): DriftDirection {
        if (points.size < MIN_POINTS) return DriftDirection.NONE

        val startX = points.first().realWorldXCm
        val avgDriftCm = points.map { it.realWorldXCm }.average().toFloat() - startX

        return when {
            avgDriftCm > DRIFT_THRESHOLD_CM -> DriftDirection.FORWARD
            avgDriftCm < -DRIFT_THRESHOLD_CM -> DriftDirection.BACKWARD
            else -> DriftDirection.NONE
        }
    }

    /**
     * Returns ARGB color Long for the given velocity, for velocity-colored bar path rendering.
     * No Android dependency — returns raw ARGB as Long.
     */
    fun velocityToColorArgb(velocity: MetersPerSecond): Long {
        val absVelocity = velocity.abs().value
        return when {
            absVelocity > VELOCITY_FAST -> COLOR_GREEN
            absVelocity > VELOCITY_MODERATE -> COLOR_YELLOW
            absVelocity > VELOCITY_SLOW -> COLOR_ORANGE
            else -> COLOR_RED
        }
    }
}
