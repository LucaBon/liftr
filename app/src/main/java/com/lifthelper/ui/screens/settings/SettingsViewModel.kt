package com.lifthelper.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifthelper.data.export.FileExporter
import com.lifthelper.domain.exercise.VelocityLossState
import com.lifthelper.domain.feedback.FeedbackLanguage
import com.lifthelper.domain.feedback.FeedbackVerbosity
import com.lifthelper.domain.feedback.IFeedbackEmitter
import com.lifthelper.domain.feedback.TtsEngineInfo
import com.lifthelper.domain.model.ExportConfig
import com.lifthelper.domain.model.SquatDepthType
import com.lifthelper.domain.repository.SettingsKeys
import com.lifthelper.domain.repository.SettingsRepository
import com.lifthelper.domain.usecase.GenerateExportUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Settings screen.
 * Manages user preferences including squat depth configurations and VBT settings.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val feedbackEmitter: IFeedbackEmitter,
    private val generateExportUseCase: GenerateExportUseCase,
    private val fileExporter: FileExporter
) : ViewModel() {

    private val _exportEvents = MutableSharedFlow<ExportEvent>(extraBufferCapacity = 4)
    val exportEvents: SharedFlow<ExportEvent> = _exportEvents.asSharedFlow()

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

    private val _feedbackLanguage = MutableStateFlow(FeedbackLanguage.DEFAULT)
    val feedbackLanguage: StateFlow<FeedbackLanguage> = _feedbackLanguage.asStateFlow()

    private val _feedbackVerbosity = MutableStateFlow(FeedbackVerbosity.DEFAULT)
    val feedbackVerbosity: StateFlow<FeedbackVerbosity> = _feedbackVerbosity.asStateFlow()

    private val _ttsEngine = MutableStateFlow<String?>(null)
    val ttsEngine: StateFlow<String?> = _ttsEngine.asStateFlow()

    val availableTtsEngines: List<TtsEngineInfo>
        get() = feedbackEmitter.getAvailableEngines()

    private val _restTimerEnabled = MutableStateFlow(true)
    val restTimerEnabled: StateFlow<Boolean> = _restTimerEnabled.asStateFlow()

    private val _restDurationDefault = MutableStateFlow(90)
    val restDurationDefault: StateFlow<Int> = _restDurationDefault.asStateFlow()

    private val _tempoModeEnabled = MutableStateFlow(false)
    val tempoModeEnabled: StateFlow<Boolean> = _tempoModeEnabled.asStateFlow()

    private val _autoStopOnTargetReps = MutableStateFlow(true)
    val autoStopOnTargetReps: StateFlow<Boolean> = _autoStopOnTargetReps.asStateFlow()

    init {
        loadSquatDepthSettings()
        loadVbtSettings()
        loadFeedbackSettings()
        loadRestTimerSettings()
        loadTempoSettings()
        loadAutoStopSettings()
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
        viewModelScope.launch {
            settingsRepository.getStringSetting(
                SettingsKeys.FEEDBACK_LANGUAGE,
                FeedbackLanguage.DEFAULT.name
            ).collect { name ->
                _feedbackLanguage.value = FeedbackLanguage.fromString(name)
            }
        }
        viewModelScope.launch {
            settingsRepository.getStringSetting(
                SettingsKeys.FEEDBACK_VERBOSITY,
                FeedbackVerbosity.DEFAULT.name
            ).collect { name ->
                _feedbackVerbosity.value = FeedbackVerbosity.fromString(name)
            }
        }
        viewModelScope.launch {
            settingsRepository.getStringSetting(
                SettingsKeys.FEEDBACK_TTS_ENGINE,
                ""
            ).collect { pkg ->
                _ttsEngine.value = pkg.takeIf { it.isNotBlank() }
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

    fun updateFeedbackLanguage(language: FeedbackLanguage) {
        viewModelScope.launch {
            settingsRepository.saveStringSetting(
                SettingsKeys.FEEDBACK_LANGUAGE,
                language.name
            ).onSuccess {
                _feedbackLanguage.value = language
            }
        }
    }

    fun updateFeedbackVerbosity(verbosity: FeedbackVerbosity) {
        viewModelScope.launch {
            settingsRepository.saveStringSetting(
                SettingsKeys.FEEDBACK_VERBOSITY,
                verbosity.name
            ).onSuccess {
                _feedbackVerbosity.value = verbosity
            }
        }
    }

    fun updateTtsEngine(packageName: String?) {
        viewModelScope.launch {
            settingsRepository.saveStringSetting(
                SettingsKeys.FEEDBACK_TTS_ENGINE,
                packageName.orEmpty()
            ).onSuccess {
                _ttsEngine.value = packageName
            }
        }
    }

    private fun loadRestTimerSettings() {
        viewModelScope.launch {
            settingsRepository.getBooleanSetting(
                SettingsKeys.REST_TIMER_ENABLED,
                true
            ).collect { enabled ->
                _restTimerEnabled.value = enabled
            }
        }
        viewModelScope.launch {
            settingsRepository.getFloatSetting(
                SettingsKeys.REST_DURATION_DEFAULT,
                90f
            ).collect { duration ->
                _restDurationDefault.value = duration.toInt()
            }
        }
    }

    fun updateRestTimerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.saveBooleanSetting(
                SettingsKeys.REST_TIMER_ENABLED,
                enabled
            ).onSuccess {
                _restTimerEnabled.value = enabled
            }
        }
    }

    fun updateRestDurationDefault(seconds: Int) {
        viewModelScope.launch {
            settingsRepository.saveFloatSetting(
                SettingsKeys.REST_DURATION_DEFAULT,
                seconds.toFloat()
            ).onSuccess {
                _restDurationDefault.value = seconds
            }
        }
    }

    private fun loadTempoSettings() {
        viewModelScope.launch {
            settingsRepository.getBooleanSetting(
                SettingsKeys.TEMPO_MODE_ENABLED,
                false
            ).collect { enabled ->
                _tempoModeEnabled.value = enabled
            }
        }
    }

    fun updateTempoModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.saveBooleanSetting(
                SettingsKeys.TEMPO_MODE_ENABLED,
                enabled
            ).onSuccess {
                _tempoModeEnabled.value = enabled
            }
        }
    }

    private fun loadAutoStopSettings() {
        viewModelScope.launch {
            settingsRepository.getBooleanSetting(
                SettingsKeys.AUTO_STOP_ON_TARGET_REPS,
                true
            ).collect { enabled ->
                _autoStopOnTargetReps.value = enabled
            }
        }
    }

    fun updateAutoStopOnTargetReps(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.saveBooleanSetting(
                SettingsKeys.AUTO_STOP_ON_TARGET_REPS,
                enabled
            ).onSuccess {
                _autoStopOnTargetReps.value = enabled
            }
        }
    }

    fun resetOnboardingFlag() {
        viewModelScope.launch {
            settingsRepository.saveBooleanSetting(SettingsKeys.ONBOARDING_COMPLETED, false)
        }
    }

    fun exportWorkoutData(config: ExportConfig, chooserTitle: String) {
        viewModelScope.launch {
            val event = runCatching { generateExportUseCase(config) }
                .fold(
                    onSuccess = { result ->
                        if (result.setCount == 0) {
                            ExportEvent.Empty
                        } else {
                            fileExporter.exportAndShare(result, chooserTitle)
                            ExportEvent.Success(result.workoutCount, result.setCount)
                        }
                    },
                    onFailure = { ExportEvent.Failure(it.message ?: it::class.simpleName.orEmpty()) }
                )
            _exportEvents.emit(event)
        }
    }
}

sealed class ExportEvent {
    data class Success(val workoutCount: Int, val setCount: Int) : ExportEvent()
    data object Empty : ExportEvent()
    data class Failure(val reason: String) : ExportEvent()
}
