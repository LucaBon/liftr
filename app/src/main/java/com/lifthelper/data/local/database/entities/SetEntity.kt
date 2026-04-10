package com.lifthelper.data.local.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lifthelper.domain.model.ExerciseType

@Entity(
    tableName = "sets",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutEntity::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("workoutId")]
)
data class SetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val workoutId: Long,
    val exerciseType: ExerciseType,
    val weight: Float,
    val targetReps: Int,
    val completedReps: Int,
    val avgVelocity: Float,
    val peakVelocity: Float,
    val avgPower: Float? = null,
    val jumpHeight: Float? = null,
    val timestamp: Long,
    val notes: String = "",
    val isManual: Boolean = false,
    val exerciseName: String? = null
)
