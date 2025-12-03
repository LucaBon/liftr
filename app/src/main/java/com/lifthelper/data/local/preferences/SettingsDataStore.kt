package com.lifthelper.data.local.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import com.lifthelper.domain.model.CalibrationData
import com.lifthelper.util.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

    val isCalibrated: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[keyIsCalibrated] ?: false
    }

    val calibrationData: Flow<CalibrationData?> = dataStore.data.map { preferences ->
        val height = preferences[keyUserHeight]
        val shoulderWidth = preferences[keyShoulderWidth]
        val pixelsPerMeter = preferences[keyPixelsPerMeter]

        if (height != null && shoulderWidth != null && pixelsPerMeter != null) {
            CalibrationData(
                pixelsPerMeter = pixelsPerMeter,
                userHeight = height,
                shoulderWidth = shoulderWidth
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
        }
    }

    suspend fun updatePixelsPerMeter(pixelsPerMeter: Float) {
        dataStore.edit { preferences ->
            preferences[keyPixelsPerMeter] = pixelsPerMeter
        }
    }

    suspend fun clearCalibration() {
        dataStore.edit { preferences ->
            preferences.remove(keyUserHeight)
            preferences.remove(keyShoulderWidth)
            preferences.remove(keyPixelsPerMeter)
            preferences[keyIsCalibrated] = false
        }
    }
}
