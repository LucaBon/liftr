package com.lifthelper.ui.screens.anthropometric

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifthelper.domain.model.AnthropometricData
import com.lifthelper.domain.repository.CalibrationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AnthropometricDetailsViewModel @Inject constructor(
    private val calibrationRepository: CalibrationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnthropometricDetailsState())
    val uiState: StateFlow<AnthropometricDetailsState> = _uiState.asStateFlow()

    init {
        loadAnthropometricData()
    }

    private fun loadAnthropometricData() {
        viewModelScope.launch {
            val calibrationData = calibrationRepository.getCalibrationData().first()
            val anthropometricData = calibrationData?.anthropometricData

            if (anthropometricData != null) {
                // Convert measurements to centimeters for user display
                _uiState.value = _uiState.value.copy(
                    userHeight = (anthropometricData.userHeight * 100).toInt().toString(),
                    neckHeight = (anthropometricData.getNeckHeight() * 100).toInt().toString(),
                    torsoHeight = (anthropometricData.getTorsoHeight() * 100).toInt().toString(),
                    upperLegHeight = (anthropometricData.getUpperLegHeight() * 100).toInt().toString(),
                    lowerLegHeight = (anthropometricData.getLowerLegHeight() * 100).toInt().toString(),
                    shoulderWidth = (anthropometricData.getShoulderWidth() * 100).toInt().toString(),
                    hipWidth = (anthropometricData.getHipWidth() * 100).toInt().toString(),
                    upperArmLength = (anthropometricData.getUpperArmLength() * 100).toInt().toString(),
                    forearmLength = (anthropometricData.getForearmLength() * 100).toInt().toString(),
                    thighLength = (anthropometricData.getThighLength() * 100).toInt().toString(),
                    shinLength = (anthropometricData.getShinLength() * 100).toInt().toString(),
                    userWeight = anthropometricData.userWeight?.toInt()?.toString() ?: "",
                    hasData = true,
                    captureQuality = anthropometricData.captureQuality,
                    alignmentMessage = anthropometricData.alignmentMessage,
                    originalData = anthropometricData
                )
            } else {
                // No anthropometric data available
                _uiState.value = _uiState.value.copy(hasData = false)
            }
        }
    }

    fun onUserHeightChanged(value: String) {
        _uiState.value = _uiState.value.copy(userHeight = value)
    }

    fun onNeckHeightChanged(value: String) {
        _uiState.value = _uiState.value.copy(neckHeight = value)
    }

    fun onTorsoHeightChanged(value: String) {
        _uiState.value = _uiState.value.copy(torsoHeight = value)
    }

    fun onUpperLegHeightChanged(value: String) {
        _uiState.value = _uiState.value.copy(upperLegHeight = value)
    }

    fun onLowerLegHeightChanged(value: String) {
        _uiState.value = _uiState.value.copy(lowerLegHeight = value)
    }

    fun onShoulderWidthChanged(value: String) {
        _uiState.value = _uiState.value.copy(shoulderWidth = value)
    }

    fun onHipWidthChanged(value: String) {
        _uiState.value = _uiState.value.copy(hipWidth = value)
    }

    fun onUpperArmLengthChanged(value: String) {
        _uiState.value = _uiState.value.copy(upperArmLength = value)
    }

    fun onForearmLengthChanged(value: String) {
        _uiState.value = _uiState.value.copy(forearmLength = value)
    }

    fun onThighLengthChanged(value: String) {
        _uiState.value = _uiState.value.copy(thighLength = value)
    }

    fun onShinLengthChanged(value: String) {
        _uiState.value = _uiState.value.copy(shinLength = value)
    }

    fun onUserWeightChanged(value: String) {
        _uiState.value = _uiState.value.copy(userWeight = value)
    }

    fun onSave(onComplete: () -> Unit) {
        viewModelScope.launch {
            val state = _uiState.value
            val originalData = state.originalData ?: return@launch

            try {
                // Convert centimeters back to meters
                val userHeight = state.userHeight.toFloatOrNull()?.div(100) ?: originalData.userHeight

                // IMPROVE-8: Validate userHeight > 0 to prevent division by zero
                if (userHeight <= 0f) {
                    _uiState.value = _uiState.value.copy(errorMessage = "Height must be greater than zero")
                    return@launch
                }

                val neckHeight = state.neckHeight.toFloatOrNull()?.div(100) ?: originalData.getNeckHeight()
                val torsoHeight = state.torsoHeight.toFloatOrNull()?.div(100) ?: originalData.getTorsoHeight()
                val upperLegHeight = state.upperLegHeight.toFloatOrNull()?.div(100) ?: originalData.getUpperLegHeight()
                val lowerLegHeight = state.lowerLegHeight.toFloatOrNull()?.div(100) ?: originalData.getLowerLegHeight()
                val shoulderWidth = state.shoulderWidth.toFloatOrNull()?.div(100) ?: originalData.getShoulderWidth()
                val hipWidth = state.hipWidth.toFloatOrNull()?.div(100) ?: originalData.getHipWidth()
                val upperArmLength = state.upperArmLength.toFloatOrNull()?.div(100) ?: originalData.getUpperArmLength()
                val forearmLength = state.forearmLength.toFloatOrNull()?.div(100) ?: originalData.getForearmLength()
                val thighLength = state.thighLength.toFloatOrNull()?.div(100) ?: originalData.getThighLength()
                val shinLength = state.shinLength.toFloatOrNull()?.div(100) ?: originalData.getShinLength()
                val userWeight = state.userWeight.toFloatOrNull()

                // Create updated anthropometric data with new ratios
                val updatedData = AnthropometricData(
                    headHeightRatio = originalData.headHeightRatio, // Keep original (not editable)
                    neckHeightRatio = neckHeight / userHeight,
                    torsoHeightRatio = torsoHeight / userHeight,
                    upperLegHeightRatio = upperLegHeight / userHeight,
                    lowerLegHeightRatio = lowerLegHeight / userHeight,
                    footHeightRatio = originalData.footHeightRatio, // Keep original (not editable)
                    shoulderWidthRatio = shoulderWidth / userHeight,
                    hipWidthRatio = hipWidth / userHeight,
                    kneeWidthRatio = originalData.kneeWidthRatio, // Keep original (not editable)
                    upperArmLengthRatio = upperArmLength / userHeight,
                    forearmLengthRatio = forearmLength / userHeight,
                    thighLengthRatio = thighLength / userHeight,
                    shinLengthRatio = shinLength / userHeight,
                    userWeight = userWeight,
                    userHeight = userHeight,
                    captureTimestamp = System.currentTimeMillis(),
                    captureQuality = originalData.captureQuality // Preserve original quality
                )

                // Save to DataStore
                calibrationRepository.saveAnthropometricData(updatedData)
                _uiState.value = _uiState.value.copy(errorMessage = null)
                onComplete()
            } catch (e: Exception) {
                // IMPROVE-8: Surface error to UI instead of silently swallowing
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to save: ${e.message ?: "Unknown error"}"
                )
            }
        }
    }
}

data class AnthropometricDetailsState(
    val userHeight: String = "",
    val neckHeight: String = "",
    val torsoHeight: String = "",
    val upperLegHeight: String = "",
    val lowerLegHeight: String = "",
    val shoulderWidth: String = "",
    val hipWidth: String = "",
    val upperArmLength: String = "",
    val forearmLength: String = "",
    val thighLength: String = "",
    val shinLength: String = "",
    val userWeight: String = "",
    val hasData: Boolean = false,
    val captureQuality: com.lifthelper.domain.model.CalibrationQuality = com.lifthelper.domain.model.CalibrationQuality.NONE,
    val alignmentMessage: String? = null,
    val originalData: AnthropometricData? = null,
    val errorMessage: String? = null
)
