package com.lifthelper.data.mapper

import com.lifthelper.data.local.database.entities.WorkoutEntity
import com.lifthelper.domain.model.Workout
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Date

class WorkoutMapperTest {

    private lateinit var mapper: WorkoutMapper

    @Before
    fun setup() {
        mapper = WorkoutMapper()
    }

    // Entity to Domain Tests
    @Test
    fun `should convert entity to domain correctly`() {
        // Given
        val timestamp = System.currentTimeMillis()
        val entity = WorkoutEntity(
            id = 123L,
            date = timestamp,
            duration = 3600000L,  // 1 hour
            notes = "Test workout",
            createdAt = timestamp
        )

        // When
        val domain = mapper.toDomain(entity)

        // Then
        assertEquals(123L, domain.id)
        assertEquals(timestamp, domain.date.time)
        assertEquals(3600000L, domain.duration)
        assertEquals("Test workout", domain.notes)
        assertEquals(timestamp, domain.createdAt)
        assertEquals(emptyList<com.lifthelper.domain.model.Set>(), domain.sets)
    }

    @Test
    fun `should convert entity to domain with empty notes`() {
        // Given
        val entity = WorkoutEntity(
            id = 1L,
            date = System.currentTimeMillis(),
            duration = 1000L,
            notes = "",
            createdAt = System.currentTimeMillis()
        )

        // When
        val domain = mapper.toDomain(entity)

        // Then
        assertEquals("", domain.notes)
    }

    @Test
    fun `should convert entity to domain with provided sets`() {
        // Given
        val entity = WorkoutEntity(
            id = 1L,
            date = System.currentTimeMillis(),
            duration = 1000L,
            createdAt = System.currentTimeMillis()
        )
        val sets = listOf(
            createTestSet(id = 1L),
            createTestSet(id = 2L)
        )

        // When
        val domain = mapper.toDomain(entity, sets)

        // Then
        assertEquals(2, domain.sets.size)
        assertEquals(sets, domain.sets)
    }

    @Test
    fun `should convert entity list to domain list`() {
        // Given
        val entities = listOf(
            WorkoutEntity(id = 1L, date = 1000L, duration = 1000L, createdAt = 1000L),
            WorkoutEntity(id = 2L, date = 2000L, duration = 2000L, createdAt = 2000L),
            WorkoutEntity(id = 3L, date = 3000L, duration = 3000L, createdAt = 3000L)
        )

        // When
        val domains = mapper.toDomainList(entities)

        // Then
        assertEquals(3, domains.size)
        assertEquals(1L, domains[0].id)
        assertEquals(2L, domains[1].id)
        assertEquals(3L, domains[2].id)
    }

    @Test
    fun `should handle empty entity list`() {
        // Given
        val entities = emptyList<WorkoutEntity>()

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
        val domain = Workout(
            id = 456L,
            date = Date(timestamp),
            duration = 7200000L,  // 2 hours
            notes = "Heavy squats",
            sets = emptyList(),
            createdAt = timestamp
        )

        // When
        val entity = mapper.toEntity(domain)

        // Then
        assertEquals(456L, entity.id)
        assertEquals(timestamp, entity.date)
        assertEquals(7200000L, entity.duration)
        assertEquals("Heavy squats", entity.notes)
        assertEquals(timestamp, entity.createdAt)
    }

    @Test
    fun `should convert domain to entity with empty notes`() {
        // Given
        val domain = Workout(
            id = 1L,
            date = Date(),
            duration = 1000L,
            notes = "",
            sets = emptyList()
        )

        // When
        val entity = mapper.toEntity(domain)

        // Then
        assertEquals("", entity.notes)
    }

    @Test
    fun `should preserve id when converting domain to entity`() {
        // Given
        val domain = Workout(
            id = 999L,
            date = Date(),
            duration = 1000L,
            sets = emptyList()
        )

        // When
        val entity = mapper.toEntity(domain)

        // Then
        assertEquals(999L, entity.id)
    }

    // Roundtrip Tests
    @Test
    fun `should maintain data integrity in entity-domain-entity roundtrip`() {
        // Given
        val originalEntity = WorkoutEntity(
            id = 100L,
            date = 1234567890L,
            duration = 5000L,
            notes = "Test notes",
            createdAt = 9876543210L
        )

        // When
        val domain = mapper.toDomain(originalEntity)
        val finalEntity = mapper.toEntity(domain)

        // Then
        assertEquals(originalEntity.id, finalEntity.id)
        assertEquals(originalEntity.date, finalEntity.date)
        assertEquals(originalEntity.duration, finalEntity.duration)
        assertEquals(originalEntity.notes, finalEntity.notes)
        assertEquals(originalEntity.createdAt, finalEntity.createdAt)
    }

    @Test
    fun `should maintain data integrity in domain-entity-domain roundtrip`() {
        // Given
        val originalDomain = Workout(
            id = 200L,
            date = Date(1234567890L),
            duration = 6000L,
            notes = "Original notes",
            sets = emptyList(),
            createdAt = 9876543210L
        )

        // When
        val entity = mapper.toEntity(originalDomain)
        val finalDomain = mapper.toDomain(entity)

        // Then
        assertEquals(originalDomain.id, finalDomain.id)
        assertEquals(originalDomain.date, finalDomain.date)
        assertEquals(originalDomain.duration, finalDomain.duration)
        assertEquals(originalDomain.notes, finalDomain.notes)
        assertEquals(originalDomain.createdAt, finalDomain.createdAt)
    }

    // Edge Cases
    @Test
    fun `should handle zero duration`() {
        // Given
        val entity = WorkoutEntity(
            id = 1L,
            date = System.currentTimeMillis(),
            duration = 0L,
            createdAt = System.currentTimeMillis()
        )

        // When
        val domain = mapper.toDomain(entity)

        // Then
        assertEquals(0L, domain.duration)
    }

    @Test
    fun `should handle very long workout duration`() {
        // Given
        val longDuration = 86400000L  // 24 hours
        val entity = WorkoutEntity(
            id = 1L,
            date = System.currentTimeMillis(),
            duration = longDuration,
            createdAt = System.currentTimeMillis()
        )

        // When
        val domain = mapper.toDomain(entity)

        // Then
        assertEquals(longDuration, domain.duration)
    }

    // Helper function
    private fun createTestSet(id: Long = 1L): com.lifthelper.domain.model.Set {
        return com.lifthelper.domain.model.Set(
            id = id,
            workoutId = 0,
            exerciseType = com.lifthelper.domain.model.ExerciseType.BACK_SQUAT,
            weight = com.lifthelper.domain.value.Kilograms(100f),
            targetReps = com.lifthelper.domain.value.RepCount(5),
            completedReps = com.lifthelper.domain.value.RepCount(5),
            avgVelocity = com.lifthelper.domain.value.MetersPerSecond(0.6f),
            peakVelocity = com.lifthelper.domain.value.MetersPerSecond(1.0f),
            timestamp = System.currentTimeMillis()
        )
    }
}
