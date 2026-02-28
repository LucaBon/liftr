package com.lifthelper.domain.model

import com.lifthelper.domain.value.MetersPerSecond

/**
 * Velocity-Based Training zones based on Gonzalez-Badillo et al. (2017).
 *
 * These universal zones apply to compound lifts (squat, bench, deadlift)
 * and classify mean concentric velocity into training intensity categories.
 *
 * @property displayName Human-readable zone name
 * @property minVelocity Inclusive lower bound (m/s)
 * @property maxVelocity Exclusive upper bound (m/s)
 * @property colorHex ARGB color for UI rendering
 * @property description Training quality description
 */
enum class VbtZone(
    val displayName: String,
    val minVelocity: Float,
    val maxVelocity: Float,
    val colorHex: Long,
    val description: String
) {
    GRINDING(
        displayName = "Grinding",
        minVelocity = 0.00f,
        maxVelocity = 0.30f,
        colorHex = 0xFFE53935,
        description = "Maximal / near failure"
    ),
    MAX_STRENGTH(
        displayName = "Max Strength",
        minVelocity = 0.30f,
        maxVelocity = 0.50f,
        colorHex = 0xFFFF9800,
        description = "Near-maximal effort"
    ),
    STRENGTH_SPEED(
        displayName = "Strength-Speed",
        minVelocity = 0.50f,
        maxVelocity = 0.75f,
        colorHex = 0xFFFFEB3B,
        description = "Heavy strength work"
    ),
    SPEED_STRENGTH(
        displayName = "Speed-Strength",
        minVelocity = 0.75f,
        maxVelocity = 1.00f,
        colorHex = 0xFF4CAF50,
        description = "Fast strength work"
    ),
    SPEED_POWER(
        displayName = "Speed/Power",
        minVelocity = 1.00f,
        maxVelocity = Float.MAX_VALUE,
        colorHex = 0xFF2196F3,
        description = "Explosive power"
    );

    companion object {
        /**
         * Determines the VBT zone for a given mean concentric velocity.
         * Uses absolute value since velocity direction is irrelevant for zone classification.
         */
        fun fromVelocity(velocity: MetersPerSecond): VbtZone {
            val v = velocity.abs().value
            return entries.first { v >= it.minVelocity && v < it.maxVelocity }
        }
    }
}
