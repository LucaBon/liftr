package com.lifthelper.vision.calculator

import android.util.Log
import com.lifthelper.domain.model.CalibrationData
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.PositionData
import com.lifthelper.domain.model.PoseResult
import com.lifthelper.domain.model.VelocityData
import com.lifthelper.util.CircularBuffer
import com.lifthelper.util.Constants
import com.lifthelper.util.Constants.TAG_VELOCITY
import javax.inject.Inject

class VelocityCalculator @Inject constructor(
    private val positionCalculator: PositionCalculator,
    private val kalmanFilter: KalmanFilter1D
) {
    private val positionHistory = CircularBuffer<PositionData>(Constants.VELOCITY_HISTORY_SIZE)

    // Separate Kalman filters for each body part
    private val hipsKalmanFilter = KalmanFilter1D()
    private val shouldersKalmanFilter = KalmanFilter1D()
    private val headKalmanFilter = KalmanFilter1D()

    // Separate position histories for each body part
    private val hipsPositionHistory = CircularBuffer<PositionData>(Constants.VELOCITY_HISTORY_SIZE)
    private val shouldersPositionHistory = CircularBuffer<PositionData>(Constants.VELOCITY_HISTORY_SIZE)
    private val headPositionHistory = CircularBuffer<PositionData>(Constants.VELOCITY_HISTORY_SIZE)

    // Velocity smoothing buffer (for reducing noise in velocity calculations)
    private val velocitySmoothing = CircularBuffer<Float>(5) // 5-frame moving average

    private var startTime: Long? = null
    private var frameCounter = 0

    fun calculate(
        poseResult: PoseResult,
        exerciseType: ExerciseType,
        calibrationData: CalibrationData,
        timestamp: Long
    ): VelocityData? {
        if (startTime == null) {
            startTime = timestamp
        }

        // For front squats, use multi-point body part tracking with vertical-only velocity
        if (exerciseType == ExerciseType.FRONT_SQUAT) {
            return calculateFrontSquatVelocity(poseResult, calibrationData, timestamp)
        }

        // For other exercises, use original single-point tracking
        // 1. Get bar position in pixels
        val barPositionPixels = positionCalculator.getBarPosition(poseResult, exerciseType)
            ?: return null

        // 2. Convert to meters
        val positionMeters = positionCalculator.pixelsToMeters(
            normalizedY = barPositionPixels.second,
            poseResult = poseResult,
            calibrationData = calibrationData
        )

        // 3. Smooth with Kalman filter
        val smoothedPosition = kalmanFilter.update(positionMeters)

        // 4. Store in history
        positionHistory.add(PositionData(smoothedPosition, timestamp))

        // 5. Calculate instantaneous velocity
        val instantVelocity = if (positionHistory.size >= Constants.VELOCITY_HISTORY_SIZE) {
            calculateSecondOrderGradient(positionHistory)
        } else {
            0f
        }

        // 6. Calculate average velocity
        val avgVelocity = calculateAverageVelocity(positionHistory)

        return VelocityData(
            instantaneous = instantVelocity,
            average = avgVelocity,
            position = smoothedPosition,
            timestamp = timestamp
        )
    }

    /**
     * Calculate velocity for front squats using multi-point body part tracking
     * Tracks vertical movement of hips, shoulders, and head separately
     */
    private fun calculateFrontSquatVelocity(
        poseResult: PoseResult,
        calibrationData: CalibrationData,
        timestamp: Long
    ): VelocityData? {
        // Get body part positions (vertical Y-coordinates only)
        val bodyPartPositions = positionCalculator.getBodyPartPositions(poseResult)
            ?: return null

        var velocitySum = 0f
        var velocityCount = 0
        var positionSum = 0f
        var positionCount = 0

        val bodyPartVelocities = mutableListOf<String>()

        // Process hips
        bodyPartPositions.hipsY?.let { hipsY ->
            val hipsMeters = positionCalculator.pixelsToMeters(hipsY, poseResult, calibrationData)
            val smoothedHips = hipsKalmanFilter.update(hipsMeters)
            hipsPositionHistory.add(PositionData(smoothedHips, timestamp))

            if (hipsPositionHistory.size >= Constants.VELOCITY_HISTORY_SIZE) {
                val hipsVelocity = calculateSecondOrderGradient(hipsPositionHistory)
                velocitySum += hipsVelocity
                velocityCount++
                bodyPartVelocities.add("Hips: %.2f m/s".format(hipsVelocity))
            }
            positionSum += smoothedHips
            positionCount++
        }

        // Process shoulders
        bodyPartPositions.shouldersY?.let { shouldersY ->
            val shouldersMeters = positionCalculator.pixelsToMeters(shouldersY, poseResult, calibrationData)
            val smoothedShoulders = shouldersKalmanFilter.update(shouldersMeters)
            shouldersPositionHistory.add(PositionData(smoothedShoulders, timestamp))

            if (shouldersPositionHistory.size >= Constants.VELOCITY_HISTORY_SIZE) {
                val shouldersVelocity = calculateSecondOrderGradient(shouldersPositionHistory)
                velocitySum += shouldersVelocity
                velocityCount++
                bodyPartVelocities.add("Shoulders: %.2f m/s".format(shouldersVelocity))
            }
            positionSum += smoothedShoulders
            positionCount++
        }

        // Process head
        bodyPartPositions.headY?.let { headY ->
            val headMeters = positionCalculator.pixelsToMeters(headY, poseResult, calibrationData)
            val smoothedHead = headKalmanFilter.update(headMeters)
            headPositionHistory.add(PositionData(smoothedHead, timestamp))

            if (headPositionHistory.size >= Constants.VELOCITY_HISTORY_SIZE) {
                val headVelocity = calculateSecondOrderGradient(headPositionHistory)
                velocitySum += headVelocity
                velocityCount++
                bodyPartVelocities.add("Head: %.2f m/s".format(headVelocity))
            }
            positionSum += smoothedHead
            positionCount++
        }

        // If we don't have any velocity data yet, return null
        if (velocityCount == 0 || positionCount == 0) {
            return null
        }

        // Average the velocities and positions from all tracked body parts
        val rawInstantVelocity = velocitySum / velocityCount
        val avgPosition = positionSum / positionCount

        // Apply moving average smoothing to reduce noise
        velocitySmoothing.add(rawInstantVelocity)
        val smoothedInstantVelocity = if (velocitySmoothing.size >= 3) {
            // Simple moving average of last 5 frames
            velocitySmoothing.toList().average().toFloat()
        } else {
            rawInstantVelocity
        }

        // For bar speed (average velocity), use only shoulders and head (not hips)
        // This represents the actual bar position during the lift
        val barVelocities = listOf(
            if (shouldersPositionHistory.size >= 2) calculateAverageVelocity(shouldersPositionHistory) else null,
            if (headPositionHistory.size >= 2) calculateAverageVelocity(headPositionHistory) else null
        ).filterNotNull()

        val avgVelocity = if (barVelocities.isNotEmpty()) {
            barVelocities.average().toFloat()
        } else {
            0f
        }

        // Log body part velocities every 30 frames (only when velocity is significant)
        if (kotlin.math.abs(smoothedInstantVelocity) > 0.1f && frameCounter++ % 30 == 0) {
            Log.d(TAG_VELOCITY, "Front Squat - ${bodyPartVelocities.joinToString(", ")} | Instant: %.2f m/s (smoothed) | Bar Speed: %.2f m/s".format(smoothedInstantVelocity, avgVelocity))
        }

        return VelocityData(
            instantaneous = smoothedInstantVelocity,
            average = avgVelocity,
            position = avgPosition,
            timestamp = timestamp
        )
    }

    /**
     * Calculate instantaneous velocity at the current time (newest point) using 2nd order backward difference
     * Formula: v[n] = (3*f[n] - 4*f[n-1] + f[n-2]) / (2*h)
     *
     * Uses backward difference at the right endpoint to get velocity at current time,
     * not at the center of the history buffer.
     */
    private fun calculateSecondOrderGradient(history: CircularBuffer<PositionData>): Float {
        if (history.size < 3) return 0f

        // For velocity at current time (index n=4), use 2nd order backward difference
        // v[n] = (3*f[n] - 4*f[n-1] + f[n-2]) / (2*h)
        val p = history
        val n = history.size - 1 // Index of newest point (4 when size=5)

        val dx = 3 * p[n].position - 4 * p[n-1].position + p[n-2].position

        // Calculate time step between consecutive points
        val h1 = (p[n].timestamp - p[n-1].timestamp) / 1000f
        val h2 = (p[n-1].timestamp - p[n-2].timestamp) / 1000f
        val h = (h1 + h2) / 2f // Average time step

        return if (h > 0) dx / (2 * h) else 0f
    }

    /**
     * Average velocity over entire movement
     */
    private fun calculateAverageVelocity(history: CircularBuffer<PositionData>): Float {
        if (history.size < 2) return 0f

        val totalDisplacement = history.last().position - history.first().position
        val totalTime = (history.last().timestamp - history.first().timestamp) / 1000f

        return if (totalTime > 0) totalDisplacement / totalTime else 0f
    }

    fun reset() {
        positionHistory.clear()
        kalmanFilter.reset()

        // Reset body part tracking
        hipsPositionHistory.clear()
        shouldersPositionHistory.clear()
        headPositionHistory.clear()
        hipsKalmanFilter.reset()
        shouldersKalmanFilter.reset()
        headKalmanFilter.reset()

        // Reset velocity smoothing
        velocitySmoothing.clear()

        startTime = null
    }
}
