package com.liftr.domain.exercise

import com.liftr.domain.calculator.CenterOfMassCalculator
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult

/** The body point that stands for the bar (or the athlete) when tracking a lift. */
enum class TrackingPoint {
    /** Midpoint of both shoulders: the bar rests there in a squat. */
    SHOULDERS,

    /** Midpoint of both wrists: the hands grip the bar. */
    WRISTS,

    /** Whole-body center of mass. */
    CENTER_OF_MASS,

    /**
     * Whole-body center of mass with the external load added at the hands
     * (the grip on the bar, which rests on the shoulders in a loaded jump),
     * falling back to the shoulder midpoint when no hand is visible.
     */
    LOADED_CENTER_OF_MASS
}

/** The single tracking point used for [type] everywhere (live, video, velocity). */
fun trackingPoint(type: ExerciseType): TrackingPoint = when (type) {
    ExerciseType.FRONT_SQUAT, ExerciseType.BACK_SQUAT, ExerciseType.AIR_SQUAT -> TrackingPoint.SHOULDERS
    ExerciseType.BENCH_PRESS,
    ExerciseType.DEADLIFT,
    ExerciseType.CLEAN,
    ExerciseType.SNATCH -> TrackingPoint.WRISTS
    ExerciseType.VERTICAL_JUMP, ExerciseType.APPROACH_JUMP -> TrackingPoint.CENTER_OF_MASS
    ExerciseType.LOADED_JUMP -> TrackingPoint.LOADED_CENTER_OF_MASS
}

/**
 * Normalized image position of [point], or null when its landmarks are not
 * visible enough.
 *
 * @param loadFraction External load as a fraction of body mass, used only by
 *   [TrackingPoint.LOADED_CENTER_OF_MASS].
 */
fun normalizedXY(pose: PoseResult, point: TrackingPoint, loadFraction: Float = 0f): Pair<Float, Float>? =
    when (point) {
        TrackingPoint.SHOULDERS -> midpoint(pose, PoseResult.LEFT_SHOULDER, PoseResult.RIGHT_SHOULDER)
        TrackingPoint.WRISTS -> midpoint(pose, PoseResult.LEFT_WRIST, PoseResult.RIGHT_WRIST)
        TrackingPoint.CENTER_OF_MASS -> CenterOfMassCalculator.centerOfMass(pose)
        TrackingPoint.LOADED_CENTER_OF_MASS -> CenterOfMassCalculator.centerOfMass(pose, loadFraction)
    }

private fun midpoint(pose: PoseResult, leftIndex: Int, rightIndex: Int): Pair<Float, Float>? {
    val a = pose.landmarks.getOrNull(leftIndex) ?: return null
    val b = pose.landmarks.getOrNull(rightIndex) ?: return null
    if (a.visibility < PoseKeypoint.VISIBILITY_THRESHOLD || b.visibility < PoseKeypoint.VISIBILITY_THRESHOLD) return null
    return Pair((a.x + b.x) / 2f, (a.y + b.y) / 2f)
}
