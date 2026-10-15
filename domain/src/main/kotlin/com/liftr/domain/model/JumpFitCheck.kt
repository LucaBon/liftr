package com.liftr.domain.model

import kotlinx.serialization.Serializable

/**
 * Whether a jump's center-of-mass flight looks like free fall, i.e. whether
 * the center of mass and its scale can be trusted, and whether the measured
 * takeoff and landing agree with it.
 *
 * @property gravityEstimate Gravity fitted freely to the flight, in m/s²
 * @property gravityTStat How many standard errors [gravityEstimate] is from 9.81
 * @property gravityOk Gravity close to 9.81, or not distinguishable from it
 * @property durbinWatson Durbin-Watson statistic of the fit residuals: ~2 when
 *   they are random, low when they follow a trend (a wrong mass model)
 * @property lag1Autocorrelation Correlation of each residual with the previous one
 * @property residualStep A sudden jump in the residuals: a joint lost and
 *   picked up somewhere else
 * @property residualsOk Residuals random and without steps
 * @property rmsCm Root mean square of the residuals, in cm
 * @property samples Flight frames the fit used
 * @property droppedFrames Frames left out because they made a step in the residuals
 * @property landingDropFromTimesCm How much lower the center of mass must be
 *   at landing than at takeoff for the fall (landing - apex) to outlast the
 *   rise (apex - takeoff): g·(fall² - rise²)/2. Landing on bent legs makes it
 *   positive.
 * @property landingDropCm How much lower the center of mass actually was at
 *   landing than at takeoff
 * @property timingOk The two drops agree: takeoff and landing are timed right.
 *   A frame off at either end moves the first by ~4-5 cm.
 */
@Serializable
data class JumpFitCheck(
    val gravityEstimate: Float,
    val gravityTStat: Float,
    val gravityOk: Boolean,
    val durbinWatson: Float,
    val lag1Autocorrelation: Float,
    val residualStep: Boolean,
    val residualsOk: Boolean,
    val rmsCm: Float,
    val samples: Int,
    val droppedFrames: Int = 0,
    val landingDropFromTimesCm: Float? = null,
    val landingDropCm: Float? = null,
    val timingOk: Boolean = true
) {
    val passed: Boolean get() = gravityOk && residualsOk && timingOk

    /** One line for the logs. */
    fun describe(): String =
        ("%s: g=%.2f m/s² (t=%.1f), DW=%.2f, r1=%.2f, step=%s, rms=%.1f cm, " +
            "%d frames, %d dropped, landing drop %s").format(
            if (passed) "ok" else "FAILED", gravityEstimate, gravityTStat, durbinWatson, lag1Autocorrelation,
            residualStep, rmsCm, samples, droppedFrames,
            if (landingDropCm != null && landingDropFromTimesCm != null) {
                "%.1f cm vs %.1f cm from times (%s)".format(landingDropCm, landingDropFromTimesCm, if (timingOk) "ok" else "off")
            } else "n/a"
        )
}
