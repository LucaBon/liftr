package com.liftr.domain.feedback

import com.liftr.domain.model.FeedbackEvent

/**
 * Emits audio and haptic feedback in response to workout events.
 *
 * Domain-layer interface — the Android implementation (TTS, Vibrator, ToneGenerator)
 * lives in the `feedback` package outside domain.
 */
interface IFeedbackEmitter {
    /** Emit feedback for the given event (audio + haptic, subject to debouncing). */
    fun emit(event: FeedbackEvent)

    /** Enable or disable audio feedback (TTS + tones). */
    fun setAudioEnabled(enabled: Boolean)

    /** Enable or disable haptic feedback (vibrations). */
    fun setHapticEnabled(enabled: Boolean)

    /** Select the spoken feedback language (and the TTS voice locale). */
    fun setLanguage(language: FeedbackLanguage)

    /** Select how chatty spoken feedback should be. */
    fun setVerbosity(verbosity: FeedbackVerbosity)

    /**
     * Switch to a specific TTS engine package. Passing `null` or the empty string
     * re-initializes with the system default engine. Installed engines can be
     * enumerated with [getAvailableEngines].
     */
    fun setTtsEngine(packageName: String?)

    /** List TTS engines currently installed on the device. */
    fun getAvailableEngines(): List<TtsEngineInfo>

    /** Flush any queued speech. Call when leaving the workout screen. */
    fun stopCurrentSpeech()

    /** Release underlying resources (TTS engine, ToneGenerator). */
    fun shutdown()
}
