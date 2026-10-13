package com.liftr.data.mapper

import com.liftr.data.local.database.entities.RepEntity
import com.liftr.domain.model.Rep
import com.liftr.domain.value.Meters
import com.liftr.domain.value.MetersPerSecond
import javax.inject.Inject

/**
 * Mapper for converting between RepEntity (database) and Rep (domain).
 *
 * Handles transformation of rep data between the data and domain layers.
 */
class RepMapper @Inject constructor() {

    /**
     * Converts a RepEntity to a Rep domain model.
     *
     * @param entity The database entity to convert
     * @return The domain model representation
     */
    fun toDomain(entity: RepEntity): Rep {
        return Rep(
            id = entity.id,
            setId = entity.setId,
            repNumber = entity.repNumber,
            peakVelocity = MetersPerSecond(entity.peakVelocity),
            avgVelocity = MetersPerSecond(entity.avgVelocity),
            timeUnderTension = entity.timeUnderTension,
            rangeOfMotion = Meters(entity.rangeOfMotion),
            startPosition = Meters(entity.startPosition),
            endPosition = Meters(entity.endPosition),
            eccentricDuration = entity.eccentricDuration,
            concentricDuration = entity.concentricDuration,
            velocityProfile = entity.velocityProfile,
            positionProfile = entity.positionProfile,
            timestamp = entity.timestamp,
            qualityScore = entity.qualityScore
        )
    }

    /**
     * Converts a list of RepEntities to domain models.
     *
     * @param entities The list of database entities to convert
     * @return The list of domain model representations
     */
    fun toDomainList(entities: List<RepEntity>): List<Rep> {
        return entities.map { toDomain(it) }
    }

    /**
     * Converts a Rep domain model to a RepEntity.
     *
     * @param domain The domain model to convert
     * @return The database entity representation
     */
    fun toEntity(domain: Rep): RepEntity {
        return RepEntity(
            id = domain.id,
            setId = domain.setId,
            repNumber = domain.repNumber,
            peakVelocity = domain.peakVelocity.value,
            avgVelocity = domain.avgVelocity.value,
            timeUnderTension = domain.timeUnderTension,
            rangeOfMotion = domain.rangeOfMotion.value,
            startPosition = domain.startPosition.value,
            endPosition = domain.endPosition.value,
            eccentricDuration = domain.eccentricDuration,
            concentricDuration = domain.concentricDuration,
            velocityProfile = domain.velocityProfile,
            positionProfile = domain.positionProfile,
            timestamp = domain.timestamp,
            qualityScore = domain.qualityScore
        )
    }
}
