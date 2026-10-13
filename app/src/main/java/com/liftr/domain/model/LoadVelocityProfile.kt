package com.liftr.domain.model

import com.liftr.domain.value.Kilograms
import com.liftr.domain.value.MetersPerSecond
import kotlin.math.pow

/**
 * A single (load, velocity) data point from a completed set.
 *
 * Used for building a load-velocity profile to estimate 1RM.
 * Based on Jidovtseff et al. (2011) — load-velocity profiles
 * estimate 1RM within 5% error.
 */
data class LoadVelocityPoint(
    val load: Kilograms,
    val meanVelocity: MetersPerSecond,
    val timestamp: Long
)

/**
 * Linear regression result for the load-velocity relationship.
 *
 * @property slope Rate of velocity change per kg (should be negative)
 * @property intercept Y-intercept (velocity at zero load)
 * @property rSquared Coefficient of determination (0-1)
 */
data class LinearRegressionResult(
    val slope: Double,
    val intercept: Double,
    val rSquared: Double
)

/**
 * Load-velocity profile for a specific exercise type.
 *
 * Collects historical (load, meanVelocity) data points and fits a linear
 * regression to estimate 1RM. The model: velocity = slope * load + intercept.
 * At 1RM, velocity equals [minVelocityAt1RM] (exercise-specific threshold).
 *
 * @property exerciseType The exercise this profile belongs to
 * @property dataPoints Historical load-velocity measurements
 * @property minVelocityAt1RM Minimum velocity at 1RM for this exercise (from literature)
 */
data class LoadVelocityProfile(
    val exerciseType: ExerciseType,
    val dataPoints: List<LoadVelocityPoint>,
    val minVelocityAt1RM: Float
) {
    /**
     * Estimates 1RM using linear regression on the load-velocity data.
     *
     * Requires at least 3 data points across different loads.
     * Returns null if data is insufficient, regression is invalid,
     * or R-squared is below the quality threshold.
     *
     * @param minRSquared Minimum R-squared to accept the estimate (default 0.80)
     * @return Estimated 1RM in kilograms, or null if unavailable
     */
    fun estimatedOneRepMax(minRSquared: Double = MIN_R_SQUARED): Kilograms? {
        if (dataPoints.size < MIN_DATA_POINTS) return null

        val regression = fitLinearRegression() ?: return null

        // Velocity should decrease with load (negative slope)
        if (regression.slope >= 0) return null

        // Reject poor fits
        if (regression.rSquared < minRSquared) return null

        // Solve: minVelocityAt1RM = slope * oneRM + intercept
        // oneRM = (minVelocityAt1RM - intercept) / slope
        val oneRM = (minVelocityAt1RM - regression.intercept) / regression.slope
        return if (oneRM > 0) Kilograms(oneRM.toFloat()) else null
    }

    /**
     * Returns the linear regression statistics, or null if insufficient data.
     */
    fun fitLinearRegression(): LinearRegressionResult? {
        val n = dataPoints.size
        if (n < MIN_DATA_POINTS) return null

        val sumX = dataPoints.sumOf { it.load.value.toDouble() }
        val sumY = dataPoints.sumOf { it.meanVelocity.value.toDouble() }
        val sumXY = dataPoints.sumOf {
            it.load.value.toDouble() * it.meanVelocity.value.toDouble()
        }
        val sumX2 = dataPoints.sumOf { it.load.value.toDouble().pow(2) }
        val sumY2 = dataPoints.sumOf { it.meanVelocity.value.toDouble().pow(2) }

        val denominator = n * sumX2 - sumX * sumX
        if (denominator == 0.0) return null

        val slope = (n * sumXY - sumX * sumY) / denominator
        val intercept = (sumY - slope * sumX) / n

        // R-squared calculation
        val meanY = sumY / n
        val ssTotal = sumY2 - n * meanY.pow(2)
        val ssResidual = dataPoints.sumOf { point ->
            val predicted = slope * point.load.value.toDouble() + intercept
            (point.meanVelocity.value.toDouble() - predicted).pow(2)
        }
        val rSquared = if (ssTotal > 0) 1.0 - (ssResidual / ssTotal) else 0.0

        return LinearRegressionResult(
            slope = slope,
            intercept = intercept,
            rSquared = rSquared.coerceIn(0.0, 1.0)
        )
    }

    companion object {
        const val MIN_DATA_POINTS = 3
        const val MIN_R_SQUARED = 0.80

        /**
         * Returns the minimum velocity at 1RM for a given exercise type.
         * Values from Gonzalez-Badillo & Sanchez-Medina literature.
         */
        fun getMinVelocityAt1RM(exerciseType: ExerciseType): Float = when (exerciseType) {
            ExerciseType.BACK_SQUAT -> 0.30f
            ExerciseType.FRONT_SQUAT -> 0.30f
            ExerciseType.BENCH_PRESS -> 0.17f
            ExerciseType.DEADLIFT -> 0.15f
            // Conservative defaults for exercises without established thresholds
            ExerciseType.CLEAN -> 0.30f
            ExerciseType.SNATCH -> 0.30f
            ExerciseType.VERTICAL_JUMP -> 0.30f
            ExerciseType.LOADED_JUMP -> 0.30f
        }
    }
}
