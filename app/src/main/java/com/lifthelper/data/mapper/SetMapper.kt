package com.lifthelper.data.mapper

import com.lifthelper.data.local.database.entities.SetEntity
import com.lifthelper.domain.model.Set
import com.lifthelper.domain.value.Kilograms
import com.lifthelper.domain.value.Meters
import com.lifthelper.domain.value.MetersPerSecond
import com.lifthelper.domain.value.RepCount
import com.lifthelper.domain.value.Watts
import javax.inject.Inject

/**
 * Mapper for converting between SetEntity (database) and Set (domain).
 *
 * Handles transformation of set data between the data and domain layers.
 */
class SetMapper @Inject constructor() {

    /**
     * Converts a SetEntity to a Set domain model.
     *
     * @param entity The database entity to convert
     * @param reps Optional list of reps associated with this set
     * @return The domain model representation
     */
    fun toDomain(entity: SetEntity, reps: List<com.lifthelper.domain.model.Rep> = emptyList()): Set {
        return Set(
            id = entity.id,
            workoutId = entity.workoutId,
            exerciseType = entity.exerciseType,
            weight = Kilograms(entity.weight),
            targetReps = RepCount(entity.targetReps),
            completedReps = RepCount(entity.completedReps),
            avgVelocity = MetersPerSecond(entity.avgVelocity),
            peakVelocity = MetersPerSecond(entity.peakVelocity),
            avgPower = entity.avgPower?.let { Watts(it) },
            jumpHeight = entity.jumpHeight?.let { Meters(it) },
            timestamp = entity.timestamp,
            notes = entity.notes,
            reps = reps,
            isManual = entity.isManual,
            exerciseName = entity.exerciseName
        )
    }

    /**
     * Converts a list of SetEntities to domain models.
     *
     * @param entities The list of database entities to convert
     * @return The list of domain model representations
     */
    fun toDomainList(entities: List<SetEntity>): List<Set> {
        return entities.map { toDomain(it) }
    }

    /**
     * Converts a Set domain model to a SetEntity.
     *
     * @param domain The domain model to convert
     * @return The database entity representation
     */
    fun toEntity(domain: Set): SetEntity {
        return SetEntity(
            id = domain.id,
            workoutId = domain.workoutId,
            exerciseType = domain.exerciseType,
            weight = domain.weight.value,
            targetReps = domain.targetReps.value,
            completedReps = domain.completedReps.value,
            avgVelocity = domain.avgVelocity.value,
            peakVelocity = domain.peakVelocity.value,
            avgPower = domain.avgPower?.value,
            jumpHeight = domain.jumpHeight?.value,
            timestamp = domain.timestamp,
            notes = domain.notes,
            isManual = domain.isManual,
            exerciseName = domain.exerciseName
        )
    }
}
