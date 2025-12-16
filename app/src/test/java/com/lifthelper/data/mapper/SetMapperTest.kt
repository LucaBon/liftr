package com.lifthelper.data.mapper

import com.lifthelper.data.local.database.entities.SetEntity
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.Set
import com.lifthelper.domain.value.Kilograms
import com.lifthelper.domain.value.Meters
import com.lifthelper.domain.value.MetersPerSecond
import com.lifthelper.domain.value.RepCount
import com.lifthelper.domain.value.Watts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SetMapperTest {

    private lateinit var mapper: SetMapper

    @Before
    fun setup() {
        mapper = SetMapper()
    }

    // Entity to Domain Tests
    @Test
    fun `should convert entity to domain correctly`() {
        // Given
        val timestamp = System.currentTimeMillis()
        val entity = SetEntity(
            id = 123L,
            workoutId = 456L,
            exerciseType = ExerciseType.BACK_SQUAT,
            weight = 100f,
            targetReps = 5,
            completedReps = 5,
            avgVelocity = 0.6f,
            peakVelocity = 1.0f,
            avgPower = 500f,
            jumpHeight = 0.3f,
            timestamp = timestamp,
            notes = "Good form"
        )

        // When
        val domain = mapper.toDomain(entity)

        // Then
        assertEquals(123L, domain.id)
        assertEquals(456L, domain.workoutId)
        assertEquals(ExerciseType.BACK_SQUAT, domain.exerciseType)
        assertEquals(100f, domain.weight.value, 0.001f)
        assertEquals(5, domain.targetReps.value)
        assertEquals(5, domain.completedReps.value)
        assertEquals(0.6f, domain.avgVelocity.value, 0.001f)
        assertEquals(1.0f, domain.peakVelocity.value, 0.001f)
        assertEquals(500f, domain.avgPower?.value ?: 0f, 0.001f)
        assertEquals(0.3f, domain.jumpHeight?.value ?: 0f, 0.001f)
        assertEquals(timestamp, domain.timestamp)
        assertEquals("Good form", domain.notes)
        assertEquals(emptyList<com.lifthelper.domain.model.Rep>(), domain.reps)
    }

    @Test
    fun `should convert entity to domain with null optional fields`() {
        // Given
        val entity = SetEntity(
            id = 1L,
            workoutId = 1L,
            exerciseType = ExerciseType.FRONT_SQUAT,
            weight = 80f,
            targetReps = 3,
            completedReps = 3,
            avgVelocity = 0.5f,
            peakVelocity = 0.8f,
            avgPower = null,  // Optional
            jumpHeight = null,  // Optional
            timestamp = System.currentTimeMillis(),
            notes = ""
        )

        // When
        val domain = mapper.toDomain(entity)

        // Then
        assertNull(domain.avgPower)
        assertNull(domain.jumpHeight)
        assertEquals("", domain.notes)
    }

    @Test
    fun `should convert entity to domain with different exercise types`() {
        // Given - test each exercise type
        val exerciseTypes = listOf(
            ExerciseType.BACK_SQUAT,
            ExerciseType.FRONT_SQUAT
        )

        exerciseTypes.forEach { exerciseType ->
            // Given
            val entity = createTestEntity(exerciseType = exerciseType)

            // When
            val domain = mapper.toDomain(entity)

            // Then
            assertEquals(exerciseType, domain.exerciseType)
        }
    }

    @Test
    fun `should convert entity list to domain list`() {
        // Given
        val entities = listOf(
            createTestEntity(id = 1L, weight = 100f),
            createTestEntity(id = 2L, weight = 120f),
            createTestEntity(id = 3L, weight = 140f)
        )

        // When
        val domains = mapper.toDomainList(entities)

        // Then
        assertEquals(3, domains.size)
        assertEquals(100f, domains[0].weight.value, 0.001f)
        assertEquals(120f, domains[1].weight.value, 0.001f)
        assertEquals(140f, domains[2].weight.value, 0.001f)
    }

    @Test
    fun `should handle empty entity list`() {
        // Given
        val entities = emptyList<SetEntity>()

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
        val domain = Set(
            id = 789L,
            workoutId = 123L,
            exerciseType = ExerciseType.FRONT_SQUAT,
            weight = Kilograms(90f),
            targetReps = RepCount(8),
            completedReps = RepCount(7),
            avgVelocity = MetersPerSecond(0.7f),
            peakVelocity = MetersPerSecond(1.2f),
            avgPower = Watts(600f),
            jumpHeight = Meters(0.4f),
            timestamp = timestamp,
            notes = "Challenging",
            reps = emptyList()
        )

        // When
        val entity = mapper.toEntity(domain)

        // Then
        assertEquals(789L, entity.id)
        assertEquals(123L, entity.workoutId)
        assertEquals(ExerciseType.FRONT_SQUAT, entity.exerciseType)
        assertEquals(90f, entity.weight, 0.001f)
        assertEquals(8, entity.targetReps)
        assertEquals(7, entity.completedReps)
        assertEquals(0.7f, entity.avgVelocity, 0.001f)
        assertEquals(1.2f, entity.peakVelocity, 0.001f)
        assertEquals(600f, entity.avgPower ?: 0f, 0.001f)
        assertEquals(0.4f, entity.jumpHeight ?: 0f, 0.001f)
        assertEquals(timestamp, entity.timestamp)
        assertEquals("Challenging", entity.notes)
    }

    @Test
    fun `should convert domain to entity with null optional fields`() {
        // Given
        val domain = Set(
            id = 1L,
            workoutId = 1L,
            exerciseType = ExerciseType.BACK_SQUAT,
            weight = Kilograms(100f),
            targetReps = RepCount(5),
            completedReps = RepCount(5),
            avgVelocity = MetersPerSecond(0.6f),
            peakVelocity = MetersPerSecond(1.0f),
            avgPower = null,
            jumpHeight = null,
            timestamp = System.currentTimeMillis(),
            notes = "",
            reps = emptyList()
        )

        // When
        val entity = mapper.toEntity(domain)

        // Then
        assertNull(entity.avgPower)
        assertNull(entity.jumpHeight)
    }

    // Roundtrip Tests
    @Test
    fun `should maintain data integrity in entity-domain-entity roundtrip`() {
        // Given
        val originalEntity = SetEntity(
            id = 100L,
            workoutId = 200L,
            exerciseType = ExerciseType.BACK_SQUAT,
            weight = 120f,
            targetReps = 10,
            completedReps = 9,
            avgVelocity = 0.65f,
            peakVelocity = 1.1f,
            avgPower = 550f,
            jumpHeight = 0.35f,
            timestamp = 1234567890L,
            notes = "Test"
        )

        // When
        val domain = mapper.toDomain(originalEntity)
        val finalEntity = mapper.toEntity(domain)

        // Then
        assertEquals(originalEntity.id, finalEntity.id)
        assertEquals(originalEntity.workoutId, finalEntity.workoutId)
        assertEquals(originalEntity.exerciseType, finalEntity.exerciseType)
        assertEquals(originalEntity.weight, finalEntity.weight, 0.001f)
        assertEquals(originalEntity.targetReps, finalEntity.targetReps)
        assertEquals(originalEntity.completedReps, finalEntity.completedReps)
        assertEquals(originalEntity.avgVelocity, finalEntity.avgVelocity, 0.001f)
        assertEquals(originalEntity.peakVelocity, finalEntity.peakVelocity, 0.001f)
        assertEquals(originalEntity.avgPower, finalEntity.avgPower)
        assertEquals(originalEntity.jumpHeight, finalEntity.jumpHeight)
        assertEquals(originalEntity.timestamp, finalEntity.timestamp)
        assertEquals(originalEntity.notes, finalEntity.notes)
    }

    @Test
    fun `should maintain data integrity in domain-entity-domain roundtrip`() {
        // Given
        val originalDomain = Set(
            id = 300L,
            workoutId = 400L,
            exerciseType = ExerciseType.FRONT_SQUAT,
            weight = Kilograms(95f),
            targetReps = RepCount(6),
            completedReps = RepCount(6),
            avgVelocity = MetersPerSecond(0.55f),
            peakVelocity = MetersPerSecond(0.95f),
            avgPower = Watts(450f),
            jumpHeight = Meters(0.25f),
            timestamp = 9876543210L,
            notes = "Perfect",
            reps = emptyList()
        )

        // When
        val entity = mapper.toEntity(originalDomain)
        val finalDomain = mapper.toDomain(entity)

        // Then
        assertEquals(originalDomain.id, finalDomain.id)
        assertEquals(originalDomain.workoutId, finalDomain.workoutId)
        assertEquals(originalDomain.exerciseType, finalDomain.exerciseType)
        assertEquals(originalDomain.weight.value, finalDomain.weight.value, 0.001f)
        assertEquals(originalDomain.targetReps.value, finalDomain.targetReps.value)
        assertEquals(originalDomain.completedReps.value, finalDomain.completedReps.value)
        assertEquals(originalDomain.avgVelocity.value, finalDomain.avgVelocity.value, 0.001f)
        assertEquals(originalDomain.peakVelocity.value, finalDomain.peakVelocity.value, 0.001f)
        assertEquals(originalDomain.avgPower?.value, finalDomain.avgPower?.value)
        assertEquals(originalDomain.jumpHeight?.value, finalDomain.jumpHeight?.value)
        assertEquals(originalDomain.timestamp, finalDomain.timestamp)
        assertEquals(originalDomain.notes, finalDomain.notes)
    }

    // Edge Cases
    @Test
    fun `should handle zero weight`() {
        // Given
        val entity = createTestEntity(weight = 0f)

        // When
        val domain = mapper.toDomain(entity)

        // Then
        assertEquals(0f, domain.weight.value, 0.001f)
    }

    @Test
    fun `should handle zero velocity`() {
        // Given
        val entity = createTestEntity(avgVelocity = 0f, peakVelocity = 0f)

        // When
        val domain = mapper.toDomain(entity)

        // Then
        assertEquals(0f, domain.avgVelocity.value, 0.001f)
        assertEquals(0f, domain.peakVelocity.value, 0.001f)
    }

    @Test
    fun `should handle incomplete reps`() {
        // Given
        val entity = createTestEntity(targetReps = 10, completedReps = 7)

        // When
        val domain = mapper.toDomain(entity)

        // Then
        assertEquals(10, domain.targetReps.value)
        assertEquals(7, domain.completedReps.value)
    }

    // Helper function
    private fun createTestEntity(
        id: Long = 1L,
        workoutId: Long = 1L,
        exerciseType: ExerciseType = ExerciseType.BACK_SQUAT,
        weight: Float = 100f,
        targetReps: Int = 5,
        completedReps: Int = 5,
        avgVelocity: Float = 0.6f,
        peakVelocity: Float = 1.0f,
        avgPower: Float? = null,
        jumpHeight: Float? = null,
        timestamp: Long = System.currentTimeMillis(),
        notes: String = ""
    ): SetEntity {
        return SetEntity(
            id = id,
            workoutId = workoutId,
            exerciseType = exerciseType,
            weight = weight,
            targetReps = targetReps,
            completedReps = completedReps,
            avgVelocity = avgVelocity,
            peakVelocity = peakVelocity,
            avgPower = avgPower,
            jumpHeight = jumpHeight,
            timestamp = timestamp,
            notes = notes
        )
    }
}
