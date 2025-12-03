package com.lifthelper.ui.screens.workout

import com.lifthelper.domain.model.CalibrationQuality
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.PoseResult
import com.lifthelper.domain.model.VelocityData

data class WorkoutState(
    val isActive: Boolean = false,
    val selectedExercise: ExerciseType = ExerciseType.FRONT_SQUAT,
    val weight: Float = 0f,
    val targetReps: Int = 5,
    val poseResult: PoseResult? = null,
    val velocityData: VelocityData? = null,
    val repCount: Int = 0,
    val isRecording: Boolean = false,
    val error: String? = null,
    val targetVelocityRange: ClosedFloatingPointRange<Float>? = null,
    val calibrationQuality: CalibrationQuality = CalibrationQuality.NONE
)
