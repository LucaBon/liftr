package com.lifthelper.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifthelper.domain.repository.SettingsKeys
import com.lifthelper.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    fun markOnboardingComplete() {
        viewModelScope.launch {
            settingsRepository.saveBooleanSetting(
                SettingsKeys.ONBOARDING_COMPLETED,
                true
            )
        }
    }
}
