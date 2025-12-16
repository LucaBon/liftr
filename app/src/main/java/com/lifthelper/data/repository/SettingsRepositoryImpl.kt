package com.lifthelper.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lifthelper.domain.error.LiftHelperError
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.repository.SettingsRepository
import com.lifthelper.domain.util.mapError
import com.lifthelper.domain.util.onError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of SettingsRepository.
 *
 * Provides data access for general application settings using DataStore preferences.
 * Handles error handling with Result<T>.
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val logger: Logger
) : SettingsRepository {

    companion object {
        private const val TAG = "SettingsRepository"
    }

    override fun getBooleanSetting(key: String, defaultValue: Boolean): Flow<Boolean> {
        val prefKey = booleanPreferencesKey(key)
        return dataStore.data.map { preferences ->
            preferences[prefKey] ?: defaultValue
        }
    }

    override suspend fun saveBooleanSetting(key: String, value: Boolean): Result<Unit> = runCatching {
        val prefKey = booleanPreferencesKey(key)
        dataStore.edit { preferences ->
            preferences[prefKey] = value
        }
        logger.d(TAG, "Boolean setting saved: $key = $value")
        Unit
    }.mapError { error ->
        when (error) {
            is IOException -> LiftHelperError.DatabaseError(
                "Failed to save boolean setting: $key",
                error
            )
            is LiftHelperError -> error
            else -> LiftHelperError.DatabaseError(
                "Unexpected error saving boolean setting: $key",
                error
            )
        }
    }.onError { error ->
        logger.e(TAG, "Save boolean setting failed: ${error.message}", error)
    }

    override fun getFloatSetting(key: String, defaultValue: Float): Flow<Float> {
        val prefKey = floatPreferencesKey(key)
        return dataStore.data.map { preferences ->
            preferences[prefKey] ?: defaultValue
        }
    }

    override suspend fun saveFloatSetting(key: String, value: Float): Result<Unit> = runCatching {
        val prefKey = floatPreferencesKey(key)
        dataStore.edit { preferences ->
            preferences[prefKey] = value
        }
        logger.d(TAG, "Float setting saved: $key = $value")
        Unit
    }.mapError { error ->
        when (error) {
            is IOException -> LiftHelperError.DatabaseError(
                "Failed to save float setting: $key",
                error
            )
            is LiftHelperError -> error
            else -> LiftHelperError.DatabaseError(
                "Unexpected error saving float setting: $key",
                error
            )
        }
    }.onError { error ->
        logger.e(TAG, "Save float setting failed: ${error.message}", error)
    }

    override fun getStringSetting(key: String, defaultValue: String): Flow<String> {
        val prefKey = stringPreferencesKey(key)
        return dataStore.data.map { preferences ->
            preferences[prefKey] ?: defaultValue
        }
    }

    override suspend fun saveStringSetting(key: String, value: String): Result<Unit> = runCatching {
        val prefKey = stringPreferencesKey(key)
        dataStore.edit { preferences ->
            preferences[prefKey] = value
        }
        logger.d(TAG, "String setting saved: $key = $value")
        Unit
    }.mapError { error ->
        when (error) {
            is IOException -> LiftHelperError.DatabaseError(
                "Failed to save string setting: $key",
                error
            )
            is LiftHelperError -> error
            else -> LiftHelperError.DatabaseError(
                "Unexpected error saving string setting: $key",
                error
            )
        }
    }.onError { error ->
        logger.e(TAG, "Save string setting failed: ${error.message}", error)
    }

    override suspend fun clearAllSettings(): Result<Unit> = runCatching {
        dataStore.edit { preferences ->
            preferences.clear()
        }
        logger.d(TAG, "All settings cleared successfully")
        Unit
    }.mapError { error ->
        when (error) {
            is IOException -> LiftHelperError.DatabaseError(
                "Failed to clear all settings",
                error
            )
            is LiftHelperError -> error
            else -> LiftHelperError.DatabaseError(
                "Unexpected error clearing all settings",
                error
            )
        }
    }.onError { error ->
        logger.e(TAG, "Clear all settings failed: ${error.message}", error)
    }
}
