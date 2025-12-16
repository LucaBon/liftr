package com.lifthelper.domain.model

import com.lifthelper.domain.value.Meters
import com.lifthelper.domain.value.MetersPerSecond
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepTest {

    @Test
    fun `should create Rep with all fields`() {
        // Given
        val repNumber = 1
        val peakVelocity = MetersPerSecond(1.2f)
        val avgVelocity = MetersPerSecond(0.8f)
        val timeUnderTension = 2.5f
        val rangeOfMotion = Meters(0.5f)
        val startPosition = Meters(1.5f)
        val endPosition = Meters(1.0f)
        val eccentricDuration = 1.5f
        val concentricDuration = 1.0f
        val timestamp = System.currentTimeMillis()

        // When
        val rep = Rep(
            id = 1,
            setId = 1,
            repNumber = repNumber,
            peakVelocity = peakVelocity,
            avgVelocity = avgVelocity,
            timeUnderTension = timeUnderTension,
            rangeOfMotion = rangeOfMotion,
            startPosition = startPosition,
            endPosition = endPosition,
            eccentricDuration = eccentricDuration,
            concentricDuration = concentricDuration,
            velocityProfile = emptyList(),
            positionProfile = emptyList(),
            timestamp = timestamp
        )

        // Then
        assertEquals(1, rep.id)
        assertEquals(1, rep.setId)
        assertEquals(repNumber, rep.repNumber)
        assertEquals(peakVelocity, rep.peakVelocity)
        assertEquals(avgVelocity, rep.avgVelocity)
        assertEquals(timeUnderTension, rep.timeUnderTension, 0.001f)
        assertEquals(rangeOfMotion, rep.rangeOfMotion)
        assertEquals(startPosition, rep.startPosition)
        assertEquals(endPosition, rep.endPosition)
        assertEquals(eccentricDuration, rep.eccentricDuration, 0.001f)
        assertEquals(concentricDuration, rep.concentricDuration, 0.001f)
        assertEquals(timestamp, rep.timestamp)
    }

    @Test
    fun `should calculate total duration correctly`() {
        // Given
        val rep = createTestRep(
            eccentricDuration = 1.5f,
            concentricDuration = 1.0f
        )

        // When
        val totalDuration = rep.getTotalDuration()

        // Then
        assertEquals(2.5f, totalDuration, 0.001f)
    }

    @Test
    fun `should calculate eccentric concentric ratio correctly`() {
        // Given
        val rep = createTestRep(
            eccentricDuration = 2.0f,
            concentricDuration = 1.0f
        )

        // When
        val ratio = rep.getEccentricConcentricRatio()

        // Then
        assertEquals(2.0f, ratio, 0.001f)
    }

    @Test
    fun `should return zero ratio when concentric duration is zero`() {
        // Given
        val rep = createTestRep(
            eccentricDuration = 1.5f,
            concentricDuration = 0f
        )

        // When
        val ratio = rep.getEccentricConcentricRatio()

        // Then
        assertEquals(0f, ratio, 0.001f)
    }

    @Test
    fun `should return true for good velocity when avg is 80% or more of peak`() {
        // Given
        val rep = createTestRep(
            peakVelocity = MetersPerSecond(1.0f),
            avgVelocity = MetersPerSecond(0.8f)
        )

        // When
        val hasGoodVelocity = rep.hasGoodVelocity()

        // Then
        assertTrue(hasGoodVelocity)
    }

    @Test
    fun `should return true for good velocity when avg is more than 80% of peak`() {
        // Given
        val rep = createTestRep(
            peakVelocity = MetersPerSecond(1.0f),
            avgVelocity = MetersPerSecond(0.9f)
        )

        // When
        val hasGoodVelocity = rep.hasGoodVelocity()

        // Then
        assertTrue(hasGoodVelocity)
    }

    @Test
    fun `should return false for poor velocity when avg is less than 80% of peak`() {
        // Given
        val rep = createTestRep(
            peakVelocity = MetersPerSecond(1.0f),
            avgVelocity = MetersPerSecond(0.7f)
        )

        // When
        val hasGoodVelocity = rep.hasGoodVelocity()

        // Then
        assertFalse(hasGoodVelocity)
    }

    @Test
    fun `should return false for good velocity when peak is zero`() {
        // Given
        val rep = createTestRep(
            peakVelocity = MetersPerSecond(0f),
            avgVelocity = MetersPerSecond(0f)
        )

        // When
        val hasGoodVelocity = rep.hasGoodVelocity()

        // Then
        assertFalse(hasGoodVelocity)
    }

    @Test
    fun `should test data class equality`() {
        // Given
        val timestamp = System.currentTimeMillis()
        val rep1 = createTestRep(timestamp = timestamp)
        val rep2 = createTestRep(timestamp = timestamp)

        // Then
        assertEquals(rep1, rep2)
        assertEquals(rep1.hashCode(), rep2.hashCode())
    }

    @Test
    fun `should test data class copy`() {
        // Given
        val rep = createTestRep(repNumber = 1)

        // When
        val copiedRep = rep.copy(repNumber = 2)

        // Then
        assertEquals(2, copiedRep.repNumber)
        assertEquals(rep.peakVelocity, copiedRep.peakVelocity)
        assertEquals(rep.avgVelocity, copiedRep.avgVelocity)
    }

    @Test
    fun `should handle velocity profile data`() {
        // Given
        val velocityProfile = listOf(
            VelocityProfile(1000, 0.5f),
            VelocityProfile(1100, 0.8f),
            VelocityProfile(1200, 1.0f)
        )
        val rep = createTestRep(velocityProfile = velocityProfile)

        // Then
        assertEquals(3, rep.velocityProfile.size)
        assertEquals(0.5f, rep.velocityProfile[0].velocity, 0.001f)
        assertEquals(1.0f, rep.velocityProfile[2].velocity, 0.001f)
    }

    @Test
    fun `should handle position profile data`() {
        // Given
        val positionProfile = listOf(
            PositionProfile(1000, 1.5f),
            PositionProfile(1100, 1.3f),
            PositionProfile(1200, 1.0f)
        )
        val rep = createTestRep(positionProfile = positionProfile)

        // Then
        assertEquals(3, rep.positionProfile.size)
        assertEquals(1.5f, rep.positionProfile[0].position, 0.001f)
        assertEquals(1.0f, rep.positionProfile[2].position, 0.001f)
    }

    // Helper function to create test reps
    private fun createTestRep(
        id: Long = 0,
        setId: Long = 1,
        repNumber: Int = 1,
        peakVelocity: MetersPerSecond = MetersPerSecond(1.2f),
        avgVelocity: MetersPerSecond = MetersPerSecond(0.8f),
        timeUnderTension: Float = 2.5f,
        rangeOfMotion: Meters = Meters(0.5f),
        startPosition: Meters = Meters(1.5f),
        endPosition: Meters = Meters(1.0f),
        eccentricDuration: Float = 1.5f,
        concentricDuration: Float = 1.0f,
        velocityProfile: List<VelocityProfile> = emptyList(),
        positionProfile: List<PositionProfile> = emptyList(),
        timestamp: Long = System.currentTimeMillis()
    ): Rep {
        return Rep(
            id = id,
            setId = setId,
            repNumber = repNumber,
            peakVelocity = peakVelocity,
            avgVelocity = avgVelocity,
            timeUnderTension = timeUnderTension,
            rangeOfMotion = rangeOfMotion,
            startPosition = startPosition,
            endPosition = endPosition,
            eccentricDuration = eccentricDuration,
            concentricDuration = concentricDuration,
            velocityProfile = velocityProfile,
            positionProfile = positionProfile,
            timestamp = timestamp
        )
    }
}
