package com.liftr.domain.model

import com.liftr.domain.exercise.FormIssue
import com.liftr.domain.model.MovementPhase

/**
 * Events that should trigger audio and/or haptic feedback during a workout.
 *
 * These are domain-level events — the concrete audio/haptic implementation
 * lives behind [com.liftr.domain.feedback.IFeedbackEmitter].
 */
sealed class FeedbackEvent {
    /** A rep was just completed. */
    data class RepCounted(val repNumber: Int) : FeedbackEvent()

    /** A jump just landed; announced by height instead of rep number. */
    data class JumpMeasured(val heightCm: Int) : FeedbackEvent()

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

    /**
     * The most recent rep failed depth validation (squats only). Emitted once
     * at rep-completion time and used to drive both audio and the visual
     * red-flash cue on the rep counter.
     */
    data object DepthInsufficient : FeedbackEvent()

    /** A personal record was just broken. */
    data object PersonalRecord : FeedbackEvent()

    /** Rest timer has completed — time to start the next set. */
    data object RestComplete : FeedbackEvent()

    /** Tempo countdown tick during a prescribed phase. */
    data class TempoCountdown(val remainingSeconds: Int, val phase: MovementPhase) : FeedbackEvent()

    /** Tempo hold cue during a pause phase (bottom or top). */
    data class TempoHold(val remainingSeconds: Int) : FeedbackEvent()

    /** Tempo speed correction cue. */
    data class TempoSpeedCue(val isTooFast: Boolean) : FeedbackEvent()
}
