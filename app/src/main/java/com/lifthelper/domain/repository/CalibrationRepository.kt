package com.lifthelper.domain.repository

import com.lifthelper.domain.model.AnthropometricData
import com.lifthelper.domain.model.CalibrationData
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for calibration data operations.
 *
 * Manages user calibration data including pixels-per-meter measurements,
 * user physical dimensions, and anthropometric data.
 */
interface CalibrationRepository {
    /**
     * Observes the current calibration data.
     *
     * @return Flow emitting the current calibration data, or null if not calibrated
     */
    fun getCalibrationData(): Flow<CalibrationData?>

    /**
     * Observes whether the app is calibrated.
     *
     * @return Flow emitting true if calibrated, false otherwise
     */
    fun isCalibrated(): Flow<Boolean>

    /**
     * Saves calibration data.
     *
     * @param calibrationData The calibration data to save
     * @return Result indicating success or failure
     */
    suspend fun saveCalibration(calibrationData: CalibrationData): Result<Unit>

    /**
     * Updates the pixels per meter value for the current calibration.
     *
     * @param pixelsPerMeter The new pixels per meter value
     * @return Result indicating success or failure
     */
    suspend fun updatePixelsPerMeter(pixelsPerMeter: Float): Result<Unit>

    /**
     * Saves anthropometric data for the user.
     *
     * @param anthropometricData The anthropometric data to save
     * @return Result indicating success or failure
     */
    suspend fun saveAnthropometricData(anthropometricData: AnthropometricData): Result<Unit>

    /**
     * Clears all calibration data.
     *
     * @return Result indicating success or failure
     */
    suspend fun clearCalibration(): Result<Unit>
}
