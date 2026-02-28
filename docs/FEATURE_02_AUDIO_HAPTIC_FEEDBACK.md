# Feature 02: Audio & Haptic Feedback

## Summary

Add voice cues, haptic pulses, and audio rep counting so lifters get feedback without
looking at the screen. This is the single most impactful UX improvement — you can't
read your phone mid-squat.

## Motivation

Current feedback is 100% visual. During a heavy set, lifters:
- Cannot look at the phone (it's on a tripod or propped up)
- Need hands-free confirmation that reps are counting
- Want immediate alerts about form issues
- Need to know when to stop (velocity loss, target reps)

Audio and haptic feedback solves all of these with minimal implementation effort.

## Design

### Feedback Events

| Event | Audio | Haptic | Priority |
|-------|-------|--------|----------|
| Rep counted | "One", "Two", ... (TTS) | Short pulse (50ms) | High |
| Target reps reached | "Set complete" (TTS) | Double pulse | High |
| Velocity loss alert | "Velocity dropping" (TTS) | Long vibration (300ms) | High |
| Form issue (WARNING) | "Watch your knees" (TTS) | Medium pulse (100ms) | Medium |
| Form issue (CRITICAL) | "Stop — back rounding" (TTS) | Triple pulse | High |
| Good depth achieved | Short beep | Short pulse (30ms) | Low |
| Zone change | (none — too frequent) | (none) | — |

### Audio System

Use Android `TextToSpeech` for voice cues. Advantages over pre-recorded clips:
- Supports dynamic content (rep numbers, velocity values)
- Multilingual out of the box
- No asset files needed
- Queuing built in (QUEUE_ADD vs QUEUE_FLUSH)

For the depth-achieved beep, use `ToneGenerator` (simple, no assets).

### Haptic System

Use `Vibrator` service with `VibrationEffect` patterns:

```kotlin
// Short pulse - rep counted
VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)

// Double pulse - set complete
VibrationEffect.createWaveform(longArrayOf(0, 80, 100, 80), -1)

// Long vibration - velocity alert
VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE)

// Triple pulse - critical form issue
VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60, 80, 60), -1)
```

### Debouncing & Rate Limiting

Form issues fire every frame. Audio/haptic must be debounced:
- **Form warnings**: Max once per 5 seconds per issue type
- **Velocity loss**: Max once per 3 seconds
- **Rep count**: Immediate (no debounce — one per rep)
- **Set complete**: Immediate, once

### Settings

Users must be able to control:
- Master audio toggle (on/off)
- Master haptic toggle (on/off)
- Voice language (system default or manual pick)
- Volume (system media volume — no custom slider needed)
- Individual event toggles (advanced, optional for v1)

## Implementation Plan

### Step 1: Domain Layer — Feedback Events

File: `domain/model/FeedbackEvent.kt`

```kotlin
sealed class FeedbackEvent {
    data class RepCounted(val repNumber: Int) : FeedbackEvent()
    data object SetComplete : FeedbackEvent()
    data class VelocityLossAlert(val lossPercent: Float) : FeedbackEvent()
    data class FormWarning(val issue: FormIssue) : FeedbackEvent()
    data class FormCritical(val issue: FormIssue) : FeedbackEvent()
    data object DepthAchieved : FeedbackEvent()
}
```

This stays in the domain layer (no Android dependencies, just data).

### Step 2: Domain Interface — FeedbackEmitter

File: `domain/feedback/IFeedbackEmitter.kt`

```kotlin
interface IFeedbackEmitter {
    fun emit(event: FeedbackEvent)
    fun setAudioEnabled(enabled: Boolean)
    fun setHapticEnabled(enabled: Boolean)
    fun shutdown()
}
```

Domain layer interface. Android implementation lives in a new `feedback/` package.

### Step 3: Android Implementation — FeedbackManager

File: `feedback/FeedbackManager.kt`

```kotlin
@Singleton
class FeedbackManager @Inject constructor(
    @ApplicationContext private val context: Context
) : IFeedbackEmitter {

    private val tts: TextToSpeech
    private val vibrator: Vibrator
    private val toneGenerator: ToneGenerator

    // Debounce tracking
    private val lastFormWarningTime = ConcurrentHashMap<String, Long>()
    private val lastVelocityAlertTime = AtomicLong(0)

    private var audioEnabled = AtomicBoolean(true)
    private var hapticEnabled = AtomicBoolean(true)

    companion object {
        const val FORM_WARNING_COOLDOWN_MS = 5000L
        const val VELOCITY_ALERT_COOLDOWN_MS = 3000L
    }

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
        }
    }

    private fun speak(text: String, queueMode: Int) { /* TTS call */ }
    private fun vibrateShort() { /* 50ms */ }
    private fun vibrateDouble() { /* 80+100+80 pattern */ }
    private fun vibrateMedium() { /* 100ms */ }
    private fun vibrateLong() { /* 300ms */ }
    private fun vibrateTriple() { /* 60+80+60+80+60 pattern */ }
    private fun playTone() { /* ToneGenerator.TONE_PROP_BEEP */ }

    private fun shouldAlertVelocity(): Boolean { /* cooldown check */ }
    private fun shouldAlertForm(issueKey: String): Boolean { /* cooldown check */ }

    override fun shutdown() {
        tts.shutdown()
        toneGenerator.release()
    }
}
```

### Step 4: Hilt Module

File: `di/FeedbackModule.kt`

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class FeedbackModule {
    @Binds
    @Singleton
    abstract fun bindFeedbackEmitter(impl: FeedbackManager): IFeedbackEmitter
}
```

### Step 5: Integrate into WorkoutViewModel

Inject `IFeedbackEmitter` into `WorkoutViewModel`. Emit events at the right moments:

```kotlin
// When rep is counted:
feedbackEmitter.emit(FeedbackEvent.RepCounted(newRepCount))

// When target reps reached:
if (newRepCount >= targetReps.value) {
    feedbackEmitter.emit(FeedbackEvent.SetComplete)
}

// When velocity loss threshold exceeded (from Feature 01):
if (newLossState.shouldStop) {
    feedbackEmitter.emit(FeedbackEvent.VelocityLossAlert(newLossState.currentLossPercent))
}

// When form issues detected:
formValidation.issues.forEach { issue ->
    when (issue.severity) {
        Severity.WARNING -> feedbackEmitter.emit(FeedbackEvent.FormWarning(issue))
        Severity.CRITICAL -> feedbackEmitter.emit(FeedbackEvent.FormCritical(issue))
        Severity.INFO -> { /* no audio/haptic for info */ }
    }
}
```

### Step 6: Settings Integration

Add to `SettingsKeys.kt`:
```kotlin
const val AUDIO_FEEDBACK_ENABLED = "audio_feedback_enabled"
const val HAPTIC_FEEDBACK_ENABLED = "haptic_feedback_enabled"
```

Add to `SettingsScreen.kt`:
- "Audio Feedback" toggle (default: on)
- "Haptic Feedback" toggle (default: on)

`SettingsViewModel` observes these and calls `feedbackEmitter.setAudioEnabled()` /
`setHapticEnabled()` when they change.

### Step 7: Lifecycle Management

In `WorkoutScreen.kt`, use `DisposableEffect` to handle TTS lifecycle:

```kotlin
DisposableEffect(Unit) {
    onDispose {
        // FeedbackManager is singleton, but we stop any queued speech
        // when leaving the workout screen
        feedbackEmitter.stopCurrentSpeech()
    }
}
```

The TTS engine itself lives as long as the app (singleton), but queued utterances
are flushed when navigating away from the workout.

## Files to Create

| File | Layer | Purpose |
|------|-------|---------|
| `domain/model/FeedbackEvent.kt` | Domain | Sealed class of feedback events |
| `domain/feedback/IFeedbackEmitter.kt` | Domain | Interface for feedback emission |
| `feedback/FeedbackManager.kt` | App | Android TTS + Vibrator implementation |
| `di/FeedbackModule.kt` | DI | Hilt binding |

## Files to Modify

| File | Change |
|------|--------|
| `domain/repository/SettingsKeys.kt` | Add audio/haptic toggle keys |
| `ui/screens/workout/WorkoutViewModel.kt` | Inject IFeedbackEmitter, emit events on rep/form/loss |
| `ui/screens/settings/SettingsScreen.kt` | Add audio/haptic toggles |
| `ui/screens/settings/SettingsViewModel.kt` | Manage audio/haptic preferences |
| `res/values/strings.xml` | Settings labels, TTS fallback strings |
| `AndroidManifest.xml` | Add VIBRATE permission (if not already present) |

## Testing Plan

| Test | Type | What |
|------|------|------|
| `FeedbackEventTest` | Unit | Event creation, sealed class exhaustiveness |
| `FeedbackManagerTest` | Unit | Debounce logic (mock TTS/Vibrator) |
| `WorkoutViewModelFeedbackTest` | Integration | Events emitted at correct moments |

Note: TTS and Vibrator are Android framework classes — mock them in unit tests.
Integration/UI tests on device would use `Espresso` or manual QA.

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| TTS latency (100-500ms init) | Initialize TTS eagerly on app start (singleton) |
| TTS not available on device | Check `TextToSpeech.SUCCESS` on init; fall back to ToneGenerator beeps |
| Too many voice cues feel annoying | Aggressive debouncing; prioritize CRITICAL over WARNING; QUEUE_FLUSH for urgent |
| Vibration not felt during heavy lifts | Offer "strong haptic" option with longer durations |
| Battery drain from continuous vibration | Haptic pulses are <300ms each, negligible impact |
