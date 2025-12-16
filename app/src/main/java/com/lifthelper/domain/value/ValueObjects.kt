package com.lifthelper.domain.value

import kotlin.math.abs

/**
 * Type-safe value object for weight measurements in kilograms.
 *
 * This value class ensures compile-time safety for weight values and
 * prevents accidental mixing of different units or negative weights.
 *
 * @property value The weight value in kilograms (must be >= 0)
 * @throws IllegalArgumentException if value is negative
 */
@JvmInline
value class Kilograms(val value: Float) {
    init {
        require(value >= 0) { "Weight cannot be negative: $value" }
    }

    operator fun plus(other: Kilograms) = Kilograms(value + other.value)
    operator fun minus(other: Kilograms) = Kilograms(value - other.value)
    operator fun times(scalar: Float) = Kilograms(value * scalar)
    operator fun div(scalar: Float) = Kilograms(value / scalar)
    operator fun compareTo(other: Kilograms): Int = value.compareTo(other.value)
}

/**
 * Type-safe value object for distance measurements in meters.
 *
 * This value class ensures compile-time safety for distance values and
 * prevents accidental mixing of different units or negative distances.
 *
 * @property value The distance value in meters (must be >= 0)
 * @throws IllegalArgumentException if value is negative
 */
@JvmInline
value class Meters(val value: Float) {
    init {
        require(value >= 0) { "Distance cannot be negative: $value" }
    }

    operator fun plus(other: Meters) = Meters(value + other.value)
    operator fun minus(other: Meters) = Meters(value - other.value)
    operator fun times(scalar: Float) = Meters(value * scalar)
    operator fun div(scalar: Float) = Meters(value / scalar)
    operator fun compareTo(other: Meters): Int = value.compareTo(other.value)
}

/**
 * Type-safe value object for velocity measurements in meters per second.
 *
 * This value class ensures compile-time safety for velocity values and
 * prevents accidental mixing of different units. Unlike distance and weight,
 * velocity can be negative (indicating direction).
 *
 * @property value The velocity value in meters per second
 */
@JvmInline
value class MetersPerSecond(val value: Float) {
    /**
     * Returns the absolute value of this velocity.
     */
    fun abs() = MetersPerSecond(abs(value))

    operator fun plus(other: MetersPerSecond) = MetersPerSecond(value + other.value)
    operator fun minus(other: MetersPerSecond) = MetersPerSecond(value - other.value)
    operator fun times(scalar: Float) = MetersPerSecond(value * scalar)
    operator fun div(scalar: Float) = MetersPerSecond(value / scalar)
    operator fun unaryMinus() = MetersPerSecond(-value)
    operator fun compareTo(other: MetersPerSecond): Int = value.compareTo(other.value)
}

/**
 * Type-safe value object for power measurements in watts.
 *
 * This value class ensures compile-time safety for power values and
 * prevents accidental mixing of different units or negative power values.
 *
 * @property value The power value in watts (must be >= 0)
 * @throws IllegalArgumentException if value is negative
 */
@JvmInline
value class Watts(val value: Float) {
    init {
        require(value >= 0) { "Power cannot be negative: $value" }
    }

    operator fun plus(other: Watts) = Watts(value + other.value)
    operator fun minus(other: Watts) = Watts(value - other.value)
    operator fun times(scalar: Float) = Watts(value * scalar)
    operator fun div(scalar: Float) = Watts(value / scalar)
    operator fun compareTo(other: Watts): Int = value.compareTo(other.value)
}

/**
 * Type-safe value object for energy measurements in joules.
 *
 * This value class ensures compile-time safety for energy values and
 * prevents accidental mixing of different units or negative energy values.
 *
 * @property value The energy value in joules (must be >= 0)
 * @throws IllegalArgumentException if value is negative
 */
@JvmInline
value class Joules(val value: Float) {
    init {
        require(value >= 0) { "Energy cannot be negative: $value" }
    }

    operator fun plus(other: Joules) = Joules(value + other.value)
    operator fun minus(other: Joules) = Joules(value - other.value)
    operator fun times(scalar: Float) = Joules(value * scalar)
    operator fun div(scalar: Float) = Joules(value / scalar)
    operator fun compareTo(other: Joules): Int = value.compareTo(other.value)
}

/**
 * Type-safe value object for rep count.
 *
 * This value class ensures compile-time safety for rep counts and
 * prevents negative values.
 *
 * @property value The rep count (must be >= 0)
 * @throws IllegalArgumentException if value is negative
 */
@JvmInline
value class RepCount(val value: Int) {
    init {
        require(value >= 0) { "Rep count cannot be negative: $value" }
    }

    operator fun inc() = RepCount(value + 1)
    operator fun dec() = RepCount(value - 1)
    operator fun plus(other: RepCount) = RepCount(value + other.value)
    operator fun minus(other: RepCount) = RepCount(value - other.value)
    operator fun compareTo(other: RepCount): Int = value.compareTo(other.value)
}

/**
 * Type-safe value object for percentage values.
 *
 * This value class ensures compile-time safety for percentage values and
 * enforces that values are within the valid 0-100 range.
 *
 * @property value The percentage value (must be in range 0-100)
 * @throws IllegalArgumentException if value is outside 0-100 range
 */
@JvmInline
value class Percentage(val value: Float) {
    init {
        require(value in 0f..100f) { "Percentage must be 0-100: $value" }
    }

    operator fun plus(other: Percentage) = Percentage(value + other.value)
    operator fun minus(other: Percentage) = Percentage(value - other.value)
    operator fun times(scalar: Float) = Percentage(value * scalar)
    operator fun div(scalar: Float) = Percentage(value / scalar)
    operator fun compareTo(other: Percentage): Int = value.compareTo(other.value)
}
