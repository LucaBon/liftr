package com.lifthelper.data.repository

import com.lifthelper.data.local.preferences.SettingsDataStore
import com.lifthelper.domain.error.LiftHelperError
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.model.AnthropometricData
import com.lifthelper.domain.model.CalibrationData
import com.lifthelper.domain.repository.CalibrationRepository
import com.lifthelper.domain.util.mapError
import com.lifthelper.domain.util.onError
import kotlinx.coroutines.flow.Flow
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of CalibrationRepository.
 *
 * Provides data access for calibration operations using DataStore preferences.
 * Handles error handling with Result<T>.
 */
@Singleton
class CalibrationRepositoryImpl @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val logger: Logger
) : CalibrationRepository {

    companion object {
        private const val TAG = "CalibrationRepository"
    }

    override fun getCalibrationData(): Flow<CalibrationData?> {
        return settingsDataStore.calibrationData
    }

    override fun isCalibrated(): Flow<Boolean> {
        return settingsDataStore.isCalibrated
    }

    override suspend fun saveCalibration(calibrationData: CalibrationData): Result<Unit> = runCatching {
        settingsDataStore.saveCalibration(calibrationData)
        logger.d(TAG, "Calibration data saved successfully")
        Unit
    }.mapError { error ->
        when (error) {
            is IOException -> LiftHelperError.CalibrationError(
                "Failed to save calibration data",
                com.lifthelper.domain.error.CalibrationErrorCode.VALIDATION_FAILED
            )
            is LiftHelperError -> error
            else -> LiftHelperError.DatabaseError(
                "Unexpected error saving calibration data",
                error
            )
        }
    }.onError { error ->
        logger.e(TAG, "Save calibration failed: ${error.message}", error)
    }

    override suspend fun updatePixelsPerMeter(pixelsPerMeter: Float): Result<Unit> = runCatching {
        if (pixelsPerMeter <= 0) {
            throw LiftHelperError.ValidationError(
                "pixelsPerMeter",
                "Pixels per meter must be positive, got: $pixelsPerMeter"
            )
        }
        settingsDataStore.updatePixelsPerMeter(pixelsPerMeter)
        logger.d(TAG, "Pixels per meter updated successfully: $pixelsPerMeter")
        Unit
    }.mapError { error ->
        when (error) {
            is LiftHelperError -> error
            is IOException -> LiftHelperError.DatabaseError(
                "Failed to update pixels per meter",
                error
            )
            else -> LiftHelperError.DatabaseError(
                "Unexpected error updating pixels per meter",
                error
            )
        }
    }.onError { error ->
        logger.e(TAG, "Update pixels per meter failed: ${error.message}", error)
    }

    override suspend fun saveAnthropometricData(anthropometricData: AnthropometricData): Result<Unit> = runCatching {
        settingsDataStore.saveAnthropometricData(anthropometricData)
        logger.d(TAG, "Anthropometric data saved successfully")
        Unit
    }.mapError { error ->
        when (error) {
            is IOException -> LiftHelperError.DatabaseError(
                "Failed to save anthropometric data",
                error
            )
            is LiftHelperError -> error
            else -> LiftHelperError.DatabaseError(
                "Unexpected error saving anthropometric data",
                error
            )
        }
    }.onError { error ->
        logger.e(TAG, "Save anthropometric data failed: ${error.message}", error)
    }

    override suspend fun clearCalibration(): Result<Unit> = runCatching {
        settingsDataStore.clearCalibration()
        logger.d(TAG, "Calibration data cleared successfully")
        Unit
    }.mapError { error ->
        when (error) {
            is IOException -> LiftHelperError.DatabaseError(
                "Failed to clear calibration data",
                error
            )
            is LiftHelperError -> error
            else -> LiftHelperError.DatabaseError(
                "Unexpected error clearing calibration data",
                error
            )
        }
    }.onError { error ->
        logger.e(TAG, "Clear calibration failed: ${error.message}", error)
    }
}
