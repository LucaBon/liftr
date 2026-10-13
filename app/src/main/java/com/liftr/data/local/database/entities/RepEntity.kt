package com.liftr.data.local.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.liftr.domain.model.PositionProfile
import com.liftr.domain.model.VelocityProfile

@Entity(
    tableName = "reps",
    foreignKeys = [
        ForeignKey(
            entity = SetEntity::class,
            parentColumns = ["id"],
            childColumns = ["setId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("setId")]
)
data class RepEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val setId: Long,
    val repNumber: Int,
    val peakVelocity: Float,
    val avgVelocity: Float,
    val timeUnderTension: Float,
    val rangeOfMotion: Float,
    val startPosition: Float,
    val endPosition: Float,
    val eccentricDuration: Float,
    val concentricDuration: Float,
    val velocityProfile: List<VelocityProfile>, // Full frame-by-frame data
    val positionProfile: List<PositionProfile>, // Full position tracking
    val timestamp: Long,
    val qualityScore: Int = 0 // Rep quality score (0-100), 0 = not scored
)
