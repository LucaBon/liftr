package com.lifthelper.feedback

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import com.lifthelper.domain.feedback.IFeedbackEmitter
import com.lifthelper.domain.model.FeedbackEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android implementation of [IFeedbackEmitter].
 *
 * Uses [TextToSpeech] for voice cues, [Vibrator] for haptic feedback,
 * and [ToneGenerator] for simple beeps. All calls are debounced to
 * avoid overwhelming the lifter during high-frequency frame processing.
 */
@Singleton
class FeedbackManager @Inject constructor(
    @ApplicationContext private val context: Context
) : IFeedbackEmitter {

    companion object {
        const val FORM_WARNING_COOLDOWN_MS = 5000L
        const val VELOCITY_ALERT_COOLDOWN_MS = 3000L
    }

    private val audioEnabled = AtomicBoolean(true)
    private val hapticEnabled = AtomicBoolean(true)

    // Debounce tracking
    private val lastFormWarningTime = ConcurrentHashMap<String, Long>()
    private val lastVelocityAlertTime = AtomicLong(0L)

    // TTS engine — initialized eagerly for low-latency first utterance.
    // ttsReady guards against speaking before the async init callback completes.
    private val ttsReady = AtomicBoolean(false)
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.getDefault()
                ttsReady.set(true)
            }
        }
    }

    // Vibrator service (API 31+ uses VibratorManager)
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    // Tone generator for depth-achieved beep
    private val toneGenerator: ToneGenerator = ToneGenerator(
        AudioManager.STREAM_MUSIC,
        ToneGenerator.MAX_VOLUME
    )

    // ── IFeedbackEmitter ──────────────────────────────────────────────

    override fun emit(event: FeedbackEvent) {
        when (event) {
            is FeedbackEvent.RepCounted -> {
                speak("${event.repNumber}", TextToSpeech.QUEUE_FLUSH)
                vibrateShort()
            }
            is FeedbackEvent.SetComplete -> {
                speak("Set complete", TextToSpeech.QUEUE_FLUSH)
                vibrateDouble()
            }
            is FeedbackEvent.VelocityLossAlert -> {
                if (shouldAlertVelocity()) {
                    speak("Velocity dropping", TextToSpeech.QUEUE_ADD)
                    vibrateLong()
                }
            }
            is FeedbackEvent.FormWarning -> {
                if (shouldAlertForm(event.issue.message)) {
                    speak(event.issue.suggestion, TextToSpeech.QUEUE_ADD)
                    vibrateMedium()
                }
            }
            is FeedbackEvent.FormCritical -> {
                if (shouldAlertForm(event.issue.message)) {
                    speak("Warning. ${event.issue.suggestion}", TextToSpeech.QUEUE_FLUSH)
                    vibrateTriple()
                }
            }
            is FeedbackEvent.DepthAchieved -> {
                playTone()
                vibrateShort()
            }
            is FeedbackEvent.PersonalRecord -> {
                speak("Personal record!", TextToSpeech.QUEUE_FLUSH)
                vibrateTriple()
            }
        }
    }

    override fun setAudioEnabled(enabled: Boolean) {
        audioEnabled.set(enabled)
        if (!enabled && ttsReady.get()) {
            tts.stop()
        }
    }

    override fun setHapticEnabled(enabled: Boolean) {
        hapticEnabled.set(enabled)
    }

    override fun stopCurrentSpeech() {
        tts.stop()
    }

    override fun shutdown() {
        tts.stop()
        tts.shutdown()
        toneGenerator.release()
    }

    // ── Audio helpers ─────────────────────────────────────────────────

    private fun speak(text: String, queueMode: Int) {
        if (!audioEnabled.get() || !ttsReady.get()) return
        tts.speak(text, queueMode, null, text.hashCode().toString())
    }

    private fun playTone() {
        if (!audioEnabled.get()) return
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
    }

    // ── Haptic helpers ────────────────────────────────────────────────

    private fun vibrateShort() {
        vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun vibrateDouble() {
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 100, 80), -1))
    }

    private fun vibrateMedium() {
        vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun vibrateLong() {
        vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun vibrateTriple() {
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60, 80, 60), -1))
    }

    private fun vibrate(effect: VibrationEffect) {
        if (!hapticEnabled.get()) return
        vibrator.vibrate(effect)
    }

    // ── Debounce helpers ──────────────────────────────────────────────

    private fun shouldAlertVelocity(): Boolean {
        val now = System.currentTimeMillis()
        val last = lastVelocityAlertTime.get()
        if (now - last < VELOCITY_ALERT_COOLDOWN_MS) return false
        return lastVelocityAlertTime.compareAndSet(last, now)
    }

    private fun shouldAlertForm(issueKey: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastFormWarningTime[issueKey] ?: 0L
        if (now - last < FORM_WARNING_COOLDOWN_MS) return false
        lastFormWarningTime[issueKey] = now
        return true
    }
}
