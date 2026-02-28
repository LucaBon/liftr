# Feature 01: VBT Zones & Auto-Regulation

## Summary

Add velocity-based training zones with color-coded real-time feedback, automatic
velocity-loss set termination, and estimated 1RM calculation from load-velocity
profiling. This turns Lift Helper from a passive tracker into an active training tool.

## Motivation

Velocity-Based Training (VBT) is the primary differentiator for a camera-based lifting
app. The velocity data is already calculated per-rep — but it's displayed as a raw
number. Lifters need actionable context: "Is this fast enough? Should I stop the set?
What's my estimated max?"

Research basis:
- Gonzalez-Badillo et al. (2017) — velocity zones predict training intensity
- Sanchez-Medina & Gonzalez-Badillo (2011) — velocity loss correlates with fatigue
- Jidovtseff et al. (2011) — load-velocity profiles estimate 1RM within 5% error

## Design

### 1. Velocity Zones

Define training zones based on mean concentric velocity (m/s):

```
Zone              | Velocity Range | Color   | Training Quality
------------------|----------------|---------|------------------
Speed/Power       | > 1.00 m/s     | Blue    | Explosive power
Speed-Strength    | 0.75 - 1.00    | Green   | Fast strength
Strength-Speed    | 0.50 - 0.75    | Yellow  | Heavy strength
Max Strength      | 0.30 - 0.50    | Orange  | Near-maximal
Grinding          | < 0.30         | Red     | Maximal / failure
```

These are the universal zones from Gonzalez-Badillo. They apply to compound lifts
(squat, bench, deadlift). Exercise-specific overrides can be added later.

### 2. Velocity Loss Auto-Stop

Track fastest rep velocity within the current set. When velocity drops below a
configurable threshold (default: 20%), alert the user to stop. This prevents
junk volume and optimizes training stimulus.

```
velocityLossPercent = (1 - currentVelocity / fastestVelocity) * 100
```

Configurable thresholds: 10%, 15%, 20%, 25%, 30%, or disabled.

### 3. Estimated 1RM from Load-Velocity Profile

Across sessions, collect (load, meanVelocity) data points for each exercise. Fit a
linear regression. The x-intercept (velocity = 0) estimates 1RM. Minimum velocity
thresholds per exercise (from literature):

```
Exercise     | Min Velocity (1RM)
-------------|-------------------
Back Squat   | 0.30 m/s
Front Squat  | 0.30 m/s
Bench Press  | 0.17 m/s
Deadlift     | 0.15 m/s
```

Require at least 3 data points across different loads to generate a profile.

### 4. Daily Readiness Check

Compare today's velocity at a warm-up load against the historical average at that load.
If significantly slower (>10%), the lifter may be fatigued — suggest reducing intensity.

## Implementation Plan

### Step 1: Domain Model — VelocityZone

File: `domain/model/VelocityZone.kt`

```kotlin
enum class VelocityZone(
    val displayName: String,
    val minVelocity: Float,    // inclusive lower bound (m/s)
    val maxVelocity: Float,    // exclusive upper bound (m/s)
    val colorHex: Long         // ARGB color for UI
) {
    GRINDING("Grinding", 0.00f, 0.30f, 0xFFE53935),
    MAX_STRENGTH("Max Strength", 0.30f, 0.50f, 0xFFFF9800),
    STRENGTH_SPEED("Strength-Speed", 0.50f, 0.75f, 0xFFFFEB3B),
    SPEED_STRENGTH("Speed-Strength", 0.75f, 1.00f, 0xFF4CAF50),
    SPEED_POWER("Speed/Power", 1.00f, Float.MAX_VALUE, 0xFF2196F3);

    companion object {
        fun fromVelocity(velocity: MetersPerSecond): VelocityZone {
            val v = velocity.value
            return entries.first { v >= it.minVelocity && v < it.maxVelocity }
        }
    }
}
```

No Android dependencies. Pure domain logic.

### Step 2: Domain Model — VelocityLossTracker

File: `domain/exercise/VelocityLossTracker.kt`

```kotlin
data class VelocityLossState(
    val fastestVelocity: MetersPerSecond = MetersPerSecond(0f),
    val currentLossPercent: Float = 0f,
    val threshold: Float = 0.20f,        // 20% default
    val shouldStop: Boolean = false,
    val repVelocities: List<MetersPerSecond> = emptyList()
)

object VelocityLossTracker {
    fun update(
        state: VelocityLossState,
        newRepVelocity: MetersPerSecond
    ): VelocityLossState {
        val fastest = maxOf(state.fastestVelocity, newRepVelocity)
        val loss = if (fastest.value > 0f) {
            1f - (newRepVelocity.value / fastest.value)
        } else 0f
        return state.copy(
            fastestVelocity = fastest,
            currentLossPercent = loss,
            shouldStop = loss >= state.threshold,
            repVelocities = state.repVelocities + newRepVelocity
        )
    }

    fun reset(threshold: Float = 0.20f): VelocityLossState {
        return VelocityLossState(threshold = threshold)
    }
}
```

Immutable state, pure functions, no side effects.

### Step 3: Domain Model — LoadVelocityProfile (1RM Estimation)

File: `domain/model/LoadVelocityProfile.kt`

```kotlin
data class LoadVelocityPoint(
    val load: Kilograms,
    val meanVelocity: MetersPerSecond,
    val date: Long
)

data class LoadVelocityProfile(
    val exerciseType: ExerciseType,
    val dataPoints: List<LoadVelocityPoint>,
    val minVelocityAt1RM: Float       // exercise-specific threshold
) {
    fun estimatedOneRepMax(): Kilograms? {
        if (dataPoints.size < 3) return null
        // Simple linear regression: velocity = a * load + b
        // 1RM = (minVelocityAt1RM - b) / a
        val n = dataPoints.size
        val sumX = dataPoints.sumOf { it.load.value.toDouble() }
        val sumY = dataPoints.sumOf { it.meanVelocity.value.toDouble() }
        val sumXY = dataPoints.sumOf {
            it.load.value.toDouble() * it.meanVelocity.value.toDouble()
        }
        val sumX2 = dataPoints.sumOf { it.load.value.toDouble().pow(2) }

        val denominator = n * sumX2 - sumX * sumX
        if (denominator == 0.0) return null

        val a = (n * sumXY - sumX * sumY) / denominator
        val b = (sumY - a * sumX) / n

        if (a >= 0) return null // velocity should decrease with load

        val oneRM = (minVelocityAt1RM - b) / a
        return if (oneRM > 0) Kilograms(oneRM.toFloat()) else null
    }
}
```

### Step 4: Settings — Velocity Loss Threshold

Add to `SettingsKeys.kt`:
```kotlin
const val VELOCITY_LOSS_THRESHOLD = "velocity_loss_threshold"
const val VBT_ZONES_ENABLED = "vbt_zones_enabled"
```

Add to `SettingsViewModel`:
- `velocityLossThreshold: StateFlow<Float>` (0.10, 0.15, 0.20, 0.25, 0.30, or -1 for disabled)
- `vbtZonesEnabled: StateFlow<Boolean>` (default true)

Add to `SettingsScreen`:
- Velocity loss threshold dropdown (10%, 15%, 20%, 25%, 30%, Disabled)
- VBT zones toggle

### Step 5: WorkoutState Changes

Add to `WorkoutState`:
```kotlin
data class WorkoutState(
    // ... existing fields ...
    val velocityZone: VelocityZone? = null,
    val velocityLossState: VelocityLossState = VelocityLossState(),
    val estimatedOneRM: Kilograms? = null
)
```

### Step 6: WorkoutViewModel Changes

When a rep is completed (`phaseResult.repCounted == true`):

1. Determine the VelocityZone from the rep's average concentric velocity
2. Update VelocityLossState with the new rep velocity
3. If `shouldStop`, emit a stop alert (used by audio/haptic in Feature 02)
4. After set ends, store the (load, meanVelocity) data point for 1RM profiling

```kotlin
// In processFrame result handling, when repCounted:
val zone = VelocityZone.fromVelocity(repAvgVelocity)
val newLossState = VelocityLossTracker.update(
    _uiState.value.velocityLossState, repAvgVelocity
)
_uiState.update {
    it.copy(velocityZone = zone, velocityLossState = newLossState)
}
```

Reset VelocityLossState when `startRecording()` is called.

### Step 7: UI — Real-Time Feedback Overlay Update

Modify `RealTimeFeedbackOverlay` in `WorkoutScreen.kt`:

1. **Zone indicator**: Color the velocity text background with the zone color
2. **Zone label**: Show zone name below velocity number
3. **Velocity loss bar**: Horizontal progress bar showing loss % with threshold marker
4. **Stop alert**: Full-width red banner when velocity loss threshold is exceeded

```
 ┌──────────────────────────────┐
 │  0.72 m/s                    │  ← velocity text on YELLOW background
 │  Strength-Speed              │  ← zone label
 │  Rep 4 / 5                   │
 │  ▓▓▓▓▓▓▓▓▓▓▓▓░░░░░|░░░░░░  │  ← loss bar (| = threshold marker)
 │  Loss: 12%                   │
 └──────────────────────────────┘
```

When `shouldStop == true`:
```
 ┌──────────────────────────────┐
 │  ⚠ VELOCITY DROP > 20%      │  ← red banner
 │  Consider ending the set     │
 └──────────────────────────────┘
```

### Step 8: Data Persistence — Load-Velocity History

Option A (recommended): Store in existing `Rep` model — the data is already there.
Each `Rep` already has `avgVelocity: MetersPerSecond`. Each `Set` has `weight: Kilograms`
and `exerciseType: ExerciseType`. Build the LoadVelocityProfile by querying sets from
`WorkoutRepository`.

New use case: `GetLoadVelocityProfileUseCase`
```kotlin
class GetLoadVelocityProfileUseCase @Inject constructor(
    private val workoutRepository: WorkoutRepository
) {
    suspend fun execute(exerciseType: ExerciseType): LoadVelocityProfile {
        val workouts = workoutRepository.getAllWorkouts()
        val points = workouts
            .flatMap { it.sets }
            .filter { it.exerciseType == exerciseType && it.completedReps.value > 0 }
            .map { LoadVelocityPoint(it.weight, it.avgVelocity, it.timestamp) }
        return LoadVelocityProfile(
            exerciseType = exerciseType,
            dataPoints = points,
            minVelocityAt1RM = getMinVelocityForExercise(exerciseType)
        )
    }
}
```

No schema changes needed — the data already exists.

### Step 9: Display Estimated 1RM

Show in WorkoutScreen header area (when not recording) or in a post-set summary dialog:

```
Estimated 1RM: 142.5 kg
Based on 8 data points
```

Also show on the History/Analytics screen (Feature 05).

## Files to Create

| File | Layer | Purpose |
|------|-------|---------|
| `domain/model/VelocityZone.kt` | Domain | Zone enum with color mapping |
| `domain/exercise/VelocityLossTracker.kt` | Domain | Immutable velocity loss state machine |
| `domain/model/LoadVelocityProfile.kt` | Domain | 1RM estimation from load-velocity data |
| `domain/usecase/GetLoadVelocityProfileUseCase.kt` | Domain | Query historical data for profiling |

## Files to Modify

| File | Change |
|------|--------|
| `domain/repository/SettingsKeys.kt` | Add VBT settings keys |
| `ui/screens/workout/WorkoutState.kt` | Add velocityZone, velocityLossState, estimatedOneRM |
| `ui/screens/workout/WorkoutViewModel.kt` | Zone calculation, loss tracking on rep completion |
| `ui/screens/workout/WorkoutScreen.kt` | Zone-colored overlay, loss bar, stop alert banner |
| `ui/screens/settings/SettingsScreen.kt` | Velocity loss threshold picker, VBT toggle |
| `ui/screens/settings/SettingsViewModel.kt` | Manage VBT settings |
| `res/values/strings.xml` | Zone names, loss alert text, settings labels |

## Testing Plan

| Test | Type | What |
|------|------|------|
| `VelocityZoneTest` | Unit | Zone boundary mapping (edge cases at 0.30, 0.50, etc.) |
| `VelocityLossTrackerTest` | Unit | Loss calculation, threshold detection, reset behavior |
| `LoadVelocityProfileTest` | Unit | Linear regression, edge cases (<3 points, flat data, negative slope) |
| `GetLoadVelocityProfileUseCaseTest` | Unit | Filtering by exercise type, empty data |
| `WorkoutViewModelVBTTest` | Integration | Zone updates on rep, loss state transitions, reset on new set |

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Noisy velocity data triggers false zone changes | Use per-rep average velocity (already smoothed by Kalman filter), not instantaneous |
| Linear regression is poor fit for some lifters | Show R-squared value; require R^2 > 0.80 for 1RM display |
| Velocity loss is meaningless for first rep | Only start tracking from rep 2 onwards |
| Zone colors clash with form feedback | Zones are background tint; form issues are separate text overlays |
