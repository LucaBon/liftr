package com.lifthelper.ui.screens.home

import androidx.lifecycle.ViewModel
import com.lifthelper.data.local.preferences.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    val settingsDataStore: SettingsDataStore
) : ViewModel()
