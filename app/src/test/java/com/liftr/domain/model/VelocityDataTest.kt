package com.liftr.domain.model

import com.liftr.domain.value.Meters
import com.liftr.domain.value.MetersPerSecond
import org.junit.Assert.assertEquals
import org.junit.Test

class VelocityDataTest {

    @Test
    fun `should create VelocityData with all fields`() {
        // Given
        val instantaneous = MetersPerSecond(1.0f)
        val average = MetersPerSecond(0.8f)
        val position = Meters(1.5f)
        val timestamp = System.currentTimeMillis()

        // When
        val velocityData = VelocityData(
            instantaneous = instantaneous,
            average = average,
            position = position,
            timestamp = timestamp
        )

        // Then
        assertEquals(instantaneous, velocityData.instantaneous)
        assertEquals(average, velocityData.average)
        assertEquals(position, velocityData.position)
        assertEquals(timestamp, velocityData.timestamp)
    }

    @Test
    fun `should test data class equality`() {
        // Given
        val timestamp = System.currentTimeMillis()
        val velocityData1 = VelocityData(
            instantaneous = MetersPerSecond(1.0f),
            average = MetersPerSecond(0.8f),
            position = Meters(1.5f),
            timestamp = timestamp
        )
        val velocityData2 = VelocityData(
            instantaneous = MetersPerSecond(1.0f),
            average = MetersPerSecond(0.8f),
            position = Meters(1.5f),
            timestamp = timestamp
        )

        // Then
        assertEquals(velocityData1, velocityData2)
        assertEquals(velocityData1.hashCode(), velocityData2.hashCode())
    }

    @Test
    fun `should test data class copy`() {
        // Given
        val velocityData = VelocityData(
            instantaneous = MetersPerSecond(1.0f),
            average = MetersPerSecond(0.8f),
            position = Meters(1.5f),
            timestamp = System.currentTimeMillis()
        )

        // When
        val copiedData = velocityData.copy(instantaneous = MetersPerSecond(1.2f))

        // Then
        assertEquals(MetersPerSecond(1.2f), copiedData.instantaneous)
        assertEquals(velocityData.average, copiedData.average)
        assertEquals(velocityData.position, copiedData.position)
    }
}

class VelocityProfileTest {

    @Test
    fun `should create VelocityProfile with all fields`() {
        // Given
        val timestamp = 1000L
        val velocity = 0.8f

        // When
        val profile = VelocityProfile(timestamp, velocity)

        // Then
        assertEquals(timestamp, profile.timestamp)
        assertEquals(velocity, profile.velocity, 0.001f)
    }

    @Test
    fun `should test data class equality`() {
        // Given
        val profile1 = VelocityProfile(1000L, 0.8f)
        val profile2 = VelocityProfile(1000L, 0.8f)

        // Then
        assertEquals(profile1, profile2)
    }
}

class PositionProfileTest {

    @Test
    fun `should create PositionProfile with all fields`() {
        // Given
        val timestamp = 1000L
        val position = 1.5f

        // When
        val profile = PositionProfile(timestamp, position)

        // Then
        assertEquals(timestamp, profile.timestamp)
        assertEquals(position, profile.position, 0.001f)
    }

    @Test
    fun `should test data class equality`() {
        // Given
        val profile1 = PositionProfile(1000L, 1.5f)
        val profile2 = PositionProfile(1000L, 1.5f)

        // Then
        assertEquals(profile1, profile2)
    }
}

class VelocityZoneTest {

    @Test
    fun `should classify very slow velocity correctly`() {
        // Given
        val velocity = MetersPerSecond(0.2f)

        // When
        val zone = VelocityZone.fromVelocity(velocity)

        // Then
        assertEquals(VelocityZone.VERY_SLOW, zone)
    }

    @Test
    fun `should classify slow velocity correctly`() {
        // Given
        val velocity = MetersPerSecond(0.4f)

        // When
        val zone = VelocityZone.fromVelocity(velocity)

        // Then
        assertEquals(VelocityZone.SLOW, zone)
    }

    @Test
    fun `should classify moderate velocity correctly`() {
        // Given
        val velocity = MetersPerSecond(0.6f)

        // When
        val zone = VelocityZone.fromVelocity(velocity)

        // Then
        assertEquals(VelocityZone.MODERATE, zone)
    }

    @Test
    fun `should classify fast velocity correctly`() {
        // Given
        val velocity = MetersPerSecond(0.8f)

        // When
        val zone = VelocityZone.fromVelocity(velocity)

        // Then
        assertEquals(VelocityZone.FAST, zone)
    }

    @Test
    fun `should classify very fast velocity correctly`() {
        // Given
        val velocity = MetersPerSecond(1.5f)

        // When
        val zone = VelocityZone.fromVelocity(velocity)

        // Then
        assertEquals(VelocityZone.VERY_FAST, zone)
    }

    @Test
    fun `should handle negative velocity using absolute value`() {
        // Given
        val velocity = MetersPerSecond(-0.6f)

        // When
        val zone = VelocityZone.fromVelocity(velocity)

        // Then
        assertEquals(VelocityZone.MODERATE, zone)
    }

    @Test
    fun `should classify boundary value 0_3 as slow`() {
        // Given
        val velocity = MetersPerSecond(0.3f)

        // When
        val zone = VelocityZone.fromVelocity(velocity)

        // Then
        assertEquals(VelocityZone.SLOW, zone)
    }

    @Test
    fun `should classify boundary value 0_5 as moderate`() {
        // Given
        val velocity = MetersPerSecond(0.5f)

        // When
        val zone = VelocityZone.fromVelocity(velocity)

        // Then
        assertEquals(VelocityZone.MODERATE, zone)
    }

    @Test
    fun `should classify boundary value 0_7 as fast`() {
        // Given
        val velocity = MetersPerSecond(0.7f)

        // When
        val zone = VelocityZone.fromVelocity(velocity)

        // Then
        assertEquals(VelocityZone.FAST, zone)
    }

    @Test
    fun `should classify boundary value 1_0 as very fast`() {
        // Given
        val velocity = MetersPerSecond(1.0f)

        // When
        val zone = VelocityZone.fromVelocity(velocity)

        // Then
        assertEquals(VelocityZone.VERY_FAST, zone)
    }

    @Test
    fun `should classify zero velocity as very slow`() {
        // Given
        val velocity = MetersPerSecond(0f)

        // When
        val zone = VelocityZone.fromVelocity(velocity)

        // Then
        assertEquals(VelocityZone.VERY_SLOW, zone)
    }

    @Test
    fun `should verify zone ranges`() {
        // Verify the defined ranges for each zone
        assertEquals(0f, VelocityZone.VERY_SLOW.minVelocity, 0.001f)
        assertEquals(0.3f, VelocityZone.VERY_SLOW.maxVelocity, 0.001f)

        assertEquals(0.3f, VelocityZone.SLOW.minVelocity, 0.001f)
        assertEquals(0.5f, VelocityZone.SLOW.maxVelocity, 0.001f)

        assertEquals(0.5f, VelocityZone.MODERATE.minVelocity, 0.001f)
        assertEquals(0.7f, VelocityZone.MODERATE.maxVelocity, 0.001f)

        assertEquals(0.7f, VelocityZone.FAST.minVelocity, 0.001f)
        assertEquals(1.0f, VelocityZone.FAST.maxVelocity, 0.001f)

        assertEquals(1.0f, VelocityZone.VERY_FAST.minVelocity, 0.001f)
        assertEquals(Float.MAX_VALUE, VelocityZone.VERY_FAST.maxVelocity, 0.001f)
    }
}
