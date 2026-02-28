package com.lifthelper.ui.screens.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifthelper.domain.model.AnalyticsSummary
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.TimeRange
import com.lifthelper.domain.usecase.GetAnalyticsSummaryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val getAnalyticsSummaryUseCase: GetAnalyticsSummaryUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnalyticsState())
    val uiState: StateFlow<AnalyticsState> = _uiState.asStateFlow()

    init {
        loadAnalytics()
    }

    fun setExerciseFilter(exerciseType: ExerciseType?) {
        _uiState.update { it.copy(selectedExercise = exerciseType) }
        loadAnalytics()
    }

    fun setTimeRange(timeRange: TimeRange) {
        _uiState.update { it.copy(selectedTimeRange = timeRange) }
        loadAnalytics()
    }

    private fun loadAnalytics() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val summary = getAnalyticsSummaryUseCase(
                    GetAnalyticsSummaryUseCase.Params(
                        exerciseType = state.selectedExercise,
                        timeRange = state.selectedTimeRange
                    )
                )
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        summary = summary
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Unknown error")
                }
            }
        }
    }
}

data class AnalyticsState(
    val isLoading: Boolean = true,
    val selectedExercise: ExerciseType? = null,
    val selectedTimeRange: TimeRange = TimeRange.THREE_MONTHS,
    val summary: AnalyticsSummary? = null,
    val error: String? = null
)
