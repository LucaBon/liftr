package com.lifthelper.domain.usecase

import com.lifthelper.domain.model.CalibrationData
import com.lifthelper.domain.repository.CalibrationRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case for loading calibration data.
 *
 * Observes calibration data changes and emits updates to subscribers.
 * This is used by ViewModels that need to react to calibration changes.
 */
class LoadCalibrationUseCase @Inject constructor(
    private val calibrationRepository: CalibrationRepository
) : FlowUseCase<NoParams, CalibrationData?>() {

    /**
     * Loads and observes calibration data.
     *
     * @param params Not used (NoParams)
     * @return Flow emitting calibration data or null if not calibrated
     */
    override fun invoke(params: NoParams): Flow<CalibrationData?> {
        return calibrationRepository.getCalibrationData()
    }
}
