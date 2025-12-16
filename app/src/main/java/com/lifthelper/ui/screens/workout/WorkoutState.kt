package com.lifthelper.ui.screens.workout

import com.lifthelper.domain.model.CalibrationQuality
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.PoseResult
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
    val calibrationQuality: CalibrationQuality = CalibrationQuality.NONE
)
