package com.lifthelper.domain.repository

/**
 * Constants for settings keys used in the application.
 * Centralizes all setting keys to avoid typos and ensure consistency.
 */
object SettingsKeys {
    /**
     * Squat depth type setting for back squat exercise.
     * Value should be a SquatDepthType enum name (e.g., "QUARTER", "HALF", "POWERLIFTING", "FULL")
     */
    const val SQUAT_DEPTH_BACK_SQUAT = "squat_depth_back_squat"

    /**
     * Squat depth type setting for front squat exercise.
     * Value should be a SquatDepthType enum name (e.g., "QUARTER", "HALF", "POWERLIFTING", "FULL")
     */
    const val SQUAT_DEPTH_FRONT_SQUAT = "squat_depth_front_squat"

    /**
     * Whether VBT zone display is enabled on the workout screen.
     * Boolean value, default true.
     */
    const val VBT_ZONES_ENABLED = "vbt_zones_enabled"

    /**
     * Velocity loss threshold for auto-stop alerts.
     * Float value: 0.10, 0.15, 0.20, 0.25, 0.30 or -1.0 for disabled.
     * Default: 0.20 (20%).
     */
    const val VELOCITY_LOSS_THRESHOLD = "velocity_loss_threshold"

    /**
     * Whether audio feedback (TTS voice cues + beeps) is enabled during workouts.
     * Boolean value, default true.
     */
    const val AUDIO_FEEDBACK_ENABLED = "audio_feedback_enabled"

    /**
     * Whether haptic feedback (vibration pulses) is enabled during workouts.
     * Boolean value, default true.
     */
    const val HAPTIC_FEEDBACK_ENABLED = "haptic_feedback_enabled"

    /**
     * Spoken feedback language. Stored as a [com.lifthelper.domain.feedback.FeedbackLanguage] name.
     * Default: `SYSTEM` (follow device locale, falling back to English).
     */
    const val FEEDBACK_LANGUAGE = "feedback_language"

    /**
     * Verbosity level for spoken feedback. Stored as a
     * [com.lifthelper.domain.feedback.FeedbackVerbosity] name. Default: `MINIMAL`.
     */
    const val FEEDBACK_VERBOSITY = "feedback_verbosity"

    /**
     * Preferred Android TTS engine package name (e.g. `com.google.android.tts`).
     * Empty string means "use the system default engine".
     */
    const val FEEDBACK_TTS_ENGINE = "feedback_tts_engine"

    // Rest Timer
    const val REST_TIMER_ENABLED = "rest_timer_enabled"
    const val REST_DURATION_SQUAT = "rest_duration_squat"
    const val REST_DURATION_BENCH = "rest_duration_bench"
    const val REST_DURATION_DEADLIFT = "rest_duration_deadlift"
    const val REST_DURATION_DEFAULT = "rest_duration_default"

    // Tempo Training
    const val TEMPO_MODE_ENABLED = "tempo_mode_enabled"
    const val LAST_TEMPO_PRESCRIPTION = "last_tempo_prescription"

    /**
     * Whether the app should auto-stop recording when the configured
     * target reps for the set are reached. Boolean, default true.
     */
    const val AUTO_STOP_ON_TARGET_REPS = "auto_stop_on_target_reps"

    /**
     * Whether the first-launch onboarding tutorial has been completed.
     * Boolean, default false — drives NavGraph's initial destination and
     * the "Replay Tutorial" entry in Settings.
     */
    const val ONBOARDING_COMPLETED = "onboarding_completed"

    /**
     * Analysis mode for tracked sets (Feature 17).
     * String value: "post_exercise" (default) or "real_time" (legacy).
     */
    const val ANALYSIS_MODE = "analysis_mode"

    /** Value for [ANALYSIS_MODE]: record video then analyze with the heavy model. */
    const val ANALYSIS_MODE_POST_EXERCISE = "post_exercise"

    /** Value for [ANALYSIS_MODE]: legacy per-frame real-time analysis. */
    const val ANALYSIS_MODE_REAL_TIME = "real_time"

    /**
     * Whether to run a lightweight lite-model rep counter during post-exercise
     * recording. Boolean, default false (recording-only for best video quality).
     */
    const val LIGHTWEIGHT_REP_COUNTER = "lightweight_rep_counter"

    /**
     * Whether to render a shareable annotated workout video (skeleton + velocity
     * + rep counter burned in) after post-exercise analysis (Feature 09).
     * Boolean, default false (opt-in — adds render time and storage use).
     */
    const val SAVE_ANNOTATED_VIDEO = "save_annotated_video"
}
