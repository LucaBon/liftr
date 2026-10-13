package com.liftr.domain.repository

import kotlinx.coroutines.flow.Flow

interface CustomExerciseRepository {
    fun getExerciseNames(): Flow<List<String>>
    suspend fun addExerciseName(name: String)
}
