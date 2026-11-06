package com.liftr.domain.exercise.strategy

import com.liftr.domain.exercise.ExercisePhaseState
import com.liftr.domain.exercise.ExerciseThresholds
import com.liftr.domain.exercise.FormIssue
import com.liftr.domain.exercise.Severity
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.PoseResult
import com.liftr.domain.vision.IPositionCalculator
import javax.inject.Inject

/**
 * Strategy for front squat exercise.
 *
 * Like the back squat (see [SquatStrategyBase]) but:
 * 1. Bar position is on the front shoulders (not upper back)
 * 2. More upright torso required, so stricter lean limits
 * 3. Elbows must stay high to keep the bar on the shoulders
 */
class FrontSquatStrategy @Inject constructor(
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
        PoseResult.RIGHT_KNEE,
        PoseResult.LEFT_ELBOW,
        PoseResult.RIGHT_ELBOW  // Important for front squat form
    ),
    tag = "FrontSquatStrategy",
    minDescentDepth = ExerciseThresholds.FrontSquat.MIN_DESCENT_DEPTH,
    returnTolerance = ExerciseThresholds.FrontSquat.RETURN_TOLERANCE,
    minVelocity = ExerciseThresholds.FrontSquat.MIN_VELOCITY
) {

    override val exerciseType = ExerciseType.FRONT_SQUAT

    override val depthTolerance = ExerciseThresholds.FrontSquat.DEPTH_TOLERANCE
    override val idleToEccentricThreshold = ExerciseThresholds.FrontSquat.IDLE_TO_ECCENTRIC_THRESHOLD
    override val bottomToConcentricThreshold = ExerciseThresholds.FrontSquat.BOTTOM_TO_CONCENTRIC_THRESHOLD
    override val topStabilizationThreshold = ExerciseThresholds.FrontSquat.TOP_STABILIZATION_THRESHOLD

    // Front squat requires a more upright torso (stricter than back squat) so
    // the bar does not roll off the shoulders.
    override val torsoLimits = TorsoLimits(
        kind = "torso_uprightness",
        criticalDegrees = 35f,
        warningDegrees = 25f,
        criticalSuggestion = "Keep chest up and elbows high to maintain upright torso",
        warningSuggestion = "Try to stay more upright - drive elbows up"
    )

    override val heelLiftSuggestion = "Keep entire foot flat on ground - may need to improve ankle mobility"

    /** Bar rests on the front shoulders (deltoids): shoulders are primary, head is the fallback. */
    override fun calculateBarPosition(headY: Float?, shouldersY: Float?): Float? = when {
        shouldersY != null -> shouldersY
        headY != null -> headY
        else -> null
    }

    override fun startingBarPosition(state: ExercisePhaseState): Float? = state.startingShouldersY

    override fun extraFormIssues(poseResult: PoseResult): List<FormIssue> =
        listOfNotNull(checkElbowPosition(poseResult.landmarks))

    /**
     * Check elbow position - elbows should be high and parallel to ground.
     * This is critical for front squat to keep bar secure on shoulders.
     */
    private fun checkElbowPosition(landmarks: List<com.liftr.domain.model.PoseKeypoint>): FormIssue? {
        val leftElbow = landmarks.getOrNull(13)
        val rightElbow = landmarks.getOrNull(14)
        val leftShoulder = landmarks.getOrNull(11)
        val rightShoulder = landmarks.getOrNull(12)

        if (leftElbow == null || rightElbow == null ||
            leftShoulder == null || rightShoulder == null) {
            return null
        }

        if (leftElbow.visibility < 0.5f || rightElbow.visibility < 0.5f ||
            leftShoulder.visibility < 0.5f || rightShoulder.visibility < 0.5f) {
            return null
        }

        // Calculate average elbow and shoulder Y positions
        val avgElbowY = (leftElbow.y + rightElbow.y) / 2f
        val avgShoulderY = (leftShoulder.y + rightShoulder.y) / 2f

        // Elbows should be at or above shoulder level (smaller Y = higher)
        val elbowDrop = avgElbowY - avgShoulderY

        return when {
            elbowDrop > 0.15f -> FormIssue(
                kind = "elbow_position",
                severity = Severity.CRITICAL,
                message = "Elbows dropped significantly below shoulders",
                suggestion = "Drive elbows up and keep them high throughout the movement"
            )
            elbowDrop > 0.08f -> FormIssue(
                kind = "elbow_position",
                severity = Severity.WARNING,
                message = "Elbows lowering below optimal position",
                suggestion = "Focus on keeping elbows parallel to ground"
            )
            else -> null
        }
    }
}
