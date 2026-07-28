package com.lifthelper.vision.calculator

import com.lifthelper.util.Constants
import javax.inject.Inject

class KalmanFilter1D @Inject constructor() {
    private var estimate = 0f
    private var errorCovariance = 1f

    private val processNoise = Constants.KALMAN_PROCESS_NOISE
    private val measurementNoise = Constants.KALMAN_MEASUREMENT_NOISE

    fun update(measurement: Float): Float {
        // A non-finite measurement would make the estimate Infinity, and the very
        // next finite measurement turns it into NaN (Infinity - Infinity). NaN
        // then survives every subsequent update and only clears on reset(), so
        // one bad frame would silently disable tracking for the rest of the set.
        if (!measurement.isFinite()) return estimate

        // Prediction step
        val predictionError = errorCovariance + processNoise

        // Update step
        val kalmanGain = predictionError / (predictionError + measurementNoise)
        estimate += kalmanGain * (measurement - estimate)
        errorCovariance = (1 - kalmanGain) * predictionError

        return estimate
    }

    fun reset() {
        estimate = 0f
        errorCovariance = 1f
    }
}
