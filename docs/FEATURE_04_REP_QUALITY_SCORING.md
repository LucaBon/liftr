# Feature 04: Rep Quality Scoring & Post-Set Summary

## Summary

Score every rep on a 0-100 scale combining depth, bar path, tempo, and form.
Show a post-set summary card with per-rep breakdown, best/worst rep, and a velocity
curve chart. This transforms raw data into actionable training insight.

## Motivation

The app already detects form issues and tracks depth, velocity, and timing — but this
data is scattered and ephemeral (shown briefly during the lift, then gone). Lifters
need:

1. A single number per rep ("How good was that?")
2. A summary after each set ("What should I fix next set?")
3. Historical comparison ("Am I getting better at form?")

A quality score creates a gamification loop: lifters try to beat their score, which
naturally improves their form.

## Design

### Rep Quality Score (0-100)

Score is a weighted sum of 4 components:

```
Component         | Weight | Max Points | What It Measures
------------------|--------|------------|------------------
Depth             | 30%    | 30         | Did the rep reach required depth?
Bar Path          | 20%    | 20         | How straight/consistent was the bar path?
Tempo             | 20%    | 20         | Was eccentric/concentric controlled and symmetric?
Form              | 30%    | 30         | Were there form violations?
```

#### Depth Scoring (30 points)

For squats:
- Full depth achieved with confidence > 0.8: 30 points
- Full depth achieved with confidence 0.6-0.8: 25 points
- Depth achieved by simple check only: 20 points
- 90% of required depth: 15 points
- 80% of required depth: 10 points
- Less than 80%: 0 points

For bench press:
- Bar touches chest (lowest point within 3cm of chest): 30 points
- Bar within 5cm of chest: 20 points
- Partial ROM: 10 points

For deadlift:
- Full lockout (hip + knee extension > 170°): 30 points
- Partial lockout (> 160°): 20 points
- No lockout: 0 points

#### Bar Path Scoring (20 points)

Measure horizontal deviation of the bar tracking point across the rep:

```kotlin
// Collect bar X position at each frame during the rep
// Calculate standard deviation of X positions
// Lower deviation = straighter bar path = higher score

val maxDeviation = 0.10f  // 10cm = worst acceptable
val deviation = barXPositions.standardDeviation()
val score = ((1f - (deviation / maxDeviation)).coerceIn(0f, 1f) * 20).toInt()
```

For bench press, the ideal bar path is a slight J-curve (not perfectly straight),
so measure deviation from the expected J-curve instead of a straight line.

#### Tempo Scoring (20 points)

Score based on controlled tempo:

```kotlin
// Eccentric should be ≥ 1.0 second (controlled descent)
// Concentric can be any speed (faster = more power)
// Penalize very fast eccentrics (< 0.5s = dropping the weight)
// Penalize very slow concentrics relative to the lifter's capability

val eccentricScore = when {
    eccentricDuration >= 2.0f -> 10  // Very controlled
    eccentricDuration >= 1.0f -> 10  // Controlled
    eccentricDuration >= 0.5f -> 5   // A bit fast
    else -> 0                         // Dropping weight
}

// Concentric consistency: compare to average of previous reps in set
val concentricConsistency = if (previousReps.isNotEmpty()) {
    val avgConcentric = previousReps.map { it.concentricDuration }.average()
    val deviation = abs(concentricDuration - avgConcentric) / avgConcentric
    ((1f - deviation).coerceIn(0f, 1f) * 10).toInt()
} else 10  // First rep gets full marks

val tempoScore = eccentricScore + concentricConsistency
```

#### Form Scoring (30 points)

Start at 30, subtract for each issue:

```kotlin
var formScore = 30
formIssues.forEach { issue ->
    when (issue.severity) {
        Severity.CRITICAL -> formScore -= 15
        Severity.WARNING -> formScore -= 8
        Severity.INFO -> formScore -= 3
    }
}
formScore = formScore.coerceAtLeast(0)
```

### Post-Set Summary Card

Shown as a dialog/bottom sheet after `stopRecording()`:

```
 ┌─────────────────────────────────────────┐
 │  SET SUMMARY                            │
 │  Back Squat · 100 kg · 5/5 reps         │
 │                                         │
 │  Overall Score: 82/100                  │
 │  ████████████████████░░░░  82%          │
 │                                         │
 │  Rep  Vel    Depth  Form  Tempo  Score  │
 │  ───  ─────  ─────  ────  ─────  ─────  │
 │   1   0.82   ✓      30    18     88     │
 │   2   0.78   ✓      30    20     90  ★  │  ← best
 │   3   0.71   ✓      22    18     78     │
 │   4   0.63   ✓      22    16     72     │
 │   5   0.55   ~      15    14     65  ▼  │  ← worst
 │                                         │
 │  ┌─── Velocity Curve ───────────────┐   │
 │  │  ·                               │   │
 │  │   ·                              │   │
 │  │     ·                            │   │
 │  │        ·                         │   │
 │  │            ·                     │   │
 │  └──────────────────────────────────┘   │
 │                                         │
 │  Form Issues:                           │
 │  ⚠ Knees caving (reps 3-5)             │
 │  ℹ Depth borderline (rep 5)             │
 │                                         │
 │  Avg Velocity: 0.70 m/s                │
 │  Velocity Loss: 33%                     │
 │                                         │
 │  [Dismiss]              [Save Notes]    │
 └─────────────────────────────────────────┘
```

## Implementation Plan

### Step 1: Domain Model — RepQualityScore

File: `domain/model/RepQualityScore.kt`

```kotlin
data class RepQualityScore(
    val totalScore: Int,               // 0-100
    val depthScore: Int,               // 0-30
    val barPathScore: Int,             // 0-20
    val tempoScore: Int,               // 0-20
    val formScore: Int,                // 0-30
    val depthAchieved: Boolean,
    val formIssues: List<FormIssue>
) {
    val grade: ScoreGrade get() = ScoreGrade.fromScore(totalScore)
}

enum class ScoreGrade(val label: String, val colorHex: Long) {
    EXCELLENT("Excellent", 0xFF4CAF50),   // 90-100
    GOOD("Good", 0xFF8BC34A),             // 75-89
    FAIR("Fair", 0xFFFFEB3B),             // 60-74
    POOR("Poor", 0xFFFF9800),             // 40-59
    BAD("Bad", 0xFFE53935);              // 0-39

    companion object {
        fun fromScore(score: Int): ScoreGrade = when {
            score >= 90 -> EXCELLENT
            score >= 75 -> GOOD
            score >= 60 -> FAIR
            score >= 40 -> POOR
            else -> BAD
        }
    }
}
```

### Step 2: Domain Model — SetSummary

File: `domain/model/SetSummary.kt`

```kotlin
data class SetSummary(
    val exerciseType: ExerciseType,
    val weight: Kilograms,
    val targetReps: RepCount,
    val completedReps: RepCount,
    val repScores: List<RepQualityScore>,
    val avgVelocity: MetersPerSecond,
    val peakVelocity: MetersPerSecond,
    val velocityLossPercent: Float,
    val overallScore: Int,                 // average of rep scores
    val bestRepIndex: Int,
    val worstRepIndex: Int,
    val aggregateFormIssues: List<AggregateFormIssue>
) {
    val overallGrade: ScoreGrade get() = ScoreGrade.fromScore(overallScore)
}

data class AggregateFormIssue(
    val issue: FormIssue,
    val repNumbers: List<Int>              // which reps had this issue
)
```

### Step 3: Domain — RepQualityScorer

File: `domain/exercise/RepQualityScorer.kt`

```kotlin
class RepQualityScorer {

    fun scoreRep(
        exerciseType: ExerciseType,
        depthValidation: DepthValidationResult?,
        barXPositions: List<Float>,
        eccentricDuration: Float,
        concentricDuration: Float,
        previousRepDurations: List<Float>,
        formIssues: List<FormIssue>
    ): RepQualityScore {
        val depth = scoreDepth(exerciseType, depthValidation)
        val barPath = scoreBarPath(barXPositions)
        val tempo = scoreTempo(eccentricDuration, concentricDuration, previousRepDurations)
        val form = scoreForm(formIssues)

        return RepQualityScore(
            totalScore = depth + barPath + tempo + form,
            depthScore = depth,
            barPathScore = barPath,
            tempoScore = tempo,
            formScore = form,
            depthAchieved = depthValidation?.isValid ?: false,
            formIssues = formIssues
        )
    }

    private fun scoreDepth(type: ExerciseType, result: DepthValidationResult?): Int {
        if (result == null) return 15  // no depth data = partial credit
        return when {
            result.isValid && result.confidence > 0.8f -> 30
            result.isValid && result.confidence > 0.6f -> 25
            result.isValid -> 20
            else -> (result.confidence * 15).toInt()  // partial credit for close attempts
        }
    }

    private fun scoreBarPath(positions: List<Float>): Int {
        if (positions.size < 3) return 15  // insufficient data
        val mean = positions.average().toFloat()
        val variance = positions.map { (it - mean).pow(2) }.average().toFloat()
        val stdDev = sqrt(variance)
        val maxDev = 0.10f  // 10cm
        return ((1f - (stdDev / maxDev)).coerceIn(0f, 1f) * 20).toInt()
    }

    private fun scoreTempo(
        eccentric: Float,
        concentric: Float,
        previousConcentric: List<Float>
    ): Int {
        val eccentricScore = when {
            eccentric >= 1.0f -> 10
            eccentric >= 0.5f -> 5
            else -> 0
        }
        val consistencyScore = if (previousConcentric.isNotEmpty()) {
            val avg = previousConcentric.average().toFloat()
            if (avg > 0f) {
                val deviation = abs(concentric - avg) / avg
                ((1f - deviation).coerceIn(0f, 1f) * 10).toInt()
            } else 10
        } else 10
        return eccentricScore + consistencyScore
    }

    private fun scoreForm(issues: List<FormIssue>): Int {
        var score = 30
        issues.forEach {
            score -= when (it.severity) {
                Severity.CRITICAL -> 15
                Severity.WARNING -> 8
                Severity.INFO -> 3
            }
        }
        return score.coerceAtLeast(0)
    }
}
```

No Android dependencies. Pure domain logic. Easily testable.

### Step 4: Data Collection During Set

During recording, the WorkoutViewModel needs to accumulate per-rep data that isn't
currently stored. Specifically:

**Bar X positions per rep**: Collect the tracking point's X coordinate each frame
during a rep (from ECCENTRIC start to TOP). Store as `List<Float>`.

**Per-rep form issues**: Collect form validation results during each rep.

**Per-rep depth validation**: Already available from the strategy.

Add to WorkoutViewModel (internal tracking state, not in WorkoutState):

```kotlin
// Accumulated during current rep
private var currentRepBarXPositions = mutableListOf<Float>()
private var currentRepFormIssues = mutableListOf<FormIssue>()
private var currentRepEccentricStartTime: Long? = null
private var currentRepBottomTime: Long? = null
private var currentRepConcentricStartTime: Long? = null

// Accumulated during current set
private var setRepScores = mutableListOf<RepQualityScore>()
private var previousConcentricDurations = mutableListOf<Float>()
```

On phase transitions:
- IDLE → ECCENTRIC: reset current rep accumulators, record eccentric start time
- ECCENTRIC → BOTTOM: record bottom time, eccentric duration = bottom - eccentric start
- BOTTOM → CONCENTRIC: record concentric start time
- CONCENTRIC → TOP: concentric duration = top time - concentric start. Score the rep.

On each frame during a rep:
- Append bar X position to `currentRepBarXPositions`
- Append any form issues to `currentRepFormIssues`

### Step 5: SetSummary Generation

When `stopRecording()` is called, generate the SetSummary:

```kotlin
fun stopRecording(): SetSummary {
    val summary = SetSummary(
        exerciseType = _uiState.value.selectedExercise,
        weight = _uiState.value.weight,
        targetReps = _uiState.value.targetReps,
        completedReps = RepCount(setRepScores.size),
        repScores = setRepScores.toList(),
        avgVelocity = /* average of rep velocities */,
        peakVelocity = /* max of rep velocities */,
        velocityLossPercent = _uiState.value.velocityLossState.currentLossPercent,
        overallScore = setRepScores.map { it.totalScore }.average().toInt(),
        bestRepIndex = setRepScores.indices.maxByOrNull { setRepScores[it].totalScore } ?: 0,
        worstRepIndex = setRepScores.indices.minByOrNull { setRepScores[it].totalScore } ?: 0,
        aggregateFormIssues = aggregateFormIssues(setRepScores)
    )

    _uiState.update { it.copy(lastSetSummary = summary) }
    return summary
}
```

### Step 6: WorkoutState Update

Add to WorkoutState:
```kotlin
data class WorkoutState(
    // ... existing fields ...
    val currentRepScore: RepQualityScore? = null,
    val lastSetSummary: SetSummary? = null
)
```

### Step 7: UI — Post-Set Summary Bottom Sheet

File: Modify `WorkoutScreen.kt`, add composable `SetSummarySheet`

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetSummarySheet(
    summary: SetSummary,
    onDismiss: () -> Unit,
    onSaveNotes: (String) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: exercise, weight, reps
            Text(
                "${summary.exerciseType.displayName} · ${summary.weight} · " +
                "${summary.completedReps}/${summary.targetReps}",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Overall score bar
            ScoreBar(score = summary.overallScore, grade = summary.overallGrade)

            Spacer(modifier = Modifier.height(16.dp))

            // Per-rep table
            RepScoreTable(
                scores = summary.repScores,
                bestIndex = summary.bestRepIndex,
                worstIndex = summary.worstRepIndex
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Velocity curve chart (using MPAndroidChart)
            VelocityCurveChart(
                velocities = summary.repScores.map { /* rep velocity */ }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Aggregate form issues
            if (summary.aggregateFormIssues.isNotEmpty()) {
                Text("Form Issues:", style = MaterialTheme.typography.titleSmall)
                summary.aggregateFormIssues.forEach { aggregate ->
                    val reps = aggregate.repNumbers.joinToString(", ")
                    Text("${aggregate.issue.severity.icon} ${aggregate.issue.message} (reps $reps)")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Stats row
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                StatItem("Avg Velocity", "${summary.avgVelocity}")
                StatItem("Vel. Loss", "${(summary.velocityLossPercent * 100).toInt()}%")
            }

            // Action buttons
            Row {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = { /* show notes input */ }) { Text("Save Notes") }
            }
        }
    }
}
```

Show the sheet when `lastSetSummary != null`:
```kotlin
// In WorkoutScreen
val state by viewModel.uiState.collectAsState()
if (state.lastSetSummary != null) {
    SetSummarySheet(
        summary = state.lastSetSummary!!,
        onDismiss = { viewModel.dismissSummary() },
        onSaveNotes = { notes -> viewModel.saveSetNotes(notes) }
    )
}
```

### Step 8: Persist Rep Quality Score

Add `qualityScore: Int` field to the `Rep` domain model:

```kotlin
data class Rep(
    // ... existing fields ...
    val qualityScore: Int = 0
)
```

And to the Room entity `RepEntity`:
```kotlin
@Entity
data class RepEntity(
    // ... existing fields ...
    @ColumnInfo(name = "quality_score") val qualityScore: Int = 0
)
```

This requires a Room database migration:
```kotlin
val MIGRATION_X_Y = object : Migration(X, Y) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE reps ADD COLUMN quality_score INTEGER NOT NULL DEFAULT 0")
    }
}
```

## Files to Create

| File | Layer | Purpose |
|------|-------|---------|
| `domain/model/RepQualityScore.kt` | Domain | Score data class + ScoreGrade enum |
| `domain/model/SetSummary.kt` | Domain | Post-set summary data class |
| `domain/exercise/RepQualityScorer.kt` | Domain | Scoring logic (pure functions) |

## Files to Modify

| File | Change |
|------|--------|
| `domain/model/Rep.kt` | Add qualityScore field |
| `data/local/entity/RepEntity.kt` | Add quality_score column |
| `data/local/database/AppDatabase.kt` | Add migration for new column |
| `data/mapper/RepMapper.kt` | Map qualityScore between entity and domain |
| `ui/screens/workout/WorkoutState.kt` | Add currentRepScore, lastSetSummary |
| `ui/screens/workout/WorkoutViewModel.kt` | Accumulate rep data, score reps, generate summary |
| `ui/screens/workout/WorkoutScreen.kt` | Add SetSummarySheet, show after stopRecording |
| `res/values/strings.xml` | Summary labels, grade names |

## Testing Plan

| Test | Type | What |
|------|------|------|
| `RepQualityScorerTest` | Unit | All 4 scoring components with edge cases |
| `RepQualityScorerDepthTest` | Unit | Depth scoring for each exercise type |
| `RepQualityScorerBarPathTest` | Unit | Perfect path (0 deviation), bad path, insufficient data |
| `RepQualityScorerTempoTest` | Unit | Fast/slow eccentrics, consistency with/without previous reps |
| `RepQualityScorerFormTest` | Unit | No issues = 30, critical = -15, combinations |
| `SetSummaryTest` | Unit | Aggregation, best/worst rep, overall score |
| `ScoreGradeTest` | Unit | Boundary values (89→GOOD, 90→EXCELLENT, etc.) |

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Scoring weights feel arbitrary to users | Make scoring transparent: show component breakdown. Tune weights based on user feedback |
| Bar path data is noisy (camera jitter) | Apply same Kalman filter used for velocity; use smoothed X positions |
| First rep has no tempo comparison data | Give full consistency points to first rep |
| Depth validation returns null (no data) | Award partial credit (15/30) when data is insufficient |
| Summary sheet blocks camera/recording | Sheet only appears after stopRecording(); camera is already paused |
| Database migration on existing installs | Default qualityScore = 0; old reps show "Not scored" |
