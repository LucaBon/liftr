package com.lifthelper.ui.screens.workout

import com.lifthelper.domain.exercise.VelocityLossState
import com.lifthelper.domain.model.CalibrationQuality
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.PoseResult
import com.lifthelper.domain.model.RepQualityScore
import com.lifthelper.domain.model.PersonalRecord
import com.lifthelper.domain.model.RestTimerState
import com.lifthelper.domain.model.SetSummary
import com.lifthelper.domain.model.TempoPrescription
import com.lifthelper.domain.exercise.TempoCue
import com.lifthelper.domain.exercise.TempoState
import com.lifthelper.domain.model.VbtZone
import com.lifthelper.domain.model.VelocityData
import com.lifthelper.domain.value.Kilograms
import com.lifthelper.domain.value.RepCount
import com.lifthelper.ui.common.UiError

data class WorkoutState(
    val isActive: Boolean = false,
    val selectedExercise: ExerciseType = ExerciseType.FRONT_SQUAT,
    val weight: Kilograms = Kilograms(0f),
    val targetReps: RepCount = RepCount(5),
    val poseResult: PoseResult? = null,
    val velocityData: VelocityData? = null,
    val repCount: RepCount = RepCount(0),
    val isRecording: Boolean = false,
    val error: UiError? = null,
    val isLoading: Boolean = false,
    val targetVelocityRange: ClosedFloatingPointRange<Float>? = null,
    val calibrationQuality: CalibrationQuality = CalibrationQuality.NONE,
    val vbtZone: VbtZone? = null,
    val velocityLossState: VelocityLossState = VelocityLossState(),
    val estimatedOneRM: Kilograms? = null,
    val vbtZonesEnabled: Boolean = true,
    val currentRepScore: RepQualityScore? = null,
    val lastSetSummary: SetSummary? = null,
    val completedSetCount: Int = 0,
    val newPersonalRecords: List<PersonalRecord> = emptyList(),
    val workoutSaved: Boolean = false,
    // Manual entry
    val isManualEntryMode: Boolean = false,
    val customExerciseNames: List<String> = emptyList(),
    // Rest timer
    val restTimerState: RestTimerState? = null,
    val restTimerEnabled: Boolean = true,
    // Tempo training
    val tempoModeEnabled: Boolean = false,
    val tempoPrescription: TempoPrescription? = null,
    val tempoState: TempoState? = null,
    val tempoCue: TempoCue? = null
)
