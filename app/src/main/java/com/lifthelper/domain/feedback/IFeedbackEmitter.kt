package com.lifthelper.domain.feedback

import com.lifthelper.domain.model.FeedbackEvent

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

    /** Flush any queued speech. Call when leaving the workout screen. */
    fun stopCurrentSpeech()

    /** Release underlying resources (TTS engine, ToneGenerator). */
    fun shutdown()
}
