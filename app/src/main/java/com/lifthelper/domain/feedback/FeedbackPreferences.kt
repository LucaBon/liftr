package com.lifthelper.domain.feedback

/**
 * Spoken-feedback language selection.
 *
 * `SYSTEM` follows the device locale with a fallback to English when the
 * device locale is not a supported language.
 */
enum class FeedbackLanguage {
    SYSTEM,
    ENGLISH,
    ITALIAN;

    companion object {
        val DEFAULT = SYSTEM

        fun fromString(name: String?): FeedbackLanguage =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * How chatty spoken feedback should be during a workout.
 *
 * `MINIMAL` — rep numbers, set completion, PR, rest-complete, critical form
 *             alerts, tempo cues. Form warnings and velocity-loss alerts are
 *             suppressed.
 * `NORMAL`  — `MINIMAL` plus form warnings (longer cooldown) and velocity-loss
 *             alerts.
 * `VERBOSE` — current behaviour: same as `NORMAL` with tighter cooldowns.
 */
enum class FeedbackVerbosity {
    MINIMAL,
    NORMAL,
    VERBOSE;

    companion object {
        val DEFAULT = MINIMAL

        fun fromString(name: String?): FeedbackVerbosity =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * A TTS engine installed on the device, as reported by the platform.
 *
 * @property packageName Engine package (e.g. `com.google.android.tts`). Pass
 *                       this back to [IFeedbackEmitter.setTtsEngine].
 * @property label Human-readable label for display in the Settings UI.
 */
data class TtsEngineInfo(
    val packageName: String,
    val label: String
)
