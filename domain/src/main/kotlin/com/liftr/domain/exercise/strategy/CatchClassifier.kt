package com.liftr.domain.exercise.strategy

import com.liftr.domain.exercise.ExerciseThresholds
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.CatchType
import com.liftr.domain.model.MovementPhase
import com.liftr.domain.model.PoseResult
import com.liftr.domain.usecase.AnalyzeRepUseCase
import com.liftr.domain.vision.IPositionCalculator

private const val MIN_VISIBILITY = 0.5f

/**
 * How the rep's catch went, from the hips only. The drop is the lowest hip in the
 * catch (CONCENTRIC) frames below the highest hip from the second pull on; a small
 * drop is a muscle catch, otherwise hip against knee at the lowest point tells
 * power from squat. Distances in metres and times in ms, so it holds at any fps.
 * Null when the catch frames have no usable hip.
 */
internal fun classifyCatch(
    frames: List<AnalyzeRepUseCase.RepFrame>,
    positionCalculator: IPositionCalculator,
    calibrationData: CalibrationData
): CatchType? {
    val sides = frames.map {
        nearSideLandmarks(
            it.pose,
            left = listOf(PoseResult.LEFT_HIP, PoseResult.LEFT_KNEE),
            right = listOf(PoseResult.RIGHT_HIP, PoseResult.RIGHT_KNEE),
            minVisibility = MIN_VISIBILITY
        )
    }
    // Median-filtered over time so a one-frame landmark jump is not the lowest hip.
    val hipY = rollingMedianOrNull(
        frames.map { it.pose.timestampUs },
        sides.map { it?.get(0)?.y },
        ExerciseThresholds.Catch.HIP_SMOOTH_MS
    )

    val lowest = frames.indices
        .filter { frames[it].phase == MovementPhase.CONCENTRIC && hipY[it] != null }
        .maxByOrNull { hipY[it]!! } ?: return null
    // Larger Y = lower. The highest hip (smallest Y) from the second pull to the lowest point.
    val highestY = (0..lowest)
        .filter { frames[it].phase == MovementPhase.BOTTOM || frames[it].phase == MovementPhase.CONCENTRIC }
        .mapNotNull { hipY[it] }
        .min()

    val pose = frames[lowest].pose
    val drop = positionCalculator.pixelsToMeters(hipY[lowest]!! - highestY, pose, calibrationData)
    if (drop < ExerciseThresholds.Catch.MUSCLE_MAX_HIP_DROP) return CatchType.MUSCLE

    val kneeY = sides[lowest]?.get(1)?.y ?: return CatchType.POWER
    val hipsBelowKnees = positionCalculator.pixelsToMeters(hipY[lowest]!! - kneeY, pose, calibrationData)
    return if (hipsBelowKnees >= ExerciseThresholds.Catch.SQUAT_HIP_BELOW_KNEE) CatchType.SQUAT else CatchType.POWER
}
