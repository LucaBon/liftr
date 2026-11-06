package com.liftr.domain.exercise.strategy

import com.liftr.domain.exercise.ExercisePhaseState
import com.liftr.domain.exercise.ExerciseThresholds
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.PoseResult
import com.liftr.domain.vision.IPositionCalculator
import javax.inject.Inject

/**
 * Strategy for back squat exercise: the bar rests on the upper back, roughly
 * midway between head and shoulders. See [SquatStrategyBase] for the phase
 * detection and form checks.
 */
class BackSquatStrategy @Inject constructor(
    positionCalculator: IPositionCalculator,
    logger: Logger
) : SquatStrategyBase(
    positionCalculator = positionCalculator,
    logger = logger,
    targetBodyParts = listOf(
        PoseResult.NOSE,
        PoseResult.LEFT_SHOULDER,
        PoseResult.RIGHT_SHOULDER,
        PoseResult.LEFT_HIP,
        PoseResult.RIGHT_HIP,
        PoseResult.LEFT_KNEE,
        PoseResult.RIGHT_KNEE
    ),
    tag = "BackSquatStrategy",
    minDescentDepth = ExerciseThresholds.BackSquat.MIN_DESCENT_DEPTH,
    returnTolerance = ExerciseThresholds.BackSquat.RETURN_TOLERANCE,
    minVelocity = ExerciseThresholds.BackSquat.MIN_VELOCITY
) {

    override val exerciseType = ExerciseType.BACK_SQUAT

    override val depthTolerance = ExerciseThresholds.BackSquat.DEPTH_TOLERANCE
    override val idleToEccentricThreshold = ExerciseThresholds.BackSquat.IDLE_TO_ECCENTRIC_THRESHOLD
    override val bottomToConcentricThreshold = ExerciseThresholds.BackSquat.BOTTOM_TO_CONCENTRIC_THRESHOLD
    override val topStabilizationThreshold = ExerciseThresholds.BackSquat.TOP_STABILIZATION_THRESHOLD

    override val torsoLimits = TorsoLimits(
        kind = "torso_angle",
        criticalDegrees = 50f,
        warningDegrees = 35f,
        criticalSuggestion = "Keep chest up and sit back into the squat",
        warningSuggestion = "Try to maintain more upright torso position"
    )

    override val heelLiftSuggestion = "Keep weight on heels throughout the movement"

    /** Bar rests on upper back, approximately midway between head and shoulders. */
    override fun calculateBarPosition(headY: Float?, shouldersY: Float?): Float? = when {
        headY != null && shouldersY != null -> (headY + shouldersY) / 2f
        shouldersY != null -> shouldersY  // Prefer shoulders if only one available
        headY != null -> headY
        else -> null
    }

    override fun startingBarPosition(state: ExercisePhaseState): Float? {
        val head = state.startingHeadY ?: return null
        val shoulders = state.startingShouldersY ?: return null
        return (head + shoulders) / 2f
    }
}
