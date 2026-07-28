package com.lifthelper.feedback

import timber.log.Timber
import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.lifthelper.R
import com.lifthelper.domain.feedback.FeedbackLanguage
import com.lifthelper.domain.feedback.FeedbackVerbosity
import com.lifthelper.domain.feedback.IFeedbackEmitter
import com.lifthelper.domain.feedback.TtsEngineInfo
import com.lifthelper.domain.model.FeedbackEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android implementation of [IFeedbackEmitter].
 *
 * Uses [TextToSpeech] for voice cues, [Vibrator] for haptic feedback,
 * and [ToneGenerator] for simple beeps.
 *
 * Language, verbosity and TTS engine are configured from settings via
 * [setLanguage], [setVerbosity] and [setTtsEngine]. Spoken strings are loaded
 * from a locale-specific Android [Context] so the text handed to TTS always
 * matches the voice locale — otherwise an Italian voice would try to read
 * English words, which is what made pronunciation sound garbled.
 */
@Singleton
class FeedbackManager @Inject constructor(
    @ApplicationContext private val appContext: Context
) : IFeedbackEmitter {

    companion object {
        /** Per-category minimum interval — no event category fires more often than this. */
        const val GLOBAL_CATEGORY_COOLDOWN_MS = 1500L
        const val FORM_WARNING_COOLDOWN_NORMAL_MS = 10_000L
        const val FORM_WARNING_COOLDOWN_VERBOSE_MS = 5_000L
        const val VELOCITY_ALERT_COOLDOWN_NORMAL_MS = 5_000L
        const val VELOCITY_ALERT_COOLDOWN_VERBOSE_MS = 3_000L
    }

    private val audioEnabled = AtomicBoolean(true)
    private val hapticEnabled = AtomicBoolean(true)
    private val verbosity = AtomicReference(FeedbackVerbosity.DEFAULT)
    private val currentLocale = AtomicReference(resolveLocale(FeedbackLanguage.DEFAULT))
    private val currentEngine = AtomicReference<String?>(null)

    /** Context bound to the selected TTS locale — used for `getString` lookups. */
    @Volatile
    private var localizedContext: Context = localizedContextFor(currentLocale.get())

    // Debounce tracking
    private val lastCategoryTime = ConcurrentHashMap<String, Long>()
    private val lastFormWarningTime = ConcurrentHashMap<String, Long>()
    private val lastVelocityAlertTime = AtomicLong(0L)

    // TTS engine — initialized eagerly for low-latency first utterance.
    private val ttsReady = AtomicBoolean(false)
    @Volatile
    private var tts: TextToSpeech = createTts(engine = null)

    // Vibrator service (API 31+ uses VibratorManager)
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    /**
     * Built lazily and defensively: the ToneGenerator constructor throws
     * RuntimeException("Init failed") when the media server cannot allocate a
     * tone resource. As an eagerly-initialised field of a @Singleton that
     * exception escapes Hilt construction and crashes the app the first time
     * anything injects feedback. Audio cues are optional, so degrade to silence.
     */
    private val toneGenerator: ToneGenerator? by lazy {
        runCatching {
            ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)
        }.onFailure {
            Timber.w(it, "ToneGenerator unavailable, tone cues disabled")
        }.getOrNull()
    }

    // ── IFeedbackEmitter ──────────────────────────────────────────────

    override fun emit(event: FeedbackEvent) {
        val level = verbosity.get()
        when (event) {
            is FeedbackEvent.RepCounted -> {
                if (!throttleCategory("rep")) return
                speak("${event.repNumber}", TextToSpeech.QUEUE_FLUSH)
                vibrateShort()
            }
            is FeedbackEvent.SetComplete -> {
                if (!throttleCategory("set")) return
                speakRes(R.string.tts_set_complete, TextToSpeech.QUEUE_FLUSH)
                vibrateDouble()
            }
            is FeedbackEvent.VelocityLossAlert -> {
                if (level == FeedbackVerbosity.MINIMAL) return
                if (!shouldAlertVelocity(level)) return
                if (!throttleCategory("velocity")) return
                speakRes(R.string.tts_velocity_drop, TextToSpeech.QUEUE_ADD)
                vibrateLong()
            }
            is FeedbackEvent.FormWarning -> {
                if (level == FeedbackVerbosity.MINIMAL) return
                if (!shouldAlertForm(event.issue.message, level)) return
                if (!throttleCategory("form_warning")) return
                speakRes(R.string.tts_form_warning, TextToSpeech.QUEUE_ADD)
                vibrateMedium()
            }
            is FeedbackEvent.FormCritical -> {
                if (!shouldAlertForm(event.issue.message, level)) return
                if (!throttleCategory("form_critical")) return
                speakRes(R.string.tts_form_critical, TextToSpeech.QUEUE_FLUSH)
                vibrateTriple()
            }
            is FeedbackEvent.DepthAchieved -> {
                if (!throttleCategory("depth")) return
                playTone()
                if (level != FeedbackVerbosity.MINIMAL) vibrateShort()
            }
            is FeedbackEvent.DepthInsufficient -> {
                if (!throttleCategory("depth_insufficient")) return
                speakRes(R.string.tts_depth_insufficient, TextToSpeech.QUEUE_ADD)
                vibrateDouble()
            }
            is FeedbackEvent.PersonalRecord -> {
                if (!throttleCategory("pr")) return
                speakRes(R.string.tts_personal_record, TextToSpeech.QUEUE_FLUSH)
                vibrateTriple()
            }
            is FeedbackEvent.RestComplete -> {
                if (!throttleCategory("rest")) return
                speakRes(R.string.tts_rest_complete, TextToSpeech.QUEUE_FLUSH)
                vibrateDouble()
            }
            is FeedbackEvent.TempoCountdown -> {
                if (!throttleCategory("tempo_count")) return
                speak("${event.remainingSeconds}", TextToSpeech.QUEUE_FLUSH)
            }
            is FeedbackEvent.TempoHold -> {
                if (event.remainingSeconds <= 0) return
                if (!throttleCategory("tempo_hold")) return
                speak(
                    localizedContext.getString(R.string.tts_tempo_hold, event.remainingSeconds),
                    TextToSpeech.QUEUE_FLUSH
                )
            }
            is FeedbackEvent.TempoSpeedCue -> {
                if (!throttleCategory("tempo_speed")) return
                val resId = if (event.isTooFast) R.string.tts_tempo_slower else R.string.tts_tempo_faster
                speakRes(resId, TextToSpeech.QUEUE_FLUSH)
                vibrateMedium()
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

    override fun setLanguage(language: FeedbackLanguage) {
        val locale = resolveLocale(language)
        if (locale == currentLocale.get()) return
        currentLocale.set(locale)
        localizedContext = localizedContextFor(locale)
        if (ttsReady.get()) applyLocaleToTts(locale)
    }

    override fun setVerbosity(verbosity: FeedbackVerbosity) {
        this.verbosity.set(verbosity)
    }

    override fun setTtsEngine(packageName: String?) {
        val desired = packageName?.takeIf { it.isNotBlank() }
        if (desired == currentEngine.get()) return
        currentEngine.set(desired)
        // Re-init the engine. stop()+shutdown() first to release the old instance.
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
        ttsReady.set(false)
        tts = createTts(engine = desired)
    }

    override fun getAvailableEngines(): List<TtsEngineInfo> {
        return try {
            tts.engines.orEmpty().map { info ->
                val label = runCatching { info.label.ifBlank { info.name } }.getOrDefault(info.name)
                TtsEngineInfo(packageName = info.name, label = label)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun stopCurrentSpeech() {
        runCatching { tts.stop() }
    }

    override fun shutdown() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
        runCatching { toneGenerator?.release() }
    }

    // ── TTS bootstrap ─────────────────────────────────────────────────

    private fun createTts(engine: String?): TextToSpeech {
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                applyLocaleToTts(currentLocale.get())
                ttsReady.set(true)
            }
        }
        return if (engine.isNullOrBlank()) {
            TextToSpeech(appContext, listener)
        } else {
            TextToSpeech(appContext, listener, engine)
        }
    }

    /**
     * Set the TTS language to [locale] and select the highest-quality installed
     * voice for it — this is what makes pronunciation actually sound decent,
     * since many devices ship both a compressed and a neural voice per locale.
     */
    private fun applyLocaleToTts(locale: Locale) {
        val setResult = runCatching { tts.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (setResult == TextToSpeech.LANG_MISSING_DATA || setResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Fall back to English — the default resource strings are English.
            runCatching { tts.setLanguage(Locale.ENGLISH) }
        }
        val bestVoice = pickBestVoice(locale) ?: pickBestVoice(Locale.ENGLISH)
        if (bestVoice != null) {
            runCatching { tts.voice = bestVoice }
        }
    }

    private fun pickBestVoice(locale: Locale): Voice? {
        val voices = runCatching { tts.voices }.getOrNull().orEmpty()
        if (voices.isEmpty()) return null
        return voices.asSequence()
            .filter { it.locale.language == locale.language }
            .filter { !it.isNetworkConnectionRequired }
            .filter { !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .maxByOrNull { it.quality }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun speakRes(resId: Int, queueMode: Int) {
        speak(localizedContext.getString(resId), queueMode)
    }

    private fun speak(text: String, queueMode: Int) {
        if (!audioEnabled.get() || !ttsReady.get()) return
        tts.speak(text, queueMode, null, text.hashCode().toString())
    }

    private fun playTone() {
        if (!audioEnabled.get()) return
        val generator = toneGenerator ?: return
        runCatching { generator.startTone(ToneGenerator.TONE_PROP_BEEP, 150) }
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

    // ── Throttling ────────────────────────────────────────────────────

    private fun throttleCategory(category: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastCategoryTime[category] ?: 0L
        if (now - last < GLOBAL_CATEGORY_COOLDOWN_MS) return false
        lastCategoryTime[category] = now
        return true
    }

    private fun shouldAlertVelocity(level: FeedbackVerbosity): Boolean {
        val cooldown = if (level == FeedbackVerbosity.VERBOSE)
            VELOCITY_ALERT_COOLDOWN_VERBOSE_MS else VELOCITY_ALERT_COOLDOWN_NORMAL_MS
        val now = System.currentTimeMillis()
        val last = lastVelocityAlertTime.get()
        if (now - last < cooldown) return false
        return lastVelocityAlertTime.compareAndSet(last, now)
    }

    private fun shouldAlertForm(issueKey: String, level: FeedbackVerbosity): Boolean {
        val cooldown = if (level == FeedbackVerbosity.VERBOSE)
            FORM_WARNING_COOLDOWN_VERBOSE_MS else FORM_WARNING_COOLDOWN_NORMAL_MS
        val now = System.currentTimeMillis()
        val last = lastFormWarningTime[issueKey] ?: 0L
        if (now - last < cooldown) return false
        lastFormWarningTime[issueKey] = now
        return true
    }

    // ── Locale resolution ─────────────────────────────────────────────

    private fun resolveLocale(language: FeedbackLanguage): Locale = when (language) {
        FeedbackLanguage.SYSTEM -> {
            val system = Locale.getDefault()
            if (system.language == "it") Locale.ITALIAN else Locale.ENGLISH
        }
        FeedbackLanguage.ENGLISH -> Locale.ENGLISH
        FeedbackLanguage.ITALIAN -> Locale.ITALIAN
    }

    private fun localizedContextFor(locale: Locale): Context {
        val config = Configuration(appContext.resources.configuration)
        config.setLocale(locale)
        return appContext.createConfigurationContext(config)
    }
}
