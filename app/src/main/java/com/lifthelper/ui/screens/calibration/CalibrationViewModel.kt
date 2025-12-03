package com.lifthelper.ui.screens.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifthelper.data.local.preferences.SettingsDataStore
import com.lifthelper.domain.model.CalibrationData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CalibrationViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalibrationState())
    val uiState: StateFlow<CalibrationState> = _uiState.asStateFlow()

    fun onHeightChanged(height: String) {
        _uiState.value = _uiState.value.copy(
            height = height,
            heightError = null
        )
    }

    fun onShoulderWidthChanged(width: String) {
        _uiState.value = _uiState.value.copy(
            shoulderWidth = width,
            shoulderWidthError = null
        )
    }

    fun onContinue(onComplete: () -> Unit) {
        val height = _uiState.value.height.toFloatOrNull()
        val shoulderWidth = _uiState.value.shoulderWidth.toFloatOrNull()

        // Validation
        if (height == null || height < 100 || height > 250) {
            _uiState.value = _uiState.value.copy(
                heightError = "Please enter a valid height (100-250 cm)"
            )
            return
        }

        if (shoulderWidth != null && (shoulderWidth < 20 || shoulderWidth > 80)) {
            _uiState.value = _uiState.value.copy(
                shoulderWidthError = "Please enter a valid shoulder width (20-80 cm)"
            )
            return
        }

        // Save calibration
        val calibrationData = CalibrationData.fromUserInput(
            heightCm = height,
            shoulderWidthCm = shoulderWidth
        )

        viewModelScope.launch {
            settingsDataStore.saveCalibration(calibrationData)
            onComplete()
        }
    }

    fun onSkip(onComplete: () -> Unit) {
        // Use default values
        val calibrationData = CalibrationData.fromUserInput(
            heightCm = 170f, // Default height
            shoulderWidthCm = null // Will use default shoulder width
        )

        viewModelScope.launch {
            settingsDataStore.saveCalibration(calibrationData)
            onComplete()
        }
    }
}

data class CalibrationState(
    val height: String = "",
    val shoulderWidth: String = "",
    val heightError: String? = null,
    val shoulderWidthError: String? = null
)
