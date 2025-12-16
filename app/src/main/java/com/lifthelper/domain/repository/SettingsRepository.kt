package com.lifthelper.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for application settings.
 *
 * Manages general application preferences and settings.
 */
interface SettingsRepository {
    /**
     * Observes a boolean setting value.
     *
     * @param key The setting key
     * @param defaultValue The default value if not set
     * @return Flow emitting the setting value
     */
    fun getBooleanSetting(key: String, defaultValue: Boolean = false): Flow<Boolean>

    /**
     * Saves a boolean setting.
     *
     * @param key The setting key
     * @param value The value to save
     * @return Result indicating success or failure
     */
    suspend fun saveBooleanSetting(key: String, value: Boolean): Result<Unit>

    /**
     * Observes a float setting value.
     *
     * @param key The setting key
     * @param defaultValue The default value if not set
     * @return Flow emitting the setting value
     */
    fun getFloatSetting(key: String, defaultValue: Float = 0f): Flow<Float>

    /**
     * Saves a float setting.
     *
     * @param key The setting key
     * @param value The value to save
     * @return Result indicating success or failure
     */
    suspend fun saveFloatSetting(key: String, value: Float): Result<Unit>

    /**
     * Observes a string setting value.
     *
     * @param key The setting key
     * @param defaultValue The default value if not set
     * @return Flow emitting the setting value
     */
    fun getStringSetting(key: String, defaultValue: String = ""): Flow<String>

    /**
     * Saves a string setting.
     *
     * @param key The setting key
     * @param value The value to save
     * @return Result indicating success or failure
     */
    suspend fun saveStringSetting(key: String, value: String): Result<Unit>

    /**
     * Clears all settings.
     *
     * @return Result indicating success or failure
     */
    suspend fun clearAllSettings(): Result<Unit>
}
