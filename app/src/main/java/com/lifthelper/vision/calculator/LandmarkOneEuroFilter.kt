package com.lifthelper.vision.calculator

import com.lifthelper.domain.model.PoseKeypoint
import com.lifthelper.util.Constants
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.abs

private class OneEuro1D(
    private val minCutoff: Float,
    private val beta: Float,
    private val dCutoff: Float
) {
    private var initialized = false
    private var xPrev = 0f
    private var dxPrev = 0f

    fun filter(x: Float, dt: Float): Float {
        if (!initialized || dt <= 0f) {
            initialized = true
            xPrev = x
            dxPrev = 0f
            return x
        }
        val dx = (x - xPrev) / dt
        val edx = lowpass(dx, dxPrev, alpha(dCutoff, dt))
        val cutoff = minCutoff + beta * abs(edx)
        val ex = lowpass(x, xPrev, alpha(cutoff, dt))
        xPrev = ex
        dxPrev = edx
        return ex
    }

    fun reset() {
        initialized = false
        xPrev = 0f
        dxPrev = 0f
    }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    private fun lowpass(x: Float, prev: Float, a: Float): Float = a * x + (1f - a) * prev
}

/**
 * Applies a OneEuro filter independently to each landmark's (x, y, z), preserving
 * visibility. Reduces jitter on MediaPipe pose landmarks, especially under
 * self-occlusion (e.g. bottom of a squat).
 */
@Singleton
class LandmarkOneEuroFilter @Inject constructor() {
    private val filters: Array<Array<OneEuro1D>> = Array(LANDMARK_COUNT) {
        Array(AXIS_COUNT) {
            OneEuro1D(
                minCutoff = Constants.ONE_EURO_MIN_CUTOFF,
                beta = Constants.ONE_EURO_BETA,
                dCutoff = Constants.ONE_EURO_DCUTOFF
            )
        }
    }
    private var lastTimestampMs: Long = 0L

    @Synchronized
    fun apply(landmarks: List<PoseKeypoint>, timestampMs: Long): List<PoseKeypoint> {
        if (landmarks.size != LANDMARK_COUNT) return landmarks
        val dt = if (lastTimestampMs == 0L) 0f
                 else ((timestampMs - lastTimestampMs).toFloat() / 1000f).coerceAtLeast(0f)
        lastTimestampMs = timestampMs
        return landmarks.mapIndexed { i, lm ->
            PoseKeypoint(
                x = filters[i][0].filter(lm.x, dt),
                y = filters[i][1].filter(lm.y, dt),
                z = filters[i][2].filter(lm.z, dt),
                visibility = lm.visibility
            )
        }
    }

    @Synchronized
    fun reset() {
        filters.forEach { axes -> axes.forEach { it.reset() } }
        lastTimestampMs = 0L
    }

    companion object {
        private const val LANDMARK_COUNT = 33
        private const val AXIS_COUNT = 3
    }
}
