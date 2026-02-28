package com.lifthelper.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifthelper.domain.model.Workout
import com.lifthelper.domain.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val workoutRepository: WorkoutRepository
) : ViewModel() {

    val uiState: StateFlow<HistoryState> = workoutRepository.getWorkouts()
        .map { workouts ->
            if (workouts.isEmpty()) {
                HistoryState.Empty
            } else {
                HistoryState.Loaded(workouts)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryState.Loading)

    fun deleteWorkout(id: Long) {
        viewModelScope.launch {
            workoutRepository.deleteWorkout(id)
        }
    }
}

sealed interface HistoryState {
    data object Loading : HistoryState
    data object Empty : HistoryState
    data class Loaded(val workouts: List<Workout>) : HistoryState
}
