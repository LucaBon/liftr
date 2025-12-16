package com.lifthelper.domain.model

import android.util.Log
import com.lifthelper.domain.value.Meters
import com.lifthelper.domain.value.MetersPerSecond
import com.lifthelper.util.Constants.TAG_VELOCITY
import kotlinx.serialization.Serializable

data class VelocityData(
    val instantaneous: MetersPerSecond,
    val average: MetersPerSecond,
    val position: Meters,
    val timestamp: Long
)

@Serializable
data class VelocityProfile(
    val timestamp: Long,
    val velocity: Float
)

@Serializable
data class PositionProfile(
    val timestamp: Long,
    val position: Float
)

enum class VelocityZone(val minVelocity: Float, val maxVelocity: Float) {
    VERY_SLOW(0f, 0.3f),
    SLOW(0.3f, 0.5f),
    MODERATE(0.5f, 0.7f),
    FAST(0.7f, 1.0f),
    VERY_FAST(1.0f, Float.MAX_VALUE);

    companion object {
        fun fromVelocity(velocity: MetersPerSecond): VelocityZone {
            // Use absolute value since velocity can be negative (downward movement)
            val absVelocity = velocity.abs().value
            val zone = values().firstOrNull { absVelocity >= it.minVelocity && absVelocity < it.maxVelocity }
                ?: VERY_FAST

            // Log only for very fast movements or if zone couldn't be determined
            if (absVelocity > 10f) {
                Log.d(TAG_VELOCITY, "Velocity: %.2f m/s (abs: %.2f) -> Zone: $zone".format(velocity.value, absVelocity))
            }

            return zone
        }
    }
}
