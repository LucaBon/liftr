package com.liftr.data.local.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.liftr.domain.model.AnthropometricData
import com.liftr.domain.model.CalibrationData
import com.liftr.util.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private val keyUserHeight = floatPreferencesKey(Constants.KEY_USER_HEIGHT)
    private val keyShoulderWidth = floatPreferencesKey(Constants.KEY_SHOULDER_WIDTH)
    private val keyPixelsPerMeter = floatPreferencesKey(Constants.KEY_PIXELS_PER_METER)
    private val keyIsCalibrated = booleanPreferencesKey(Constants.KEY_IS_CALIBRATED)
    private val keyAnthropometricData = stringPreferencesKey("anthropometric_data_json")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val isCalibrated: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[keyIsCalibrated] ?: false
    }

    val calibrationData: Flow<CalibrationData?> = dataStore.data.map { preferences ->
        val height = preferences[keyUserHeight]
        val shoulderWidth = preferences[keyShoulderWidth]
        val pixelsPerMeter = preferences[keyPixelsPerMeter]
        val anthropometricJson = preferences[keyAnthropometricData]

        // Deserialize anthropometric data if present
        val anthropometricData = anthropometricJson?.let {
            try {
                json.decodeFromString<AnthropometricData>(it)
            } catch (e: Exception) {
                null // Failed to parse, ignore
            }
        }

        if (height != null && shoulderWidth != null && pixelsPerMeter != null) {
            CalibrationData(
                pixelsPerMeter = pixelsPerMeter,
                userHeight = height,
                shoulderWidth = shoulderWidth,
                anthropometricData = anthropometricData
            )
        } else {
            null
        }
    }

    suspend fun saveCalibration(calibrationData: CalibrationData) {
        dataStore.edit { preferences ->
            preferences[keyUserHeight] = calibrationData.userHeight
            preferences[keyShoulderWidth] = calibrationData.shoulderWidth
            preferences[keyPixelsPerMeter] = calibrationData.pixelsPerMeter
            preferences[keyIsCalibrated] = true

            // Serialize and save anthropometric data if present
            calibrationData.anthropometricData?.let { anthroData ->
                preferences[keyAnthropometricData] = json.encodeToString(anthroData)
            }
        }
    }

    suspend fun updatePixelsPerMeter(pixelsPerMeter: Float) {
        dataStore.edit { preferences ->
            preferences[keyPixelsPerMeter] = pixelsPerMeter
        }
    }

    suspend fun saveAnthropometricData(anthropometricData: AnthropometricData) {
        dataStore.edit { preferences ->
            // Serialize and save anthropometric data
            preferences[keyAnthropometricData] = json.encodeToString(anthropometricData)

            // Update height in calibration data if it changed
            preferences[keyUserHeight] = anthropometricData.userHeight
        }
    }

    suspend fun clearCalibration() {
        dataStore.edit { preferences ->
            preferences.remove(keyUserHeight)
            preferences.remove(keyShoulderWidth)
            preferences.remove(keyPixelsPerMeter)
            preferences[keyIsCalibrated] = false
            preferences.remove(keyAnthropometricData)
        }
    }
}
