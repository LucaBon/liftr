package com.liftr.data.mapper

import com.liftr.data.local.database.entities.WorkoutEntity
import com.liftr.domain.model.Workout
import java.util.Date
import javax.inject.Inject

/**
 * Mapper for converting between WorkoutEntity (database) and Workout (domain).
 *
 * Responsible for transforming data between the data layer and domain layer,
 * maintaining separation of concerns and allowing each layer to have its own representation.
 */
class WorkoutMapper @Inject constructor() {

    /**
     * Converts a WorkoutEntity to a Workout domain model.
     *
     * @param entity The database entity to convert
     * @param sets Optional list of sets associated with this workout
     * @return The domain model representation
     */
    fun toDomain(entity: WorkoutEntity, sets: List<com.liftr.domain.model.Set> = emptyList()): Workout {
        return Workout(
            id = entity.id,
            date = Date(entity.date),
            duration = entity.duration,
            notes = entity.notes,
            sets = sets,
            createdAt = entity.createdAt
        )
    }

    /**
     * Converts a list of WorkoutEntities to domain models.
     *
     * @param entities The list of database entities to convert
     * @return The list of domain model representations
     */
    fun toDomainList(entities: List<WorkoutEntity>): List<Workout> {
        return entities.map { toDomain(it) }
    }

    /**
     * Converts a Workout domain model to a WorkoutEntity.
     *
     * @param domain The domain model to convert
     * @return The database entity representation
     */
    fun toEntity(domain: Workout): WorkoutEntity {
        return WorkoutEntity(
            id = domain.id,
            date = domain.date.time,
            duration = domain.duration,
            notes = domain.notes,
            createdAt = domain.createdAt
        )
    }
}
