package com.liftr.data.local.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workouts")
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val date: Long,
    val duration: Long,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
