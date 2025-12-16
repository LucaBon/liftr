package com.lifthelper.data.mapper

import com.lifthelper.data.local.database.entities.RepEntity
import com.lifthelper.domain.model.PositionProfile
import com.lifthelper.domain.model.Rep
import com.lifthelper.domain.model.VelocityProfile
import com.lifthelper.domain.value.Meters
import com.lifthelper.domain.value.MetersPerSecond
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RepMapperTest {

    private lateinit var mapper: RepMapper

    @Before
    fun setup() {
        mapper = RepMapper()
    }

    // Entity to Domain Tests
    @Test
    fun `should convert entity to domain correctly`() {
        // Given
        val timestamp = System.currentTimeMillis()
        val velocityProfile = listOf(
            VelocityProfile(timestamp = timestamp, velocity = 0.5f),
            VelocityProfile(timestamp = timestamp + 100, velocity = 1.0f)
        )
        val positionProfile = listOf(
            PositionProfile(timestamp = timestamp, position = 0.1f),
            PositionProfile(timestamp = timestamp + 100, position = 0.5f)
        )

        val entity = RepEntity(
            id = 123L,
            setId = 456L,
            repNumber = 3,
            peakVelocity = 1.2f,
            avgVelocity = 0.8f,
            timeUnderTension = 2.5f,
            rangeOfMotion = 0.6f,
            startPosition = 0.2f,
            endPosition = 0.8f,
            eccentricDuration = 1.5f,
            concentricDuration = 1.0f,
            velocityProfile = velocityProfile,
            positionProfile = positionProfile,
            timestamp = timestamp
        )

        // When
        val domain = mapper.toDomain(entity)

        // Then
        assertEquals(123L, domain.id)
        assertEquals(456L, domain.setId)
        assertEquals(3, domain.repNumber)
        assertEquals(1.2f, domain.peakVelocity.value, 0.001f)
        assertEquals(0.8f, domain.avgVelocity.value, 0.001f)
        assertEquals(2.5f, domain.timeUnderTension, 0.001f)
        assertEquals(0.6f, domain.rangeOfMotion.value, 0.001f)
        assertEquals(0.2f, domain.startPosition.value, 0.001f)
        assertEquals(0.8f, domain.endPosition.value, 0.001f)
        assertEquals(1.5f, domain.eccentricDuration, 0.001f)
        assertEquals(1.0f, domain.concentricDuration, 0.001f)
        assertEquals(velocityProfile, domain.velocityProfile)
        assertEquals(positionProfile, domain.positionProfile)
        assertEquals(timestamp, domain.timestamp)
    }

    @Test
    fun `should convert entity with first rep number`() {
        // Given
        val entity = createTestEntity(repNumber = 1)

        // When
        val domain = mapper.toDomain(entity)

        // Then
        assertEquals(1, domain.repNumber)
    }

    @Test
    fun `should convert entity with high rep number`() {
        // Given
        val entity = createTestEntity(repNumber = 20)

        // When
        val domain = mapper.toDomain(entity)

        // Then
        assertEquals(20, domain.repNumber)
    }

    @Test
    fun `should convert entity with empty velocity profile`() {
        // Given
        val entity = createTestEntity(velocityProfile = emptyList())

        // When
        val domain = mapper.toDomain(entity)

        // Then
        assertEquals(0, domain.velocityProfile.size)
    }

    @Test
    fun `should convert entity with empty position profile`() {
        // Given
        val entity = createTestEntity(positionProfile = emptyList())

        // When
        val domain = mapper.toDomain(entity)

        // Then
        assertEquals(0, domain.positionProfile.size)
    }

    @Test
    fun `should convert entity list to domain list`() {
        // Given
        val entities = listOf(
            createTestEntity(id = 1L, repNumber = 1),
            createTestEntity(id = 2L, repNumber = 2),
            createTestEntity(id = 3L, repNumber = 3)
        )

        // When
        val domains = mapper.toDomainList(entities)

        // Then
        assertEquals(3, domains.size)
        assertEquals(1, domains[0].repNumber)
        assertEquals(2, domains[1].repNumber)
        assertEquals(3, domains[2].repNumber)
    }

    @Test
    fun `should handle empty entity list`() {
        // Given
        val entities = emptyList<RepEntity>()

        // When
        val domains = mapper.toDomainList(entities)

        // Then
        assertEquals(0, domains.size)
    }

    // Domain to Entity Tests
    @Test
    fun `should convert domain to entity correctly`() {
        // Given
        val timestamp = System.currentTimeMillis()
        val velocityProfile = listOf(
            VelocityProfile(timestamp = timestamp, velocity = 0.6f),
            VelocityProfile(timestamp = timestamp + 200, velocity = 1.1f)
        )
        val positionProfile = listOf(
            PositionProfile(timestamp = timestamp, position = 0.2f),
            PositionProfile(timestamp = timestamp + 200, position = 0.6f)
        )

        val domain = Rep(
            id = 789L,
            setId = 321L,
            repNumber = 5,
            peakVelocity = MetersPerSecond(1.3f),
            avgVelocity = MetersPerSecond(0.9f),
            timeUnderTension = 3.0f,
            rangeOfMotion = Meters(0.7f),
            startPosition = Meters(0.3f),
            endPosition = Meters(1.0f),
            eccentricDuration = 1.8f,
            concentricDuration = 1.2f,
            velocityProfile = velocityProfile,
            positionProfile = positionProfile,
            timestamp = timestamp
        )

        // When
        val entity = mapper.toEntity(domain)

        // Then
        assertEquals(789L, entity.id)
        assertEquals(321L, entity.setId)
        assertEquals(5, entity.repNumber)
        assertEquals(1.3f, entity.peakVelocity, 0.001f)
        assertEquals(0.9f, entity.avgVelocity, 0.001f)
        assertEquals(3.0f, entity.timeUnderTension, 0.001f)
        assertEquals(0.7f, entity.rangeOfMotion, 0.001f)
        assertEquals(0.3f, entity.startPosition, 0.001f)
        assertEquals(1.0f, entity.endPosition, 0.001f)
        assertEquals(1.8f, entity.eccentricDuration, 0.001f)
        assertEquals(1.2f, entity.concentricDuration, 0.001f)
        assertEquals(velocityProfile, entity.velocityProfile)
        assertEquals(positionProfile, entity.positionProfile)
        assertEquals(timestamp, entity.timestamp)
    }

    // Roundtrip Tests
    @Test
    fun `should maintain data integrity in entity-domain-entity roundtrip`() {
        // Given
        val originalEntity = createTestEntity(
            id = 100L,
            setId = 200L,
            repNumber = 7,
            peakVelocity = 1.15f,
            avgVelocity = 0.75f,
            timeUnderTension = 2.8f,
            rangeOfMotion = 0.65f,
            startPosition = 0.25f,
            endPosition = 0.9f,
            eccentricDuration = 1.6f,
            concentricDuration = 1.2f
        )

        // When
        val domain = mapper.toDomain(originalEntity)
        val finalEntity = mapper.toEntity(domain)

        // Then
        assertEquals(originalEntity.id, finalEntity.id)
        assertEquals(originalEntity.setId, finalEntity.setId)
        assertEquals(originalEntity.repNumber, finalEntity.repNumber)
        assertEquals(originalEntity.peakVelocity, finalEntity.peakVelocity, 0.001f)
        assertEquals(originalEntity.avgVelocity, finalEntity.avgVelocity, 0.001f)
        assertEquals(originalEntity.timeUnderTension, finalEntity.timeUnderTension, 0.001f)
        assertEquals(originalEntity.rangeOfMotion, finalEntity.rangeOfMotion, 0.001f)
        assertEquals(originalEntity.startPosition, finalEntity.startPosition, 0.001f)
        assertEquals(originalEntity.endPosition, finalEntity.endPosition, 0.001f)
        assertEquals(originalEntity.eccentricDuration, finalEntity.eccentricDuration, 0.001f)
        assertEquals(originalEntity.concentricDuration, finalEntity.concentricDuration, 0.001f)
        assertEquals(originalEntity.velocityProfile, finalEntity.velocityProfile)
        assertEquals(originalEntity.positionProfile, finalEntity.positionProfile)
        assertEquals(originalEntity.timestamp, finalEntity.timestamp)
    }

    @Test
    fun `should maintain data integrity in domain-entity-domain roundtrip`() {
        // Given
        val timestamp = System.currentTimeMillis()
        val velocityProfile = listOf(VelocityProfile(timestamp = timestamp, velocity = 1.0f))
        val positionProfile = listOf(PositionProfile(timestamp = timestamp, position = 0.5f))

        val originalDomain = Rep(
            id = 300L,
            setId = 400L,
            repNumber = 10,
            peakVelocity = MetersPerSecond(1.4f),
            avgVelocity = MetersPerSecond(0.95f),
            timeUnderTension = 3.2f,
            rangeOfMotion = Meters(0.75f),
            startPosition = Meters(0.35f),
            endPosition = Meters(1.1f),
            eccentricDuration = 2.0f,
            concentricDuration = 1.2f,
            velocityProfile = velocityProfile,
            positionProfile = positionProfile,
            timestamp = timestamp
        )

        // When
        val entity = mapper.toEntity(originalDomain)
        val finalDomain = mapper.toDomain(entity)

        // Then
        assertEquals(originalDomain.id, finalDomain.id)
        assertEquals(originalDomain.setId, finalDomain.setId)
        assertEquals(originalDomain.repNumber, finalDomain.repNumber)
        assertEquals(originalDomain.peakVelocity.value, finalDomain.peakVelocity.value, 0.001f)
        assertEquals(originalDomain.avgVelocity.value, finalDomain.avgVelocity.value, 0.001f)
        assertEquals(originalDomain.timeUnderTension, finalDomain.timeUnderTension, 0.001f)
        assertEquals(originalDomain.rangeOfMotion.value, finalDomain.rangeOfMotion.value, 0.001f)
        assertEquals(originalDomain.startPosition.value, finalDomain.startPosition.value, 0.001f)
        assertEquals(originalDomain.endPosition.value, finalDomain.endPosition.value, 0.001f)
        assertEquals(originalDomain.eccentricDuration, finalDomain.eccentricDuration, 0.001f)
        assertEquals(originalDomain.concentricDuration, finalDomain.concentricDuration, 0.001f)
        assertEquals(originalDomain.velocityProfile, finalDomain.velocityProfile)
        assertEquals(originalDomain.positionProfile, finalDomain.positionProfile)
        assertEquals(originalDomain.timestamp, finalDomain.timestamp)
    }

    // Edge Cases
    @Test
    fun `should handle zero time under tension`() {
        // Given
        val entity = createTestEntity(timeUnderTension = 0f)

        // When
        val domain = mapper.toDomain(entity)

        // Then
        assertEquals(0f, domain.timeUnderTension, 0.001f)
    }

    @Test
    fun `should handle zero range of motion`() {
        // Given
        val entity = createTestEntity(rangeOfMotion = 0f)

        // When
        val domain = mapper.toDomain(entity)

        // Then
        assertEquals(0f, domain.rangeOfMotion.value, 0.001f)
    }

    @Test
    fun `should handle equal start and end positions`() {
        // Given
        val entity = createTestEntity(startPosition = 0.5f, endPosition = 0.5f)

        // When
        val domain = mapper.toDomain(entity)

        // Then
        assertEquals(0.5f, domain.startPosition.value, 0.001f)
        assertEquals(0.5f, domain.endPosition.value, 0.001f)
    }

    @Test
    fun `should handle very fast rep`() {
        // Given - rep completed in less than a second
        val entity = createTestEntity(
            eccentricDuration = 0.3f,
            concentricDuration = 0.2f,
            timeUnderTension = 0.5f
        )

        // When
        val domain = mapper.toDomain(entity)

        // Then
        assertEquals(0.3f, domain.eccentricDuration, 0.001f)
        assertEquals(0.2f, domain.concentricDuration, 0.001f)
        assertEquals(0.5f, domain.timeUnderTension, 0.001f)
    }

    @Test
    fun `should handle very slow rep`() {
        // Given - slow tempo rep
        val entity = createTestEntity(
            eccentricDuration = 5.0f,
            concentricDuration = 3.0f,
            timeUnderTension = 8.0f
        )

        // When
        val domain = mapper.toDomain(entity)

        // Then
        assertEquals(5.0f, domain.eccentricDuration, 0.001f)
        assertEquals(3.0f, domain.concentricDuration, 0.001f)
        assertEquals(8.0f, domain.timeUnderTension, 0.001f)
    }

    @Test
    fun `should handle large velocity profile`() {
        // Given - many data points
        val baseTimestamp = System.currentTimeMillis()
        val largeProfile = (1..100).map {
            VelocityProfile(timestamp = baseTimestamp + it * 10L, velocity = it / 50f)
        }
        val entity = createTestEntity(velocityProfile = largeProfile)

        // When
        val domain = mapper.toDomain(entity)

        // Then
        assertEquals(100, domain.velocityProfile.size)
    }

    // Helper function
    private fun createTestEntity(
        id: Long = 1L,
        setId: Long = 1L,
        repNumber: Int = 1,
        peakVelocity: Float = 1.0f,
        avgVelocity: Float = 0.7f,
        timeUnderTension: Float = 2.0f,
        rangeOfMotion: Float = 0.5f,
        startPosition: Float = 0.2f,
        endPosition: Float = 0.7f,
        eccentricDuration: Float = 1.2f,
        concentricDuration: Float = 0.8f,
        velocityProfile: List<VelocityProfile> = emptyList(),
        positionProfile: List<PositionProfile> = emptyList(),
        timestamp: Long = System.currentTimeMillis()
    ): RepEntity {
        return RepEntity(
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
