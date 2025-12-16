package com.lifthelper.ui.screens.home

import androidx.lifecycle.ViewModel
import com.lifthelper.domain.repository.CalibrationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    val calibrationRepository: CalibrationRepository
) : ViewModel()
