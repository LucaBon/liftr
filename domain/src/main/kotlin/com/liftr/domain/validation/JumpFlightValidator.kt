package com.liftr.domain.validation

import com.liftr.domain.exercise.ExerciseThresholds.Jump
import com.liftr.domain.model.JumpFitCheck
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Checks that a center-of-mass flight is free fall.
 *
 * In the air z = z0 + v0·τ - g·τ²/2, so z + g·τ²/2 is a straight line. Fitting
 * it with a parabola instead leaves a quadratic coefficient c that should be
 * zero: c ≠ 0 means the flight fell with g - 2c, which a wrong scale (cm per
 * unit of image) or a systematic distortion of the center of mass produces.
 *
 * A small fit error is not enough: residuals must also be random. A hump
 * (e.g. while the arms swing) is a wrong mass model, a sudden step a joint
 * lost and picked up somewhere else. A step from one or two wrong frames can
 * be fixed by leaving them out (see [checkWithoutOutliers]).
 */
object JumpFlightValidator {

    private const val G = 9.81

    /**
     * [taus] are the times since takeoff in seconds, [heights] the center of
     * mass height in meters (any origin). Null with fewer than
     * [Jump.MIN_CHECK_SAMPLES] frames, too few to estimate a curvature.
     */
    fun check(taus: List<Double>, heights: List<Double>): JumpFitCheck? = evaluate(taus, heights)?.check

    /**
     * Like [check], but while the residuals show a step, leaves out the frame
     * farthest off the fit and fits again, up to [Jump.MAX_DROPPED_FRAMES]
     * frames: a joint mistracked for a frame or two. A step that outlasts
     * that is a lasting reassignment, so all frames are kept and the step
     * reported. Returns the check and the indices of the frames kept.
     */
    fun checkWithoutOutliers(taus: List<Double>, heights: List<Double>): Pair<JumpFitCheck, List<Int>>? {
        val all = taus.indices.toList()
        val first = evaluate(taus, heights) ?: return null
        var kept = all
        var current = first
        while (current.check.residualStep) {
            if (all.size - kept.size >= Jump.MAX_DROPPED_FRAMES) return first.check to all
            val worst = kept[current.residuals.indices.maxBy { abs(current.residuals[it]) }]
            val next = kept - worst
            current = evaluate(next.map { taus[it] }, next.map { heights[it] }) ?: return first.check to all
            kept = next
        }
        return current.check.copy(droppedFrames = all.size - kept.size) to kept
    }

    private class Evaluation(val check: JumpFitCheck, val residuals: List<Double>)

    private fun evaluate(taus: List<Double>, heights: List<Double>): Evaluation? {
        val n = taus.size
        if (n < Jump.MIN_CHECK_SAMPLES || heights.size != n) return null
        val mean = taus.average()
        val u = taus.map { it - mean }
        val y = heights.indices.map { heights[it] + G * taus[it] * taus[it] / 2 }

        val s = DoubleArray(5)
        val r = DoubleArray(3)
        for (i in 0 until n) {
            var p = 1.0
            for (k in 0..4) {
                s[k] += p
                if (k <= 2) r[k] += p * y[i]
                p *= u[i]
            }
        }
        val m = arrayOf(
            doubleArrayOf(s[0], s[1], s[2]),
            doubleArrayOf(s[1], s[2], s[3]),
            doubleArrayOf(s[2], s[3], s[4])
        )
        val det = det3(m)
        if (abs(det) < 1e-18) return null
        fun replaced(col: Int) = Array(3) { i -> DoubleArray(3) { j -> if (j == col) r[i] else m[i][j] } }
        val a = det3(replaced(0)) / det
        val b = det3(replaced(1)) / det
        val c = det3(replaced(2)) / det

        val residuals = u.indices.map { y[it] - (a + b * u[it] + c * u[it] * u[it]) }
        val ssr = residuals.sumOf { it * it }

        // Standard error of c from the residual variance and (XᵀX)⁻¹.
        val inverseCC = (s[0] * s[2] - s[1] * s[1]) / det
        val seC = sqrt(ssr / (n - 3) * inverseCC)
        val tStat = when {
            seC > 0 -> -c / seC
            abs(c) < 1e-9 -> 0.0
            else -> Double.POSITIVE_INFINITY
        }
        val gravity = G - 2 * c
        val gravityOk = abs(gravity - G) <= Jump.GRAVITY_TOLERANCE || abs(tStat) < tCritical(n - 3)

        val diffs = (1 until n).map { residuals[it] - residuals[it - 1] }
        val durbinWatson = if (ssr > 0) diffs.sumOf { it * it } / ssr else 2.0
        val lag1 = if (ssr > 0) (1 until n).sumOf { residuals[it] * residuals[it - 1] } / ssr else 0.0
        val step = hasStep(diffs)

        return Evaluation(JumpFitCheck(
            gravityEstimate = gravity.toFloat(),
            gravityTStat = tStat.toFloat(),
            gravityOk = gravityOk,
            durbinWatson = durbinWatson.toFloat(),
            lag1Autocorrelation = lag1.toFloat(),
            residualStep = step,
            residualsOk = durbinWatson >= Jump.DURBIN_WATSON_MIN && !step,
            rmsCm = (sqrt(ssr / n) * 100).toFloat(),
            samples = n
        ), residuals)
    }

    /**
     * A frame-to-frame change of the residuals far beyond the usual ones.
     * Their spread is taken robustly, from the median size, so the step
     * itself doesn't inflate it.
     */
    private fun hasStep(diffs: List<Double>): Boolean {
        if (diffs.isEmpty()) return false
        val sizes = diffs.map(::abs).sorted()
        val sigma = 1.4826 * sizes[sizes.size / 2]
        val limit = maxOf(Jump.RESIDUAL_STEP_MIN_M.toDouble(), Jump.RESIDUAL_STEP_SIGMAS * sigma)
        return sizes.last() > limit
    }

    private fun det3(x: Array<DoubleArray>) =
        x[0][0] * (x[1][1] * x[2][2] - x[1][2] * x[2][1]) -
            x[0][1] * (x[1][0] * x[2][2] - x[1][2] * x[2][0]) +
            x[0][2] * (x[1][0] * x[2][1] - x[1][1] * x[2][0])

    /** Two-sided 5% critical value of Student's t with [df] degrees of freedom. */
    private fun tCritical(df: Int): Double = T_975.getOrNull(df - 1) ?: 1.96

    private val T_975 = doubleArrayOf(
        12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
        2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
        2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042
    )
}
