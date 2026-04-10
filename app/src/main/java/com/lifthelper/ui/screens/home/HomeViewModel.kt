package com.lifthelper.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifthelper.domain.model.Workout
import com.lifthelper.domain.repository.CalibrationRepository
import com.lifthelper.domain.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class HomeUiState(
    val lastWorkout: Workout? = null,
    val totalWorkouts: Int = 0,
    val isCalibrated: Boolean = false
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    val calibrationRepository: CalibrationRepository,
    private val workoutRepository: WorkoutRepository
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        workoutRepository.getWorkouts(),
        calibrationRepository.isCalibrated()
    ) { workouts, calibrated ->
        HomeUiState(
            lastWorkout = workouts.maxByOrNull { it.date },
            totalWorkouts = workouts.size,
            isCalibrated = calibrated
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState()
    )
}
