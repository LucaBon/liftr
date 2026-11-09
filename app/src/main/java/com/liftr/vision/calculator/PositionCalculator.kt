package com.liftr.vision.calculator

import com.liftr.domain.exercise.normalizedXY
import com.liftr.domain.exercise.trackingPoint
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.PoseResult
import com.liftr.domain.vision.BodyPositionCalculator
import javax.inject.Inject

/** The app's position calculator: the domain math ([BodyPositionCalculator]) plus the bar position. */
class PositionCalculator @Inject constructor(
    logger: Logger
) : BodyPositionCalculator(logger) {

    /** Tracked bar position in normalized image coordinates for the exercise type. */
    fun getBarPosition(poseResult: PoseResult, exerciseType: ExerciseType): Pair<Float, Float>? =
        normalizedXY(poseResult, trackingPoint(exerciseType))
}
