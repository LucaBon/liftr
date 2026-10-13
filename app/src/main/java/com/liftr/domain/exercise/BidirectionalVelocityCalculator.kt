package com.liftr.domain.exercise

import com.liftr.domain.model.VelocityData
import com.liftr.domain.value.Meters
import com.liftr.domain.value.MetersPerSecond
import kotlin.math.abs

/**
 * Bidirectional Kalman filter for post-exercise velocity estimation (Feature 17).
 *
 * Unlike the real-time `VelocityCalculator` (forward-only, small buffer), this
 * processes the entire position time series in both directions then averages the
 * two passes, producing significantly smoother velocity estimates because it can
 * use the full set's context.
 *
 * Pure functions, no Android dependencies.
 *
 * Algorithm:
 * 1. Forward Kalman pass: estimate velocity at each timestamp.
 * 2. Backward Kalman pass over the reversed series, then reverse the result.
 * 3. Equal-weight average of the forward and backward estimates.
 */
object BidirectionalVelocityCalculator {

    /** A single tracked vertical position sample. */
    data class PositionSample(
        val positionMeters: Float,
        val timestampMs: Long
    )

    private const val DEFAULT_PROCESS_NOISE = 0.01f

    /**
     * Variance of the tracked position measurements (m²) — 0.01 corresponds to
     * roughly 10 cm of landmark jitter, which is generous for a calibrated
     * position and still lets the filter follow real bar acceleration.
     */
    private const val DEFAULT_MEASUREMENT_NOISE = 0.01f

    /**
     * Initial variance of the velocity state. Deliberately large: the starting
     * velocity is essentially unknown, so early measurements should dominate
     * the estimate instead of being pulled toward the seed value.
     */
    private const val INITIAL_VELOCITY_VARIANCE = 100f

    /**
     * Calculate smoothed velocities from a chronologically ordered series of
     * positions. The returned list is aligned 1:1 with [positions].
     */
    fun calculateVelocities(
        positions: List<PositionSample>,
        processNoise: Float = DEFAULT_PROCESS_NOISE,
        measurementNoise: Float = DEFAULT_MEASUREMENT_NOISE
    ): List<VelocityData> {
        if (positions.size < 2) {
            return positions.map { sample ->
                VelocityData(
                    instantaneous = MetersPerSecond(0f),
                    average = MetersPerSecond(0f),
                    position = Meters(abs(sample.positionMeters)),
                    timestamp = sample.timestampMs
                )
            }
        }

        val forward = kalmanPass(positions, processNoise, measurementNoise)
        // The backward pass walks the series in reversed time, so its velocity
        // sign is flipped relative to the forward pass. Negate it before
        // averaging, otherwise the two passes cancel each other out.
        val backward = kalmanPass(positions.reversed(), processNoise, measurementNoise)
            .reversed()
            .map { -it }

        return positions.mapIndexed { i, sample ->
            val avgVelocity = (forward[i] + backward[i]) / 2f
            VelocityData(
                instantaneous = MetersPerSecond(abs(avgVelocity)),
                average = MetersPerSecond(abs(avgVelocity)),
                position = Meters(abs(sample.positionMeters)),
                timestamp = sample.timestampMs
            )
        }
    }

    /**
     * Single-direction Kalman filter pass. Returns the estimated (signed)
     * velocity at each sample point.
     *
     * Constant-velocity model: the state is `[position, velocity]` and the
     * prediction step advances position by the *previously estimated* velocity.
     * Velocity is therefore a filtered state carried across samples, not a
     * per-frame finite difference, which is what makes this smooth landmark
     * jitter instead of reproducing it.
     *
     * @param processNoise how much the model is allowed to deviate from
     *   constant velocity per second — larger tracks acceleration faster.
     * @param measurementNoise variance of the position measurements — larger
     *   trusts the model over the landmark positions.
     */
    private fun kalmanPass(
        positions: List<PositionSample>,
        processNoise: Float,
        measurementNoise: Float
    ): List<Float> {
        val velocities = ArrayList<Float>(positions.size)

        // State: position (known from the first sample) and velocity, seeded
        // from the first finite difference so the filter starts near the true
        // motion instead of spending several samples climbing away from zero.
        var position = positions[0].positionMeters
        val seedDt = abs(positions[1].timestampMs - positions[0].timestampMs) / 1000f
        var velocity = if (seedDt > 0f) {
            (positions[1].positionMeters - positions[0].positionMeters) / seedDt
        } else 0f

        // Covariance of [position, velocity]; velocity starts highly uncertain.
        var p00 = 1f
        var p01 = 0f
        var p10 = 0f
        var p11 = INITIAL_VELOCITY_VARIANCE

        var prevTimestamp = positions[0].timestampMs

        velocities.add(0f) // first sample has no prior

        for (i in 1 until positions.size) {
            // Use the magnitude of the time delta: in the backward pass the
            // series is reversed, so raw deltas are negative even though the
            // spacing between samples is unchanged.
            val dt = abs(positions[i].timestampMs - prevTimestamp) / 1000f
            if (dt == 0f) {
                velocities.add(velocities.last())
                continue
            }

            // ── Predict: x = F x, P = F P Fᵀ + Q, with F = [[1, dt], [0, 1]] ──
            position += velocity * dt

            val q = processNoise * dt
            val predP00 = p00 + dt * (p01 + p10) + dt * dt * p11 + q
            val predP01 = p01 + dt * p11
            val predP10 = p10 + dt * p11
            val predP11 = p11 + q

            // ── Update with the position measurement, H = [1, 0] ──
            val innovation = positions[i].positionMeters - position
            val innovationCovariance = predP00 + measurementNoise
            val gainPosition = predP00 / innovationCovariance
            val gainVelocity = predP10 / innovationCovariance

            position += gainPosition * innovation
            velocity += gainVelocity * innovation

            p00 = (1f - gainPosition) * predP00
            p01 = (1f - gainPosition) * predP01
            p10 = predP10 - gainVelocity * predP00
            p11 = predP11 - gainVelocity * predP01

            velocities.add(velocity)
            prevTimestamp = positions[i].timestampMs
        }

        return velocities
    }
}
