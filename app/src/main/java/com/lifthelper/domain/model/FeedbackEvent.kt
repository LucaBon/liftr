package com.lifthelper.domain.model

import com.lifthelper.domain.exercise.FormIssue

/**
 * Events that should trigger audio and/or haptic feedback during a workout.
 *
 * These are domain-level events — the concrete audio/haptic implementation
 * lives behind [com.lifthelper.domain.feedback.IFeedbackEmitter].
 */
sealed class FeedbackEvent {
    /** A rep was just completed. */
    data class RepCounted(val repNumber: Int) : FeedbackEvent()

    /** The lifter has reached the target number of reps for this set. */
    data object SetComplete : FeedbackEvent()

    /** Velocity loss has exceeded the configured threshold — lifter should stop. */
    data class VelocityLossAlert(val lossPercent: Float) : FeedbackEvent()

    /** A non-critical form issue (e.g. slight knee cave). */
    data class FormWarning(val issue: FormIssue) : FeedbackEvent()

    /** A critical form issue that could lead to injury (e.g. back rounding). */
    data class FormCritical(val issue: FormIssue) : FeedbackEvent()

    /** The lifter has reached sufficient squat depth. */
    data object DepthAchieved : FeedbackEvent()

    /** A personal record was just broken. */
    data object PersonalRecord : FeedbackEvent()
}
