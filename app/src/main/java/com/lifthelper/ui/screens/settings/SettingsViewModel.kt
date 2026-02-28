package com.lifthelper.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifthelper.domain.exercise.VelocityLossState
import com.lifthelper.domain.model.SquatDepthType
import com.lifthelper.domain.repository.SettingsKeys
import com.lifthelper.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Settings screen.
 * Manages user preferences including squat depth configurations and VBT settings.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _backSquatDepthType = MutableStateFlow(SquatDepthType.DEFAULT)
    val backSquatDepthType: StateFlow<SquatDepthType> = _backSquatDepthType.asStateFlow()

    private val _frontSquatDepthType = MutableStateFlow(SquatDepthType.DEFAULT)
    val frontSquatDepthType: StateFlow<SquatDepthType> = _frontSquatDepthType.asStateFlow()

    private val _vbtZonesEnabled = MutableStateFlow(true)
    val vbtZonesEnabled: StateFlow<Boolean> = _vbtZonesEnabled.asStateFlow()

    private val _velocityLossThreshold = MutableStateFlow(VelocityLossState.DEFAULT_THRESHOLD)
    val velocityLossThreshold: StateFlow<Float> = _velocityLossThreshold.asStateFlow()

    private val _audioFeedbackEnabled = MutableStateFlow(true)
    val audioFeedbackEnabled: StateFlow<Boolean> = _audioFeedbackEnabled.asStateFlow()

    private val _hapticFeedbackEnabled = MutableStateFlow(true)
    val hapticFeedbackEnabled: StateFlow<Boolean> = _hapticFeedbackEnabled.asStateFlow()

    init {
        loadSquatDepthSettings()
        loadVbtSettings()
        loadFeedbackSettings()
    }

    private fun loadSquatDepthSettings() {
        viewModelScope.launch {
            settingsRepository.getStringSetting(
                SettingsKeys.SQUAT_DEPTH_BACK_SQUAT,
                SquatDepthType.DEFAULT.name
            ).collect { depthTypeName ->
                _backSquatDepthType.value = SquatDepthType.fromString(depthTypeName)
            }
        }

        viewModelScope.launch {
            settingsRepository.getStringSetting(
                SettingsKeys.SQUAT_DEPTH_FRONT_SQUAT,
                SquatDepthType.DEFAULT.name
            ).collect { depthTypeName ->
                _frontSquatDepthType.value = SquatDepthType.fromString(depthTypeName)
            }
        }
    }

    private fun loadVbtSettings() {
        viewModelScope.launch {
            settingsRepository.getBooleanSetting(
                SettingsKeys.VBT_ZONES_ENABLED,
                true
            ).collect { enabled ->
                _vbtZonesEnabled.value = enabled
            }
        }
        viewModelScope.launch {
            settingsRepository.getFloatSetting(
                SettingsKeys.VELOCITY_LOSS_THRESHOLD,
                VelocityLossState.DEFAULT_THRESHOLD
            ).collect { threshold ->
                _velocityLossThreshold.value = threshold
            }
        }
    }

    fun updateBackSquatDepth(depthType: SquatDepthType) {
        viewModelScope.launch {
            settingsRepository.saveStringSetting(
                SettingsKeys.SQUAT_DEPTH_BACK_SQUAT,
                depthType.name
            ).onSuccess {
                _backSquatDepthType.value = depthType
            }
        }
    }

    fun updateFrontSquatDepth(depthType: SquatDepthType) {
        viewModelScope.launch {
            settingsRepository.saveStringSetting(
                SettingsKeys.SQUAT_DEPTH_FRONT_SQUAT,
                depthType.name
            ).onSuccess {
                _frontSquatDepthType.value = depthType
            }
        }
    }

    fun updateVbtZonesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.saveBooleanSetting(
                SettingsKeys.VBT_ZONES_ENABLED,
                enabled
            ).onSuccess {
                _vbtZonesEnabled.value = enabled
            }
        }
    }

    fun updateVelocityLossThreshold(threshold: Float) {
        viewModelScope.launch {
            settingsRepository.saveFloatSetting(
                SettingsKeys.VELOCITY_LOSS_THRESHOLD,
                threshold
            ).onSuccess {
                _velocityLossThreshold.value = threshold
            }
        }
    }

    private fun loadFeedbackSettings() {
        viewModelScope.launch {
            settingsRepository.getBooleanSetting(
                SettingsKeys.AUDIO_FEEDBACK_ENABLED,
                true
            ).collect { enabled ->
                _audioFeedbackEnabled.value = enabled
            }
        }
        viewModelScope.launch {
            settingsRepository.getBooleanSetting(
                SettingsKeys.HAPTIC_FEEDBACK_ENABLED,
                true
            ).collect { enabled ->
                _hapticFeedbackEnabled.value = enabled
            }
        }
    }

    fun updateAudioFeedbackEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.saveBooleanSetting(
                SettingsKeys.AUDIO_FEEDBACK_ENABLED,
                enabled
            ).onSuccess {
                _audioFeedbackEnabled.value = enabled
            }
        }
    }

    fun updateHapticFeedbackEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.saveBooleanSetting(
                SettingsKeys.HAPTIC_FEEDBACK_ENABLED,
                enabled
            ).onSuccess {
                _hapticFeedbackEnabled.value = enabled
            }
        }
    }
}
