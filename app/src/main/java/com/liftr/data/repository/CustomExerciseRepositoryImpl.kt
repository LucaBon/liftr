package com.liftr.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.liftr.domain.repository.CustomExerciseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CustomExerciseRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : CustomExerciseRepository {

    companion object {
        private val KEY_CUSTOM_EXERCISES = stringPreferencesKey("custom_exercise_names")

        val DEFAULT_ACCESSORIES = listOf(
            "Barbell Curl",
            "Dumbbell Curl",
            "Hammer Curl",
            "Lat Pulldown",
            "Cable Row",
            "Dumbbell Row",
            "Dumbbell Press",
            "Overhead Press",
            "Lateral Raise",
            "Tricep Extension",
            "Tricep Pushdown",
            "Leg Press",
            "Leg Curl",
            "Leg Extension",
            "Calf Raise",
            "Romanian Deadlift",
            "Hip Thrust",
            "Face Pull",
            "Pull Up",
            "Dip"
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    override fun getExerciseNames(): Flow<List<String>> {
        return dataStore.data.map { preferences ->
            val stored = preferences[KEY_CUSTOM_EXERCISES]
            if (stored != null) {
                try {
                    json.decodeFromString<List<String>>(stored)
                } catch (_: Exception) {
                    DEFAULT_ACCESSORIES
                }
            } else {
                DEFAULT_ACCESSORIES
            }
        }
    }

    override suspend fun addExerciseName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return

        dataStore.edit { preferences ->
            val stored = preferences[KEY_CUSTOM_EXERCISES]
            val current = if (stored == null) {
                DEFAULT_ACCESSORIES
            } else {
                try {
                    json.decodeFromString<List<String>>(stored)
                } catch (_: Exception) {
                    // Do NOT fall back to the defaults here. Writing
                    // defaults + the new name would overwrite the stored value
                    // and destroy the user's list for good, turning a transient
                    // read problem into permanent data loss. Leave the stored
                    // value untouched and skip this addition instead.
                    return@edit
                }
            }

            // Case-insensitive deduplication
            if (current.none { it.equals(trimmed, ignoreCase = true) }) {
                val updated = current + trimmed
                preferences[KEY_CUSTOM_EXERCISES] = json.encodeToString(updated)
            }
        }
    }
}
