# Feature 05: Progressive Overload Analytics

## Summary

Transform the History screen into a full analytics dashboard with exercise-specific
trend charts, personal records, volume tracking, estimated 1RM progression, and
quality score trends. This gives lifters the long-term view they need to ensure
progressive overload — the fundamental principle of strength training.

## Motivation

The current History screen is a flat list of workouts with aggregate numbers. Lifters
need to answer:

- "Am I getting stronger?" → velocity at same load increasing over weeks
- "Am I lifting more volume?" → total tonnage trend
- "Is my form improving?" → quality score trend (Feature 04)
- "What's my estimated max?" → 1RM from load-velocity profile (Feature 01)
- "Did I hit any PRs?" → automatic detection and celebration

Without this, the app is a session-by-session tool. With it, it becomes a training log
that drives programming decisions.

## Design

### Analytics Screen (New)

Replace the simple History list with a tabbed analytics screen:

```
 ┌─────────────────────────────────────────┐
 │  ANALYTICS                              │
 │  ┌──────┬──────────┬─────────┬────────┐ │
 │  │ All  │ Back Sq. │ Bench   │ Dead.  │ │  ← exercise filter tabs
 │  └──────┴──────────┴─────────┴────────┘ │
 │                                         │
 │  ═══ PERSONAL RECORDS ═══               │
 │  Heaviest Set:  140kg x 3  (Feb 20)    │
 │  Fastest Rep:   1.12 m/s @ 80kg         │
 │  Best Quality:  95/100  (Feb 25)        │
 │  Est. 1RM:      152 kg                  │
 │                                         │
 │  ═══ VELOCITY TREND ═══                 │
 │  [Line chart: avg velocity over weeks]  │
 │  Filter: [80kg ▼] [100kg ▼] [All ▼]    │
 │                                         │
 │  ═══ VOLUME ═══                         │
 │  [Bar chart: weekly tonnage]            │
 │  This week: 12,450 kg                   │
 │  Last week: 11,200 kg  (+11%)          │
 │                                         │
 │  ═══ ESTIMATED 1RM ═══                  │
 │  [Line chart: 1RM progression]          │
 │  Current: 152 kg  (+5kg from Jan)       │
 │                                         │
 │  ═══ QUALITY TREND ═══                  │
 │  [Line chart: avg rep quality over time]│
 │                                         │
 │  ═══ RECENT WORKOUTS ═══               │
 │  [Scrollable workout list - existing]   │
 └─────────────────────────────────────────┘
```

### Personal Records

Automatically detect and display PRs:

| PR Type | How It's Calculated |
|---------|-------------------|
| Heaviest Set | Max weight with at least 1 completed rep |
| Most Reps | Max completedReps at a given weight |
| Fastest Rep | Max peakVelocity across all reps |
| Best Quality | Max qualityScore (from Feature 04) |
| Estimated 1RM | From load-velocity profile (Feature 01) |
| Highest Volume Set | Max (weight * reps) in a single set |
| Most Tonnage (session) | Max sum of (weight * reps) across all sets in one workout |

When a new PR is set during a workout, show a celebration overlay (confetti + text).

### Velocity Trend

Line chart showing average concentric velocity over time, filterable by:
- Exercise type (tab selection)
- Load (dropdown with weights the user has used)
- Time range (1 week, 1 month, 3 months, 6 months, 1 year, all time)

If the same load gets faster over time → the lifter is getting stronger (VBT principle).

### Volume Tracking

Bar chart showing weekly training volume (tonnage = sets * reps * weight):
- Stacked by exercise if "All" tab is selected
- Single color if specific exercise is selected
- Show week-over-week percentage change

### Estimated 1RM Progression

Line chart showing the estimated 1RM over time (from Feature 01's
LoadVelocityProfile). Requires data from multiple sessions at different loads.

### Quality Score Trend

Line chart showing average rep quality score (from Feature 04) over time.
Useful for tracking form improvement.

## Implementation Plan

### Step 1: Domain Models

File: `domain/model/PersonalRecord.kt`

```kotlin
data class PersonalRecord(
    val type: PRType,
    val value: String,               // formatted display value
    val rawValue: Float,             // for comparison
    val exerciseType: ExerciseType,
    val date: Long,
    val workoutId: Long
)

enum class PRType(val displayName: String) {
    HEAVIEST_SET("Heaviest Set"),
    MOST_REPS("Most Reps"),
    FASTEST_REP("Fastest Rep"),
    BEST_QUALITY("Best Quality"),
    ESTIMATED_1RM("Estimated 1RM"),
    HIGHEST_VOLUME_SET("Highest Volume Set"),
    MOST_TONNAGE_SESSION("Most Tonnage (Session)")
}
```

File: `domain/model/AnalyticsData.kt`

```kotlin
data class VelocityTrendPoint(
    val date: Long,
    val avgVelocity: MetersPerSecond,
    val weight: Kilograms,
    val exerciseType: ExerciseType
)

data class VolumeTrendPoint(
    val weekStart: Long,             // Monday of the week
    val tonnage: Float,              // kg
    val exerciseType: ExerciseType
)

data class OneRMTrendPoint(
    val date: Long,
    val estimated1RM: Kilograms,
    val exerciseType: ExerciseType,
    val confidence: Float            // R-squared of the regression
)

data class QualityTrendPoint(
    val date: Long,
    val avgQuality: Int,
    val exerciseType: ExerciseType
)

data class ExerciseAnalytics(
    val exerciseType: ExerciseType?,  // null = all exercises
    val personalRecords: List<PersonalRecord>,
    val velocityTrend: List<VelocityTrendPoint>,
    val volumeTrend: List<VolumeTrendPoint>,
    val oneRMTrend: List<OneRMTrendPoint>,
    val qualityTrend: List<QualityTrendPoint>,
    val recentWorkouts: List<Workout>
)
```

### Step 2: Use Cases

File: `domain/usecase/GetExerciseAnalyticsUseCase.kt`

```kotlin
class GetExerciseAnalyticsUseCase @Inject constructor(
    private val workoutRepository: WorkoutRepository,
    private val getLoadVelocityProfileUseCase: GetLoadVelocityProfileUseCase
) {
    suspend fun execute(
        exerciseType: ExerciseType?,     // null = all
        timeRange: TimeRange = TimeRange.ALL
    ): ExerciseAnalytics {
        val workouts = workoutRepository.getAllWorkouts()
        val filteredSets = workouts
            .flatMap { it.sets }
            .filter { set ->
                (exerciseType == null || set.exerciseType == exerciseType) &&
                isInTimeRange(set.timestamp, timeRange)
            }

        return ExerciseAnalytics(
            exerciseType = exerciseType,
            personalRecords = calculatePRs(filteredSets, workouts),
            velocityTrend = calculateVelocityTrend(filteredSets),
            volumeTrend = calculateVolumeTrend(filteredSets),
            oneRMTrend = calculateOneRMTrend(exerciseType, filteredSets),
            qualityTrend = calculateQualityTrend(filteredSets),
            recentWorkouts = workouts
                .filter { w ->
                    exerciseType == null ||
                    w.sets.any { it.exerciseType == exerciseType }
                }
                .sortedByDescending { it.date }
                .take(20)
        )
    }
}

enum class TimeRange(val label: String, val daysBack: Int) {
    ONE_WEEK("1W", 7),
    ONE_MONTH("1M", 30),
    THREE_MONTHS("3M", 90),
    SIX_MONTHS("6M", 180),
    ONE_YEAR("1Y", 365),
    ALL("All", Int.MAX_VALUE)
}
```

File: `domain/usecase/DetectPersonalRecordsUseCase.kt`

```kotlin
class DetectPersonalRecordsUseCase @Inject constructor(
    private val workoutRepository: WorkoutRepository
) {
    /**
     * Check if the just-completed set contains any new PRs.
     * Returns list of newly broken records.
     */
    suspend fun checkForNewPRs(
        completedSet: Set,
        exerciseType: ExerciseType
    ): List<PersonalRecord> {
        val historicalSets = workoutRepository.getAllWorkouts()
            .flatMap { it.sets }
            .filter { it.exerciseType == exerciseType }

        val newPRs = mutableListOf<PersonalRecord>()

        // Check heaviest set
        val historicalMax = historicalSets.maxOfOrNull { it.weight.value } ?: 0f
        if (completedSet.weight.value > historicalMax) {
            newPRs.add(PersonalRecord(
                type = PRType.HEAVIEST_SET,
                value = "${completedSet.weight}",
                rawValue = completedSet.weight.value,
                exerciseType = exerciseType,
                date = completedSet.timestamp,
                workoutId = completedSet.workoutId
            ))
        }

        // Check fastest rep, most reps, etc.
        // ... similar pattern for each PR type

        return newPRs
    }
}
```

### Step 3: Repository Layer — Efficient Queries

The current `WorkoutRepository` loads all workouts. For analytics, we need efficient
queries that don't load everything into memory.

Add to `WorkoutRepository` interface:
```kotlin
interface WorkoutRepository {
    // ... existing methods ...

    suspend fun getSetsByExercise(exerciseType: ExerciseType): List<Set>
    suspend fun getSetsByExerciseAndDateRange(
        exerciseType: ExerciseType,
        startDate: Long,
        endDate: Long
    ): List<Set>
    suspend fun getWeeklyVolume(
        exerciseType: ExerciseType?,
        weeksBack: Int
    ): List<VolumeTrendPoint>
}
```

Add corresponding Room DAO queries:
```kotlin
@Query("""
    SELECT * FROM sets
    WHERE exercise_type = :exerciseType
    ORDER BY timestamp DESC
""")
suspend fun getSetsByExercise(exerciseType: String): List<SetEntity>

@Query("""
    SELECT
        (timestamp / 604800000 * 604800000) as weekStart,
        SUM(weight * completed_reps) as tonnage,
        exercise_type as exerciseType
    FROM sets
    WHERE (:exerciseType IS NULL OR exercise_type = :exerciseType)
        AND timestamp > :sinceTimestamp
    GROUP BY weekStart, exercise_type
    ORDER BY weekStart
""")
suspend fun getWeeklyVolume(
    exerciseType: String?,
    sinceTimestamp: Long
): List<VolumeTrendRaw>
```

### Step 4: AnalyticsViewModel

File: `ui/screens/analytics/AnalyticsViewModel.kt`

```kotlin
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val getExerciseAnalyticsUseCase: GetExerciseAnalyticsUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow<AnalyticsState>(AnalyticsState.Loading)
    val uiState: StateFlow<AnalyticsState> = _uiState.asStateFlow()

    private val _selectedExercise = MutableStateFlow<ExerciseType?>(null)
    val selectedExercise: StateFlow<ExerciseType?> = _selectedExercise.asStateFlow()

    private val _timeRange = MutableStateFlow(TimeRange.THREE_MONTHS)
    val timeRange: StateFlow<TimeRange> = _timeRange.asStateFlow()

    init {
        loadAnalytics()
    }

    fun selectExercise(exerciseType: ExerciseType?) {
        _selectedExercise.value = exerciseType
        loadAnalytics()
    }

    fun selectTimeRange(range: TimeRange) {
        _timeRange.value = range
        loadAnalytics()
    }

    private fun loadAnalytics() {
        viewModelScope.launch {
            _uiState.value = AnalyticsState.Loading
            try {
                val analytics = getExerciseAnalyticsUseCase.execute(
                    exerciseType = _selectedExercise.value,
                    timeRange = _timeRange.value
                )
                _uiState.value = AnalyticsState.Loaded(analytics)
            } catch (e: Exception) {
                _uiState.value = AnalyticsState.Error(e.message ?: "Unknown error")
            }
        }
    }
}

sealed interface AnalyticsState {
    data object Loading : AnalyticsState
    data class Loaded(val data: ExerciseAnalytics) : AnalyticsState
    data class Error(val message: String) : AnalyticsState
}
```

### Step 5: Analytics Screen

File: `ui/screens/analytics/AnalyticsScreen.kt`

```kotlin
@Composable
fun AnalyticsScreen(
    viewModel: AnalyticsViewModel = hiltViewModel(),
    onNavigateToWorkoutDetail: (Long) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val selectedExercise by viewModel.selectedExercise.collectAsState()
    val timeRange by viewModel.timeRange.collectAsState()

    Column {
        // Exercise filter tabs
        ExerciseFilterTabs(
            selected = selectedExercise,
            onSelect = viewModel::selectExercise
        )

        // Time range selector
        TimeRangeSelector(
            selected = timeRange,
            onSelect = viewModel::selectTimeRange
        )

        when (val s = state) {
            is AnalyticsState.Loading -> CircularProgressIndicator()
            is AnalyticsState.Error -> ErrorMessage(s.message)
            is AnalyticsState.Loaded -> {
                LazyColumn {
                    // Personal Records section
                    item { PersonalRecordsCard(s.data.personalRecords) }

                    // Velocity Trend chart
                    item { VelocityTrendChart(s.data.velocityTrend) }

                    // Volume chart
                    item { VolumeTrendChart(s.data.volumeTrend) }

                    // Estimated 1RM chart
                    if (s.data.oneRMTrend.isNotEmpty()) {
                        item { OneRMTrendChart(s.data.oneRMTrend) }
                    }

                    // Quality trend chart
                    if (s.data.qualityTrend.isNotEmpty()) {
                        item { QualityTrendChart(s.data.qualityTrend) }
                    }

                    // Recent workouts list
                    item {
                        Text(
                            "Recent Workouts",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    items(s.data.recentWorkouts) { workout ->
                        WorkoutCard(
                            workout = workout,
                            onClick = { onNavigateToWorkoutDetail(workout.id) }
                        )
                    }
                }
            }
        }
    }
}
```

### Step 6: Chart Components

Use MPAndroidChart (already a dependency) via `AndroidView` in Compose:

File: `ui/components/charts/VelocityTrendChart.kt`

```kotlin
@Composable
fun VelocityTrendChart(data: List<VelocityTrendPoint>) {
    AndroidView(
        factory = { context ->
            LineChart(context).apply {
                description.isEnabled = false
                setTouchEnabled(true)
                isDragEnabled = true
                setScaleEnabled(true)
                xAxis.position = XAxis.XAxisPosition.BOTTOM
                xAxis.valueFormatter = DateAxisFormatter()
                axisRight.isEnabled = false
                axisLeft.axisMinimum = 0f
            }
        },
        update = { chart ->
            val entries = data.mapIndexed { i, point ->
                Entry(point.date.toFloat(), point.avgVelocity.value)
            }
            val dataSet = LineDataSet(entries, "Avg Velocity (m/s)").apply {
                color = Color.BLUE
                setCircleColor(Color.BLUE)
                lineWidth = 2f
                circleRadius = 3f
                setDrawValues(false)
                mode = LineDataSet.Mode.CUBIC_BEZIER
            }
            chart.data = LineData(dataSet)
            chart.invalidate()
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
    )
}
```

Similar implementations for `VolumeTrendChart` (BarChart), `OneRMTrendChart`
(LineChart), and `QualityTrendChart` (LineChart).

### Step 7: PR Celebration Overlay

When a PR is detected after a set, show a brief celebration:

```kotlin
@Composable
fun PRCelebration(records: List<PersonalRecord>) {
    AnimatedVisibility(
        visible = records.isNotEmpty(),
        enter = fadeIn() + scaleIn(),
        exit = fadeOut()
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Text("NEW PR!", style = MaterialTheme.typography.headlineMedium)
                records.forEach { pr ->
                    Text("${pr.type.displayName}: ${pr.value}")
                }
            }
        }
    }
}
```

Trigger from WorkoutViewModel after set completion:
```kotlin
val newPRs = detectPersonalRecordsUseCase.checkForNewPRs(completedSet, exerciseType)
if (newPRs.isNotEmpty()) {
    _uiState.update { it.copy(newPersonalRecords = newPRs) }
    // Also emit FeedbackEvent for audio (Feature 02)
    feedbackEmitter.emit(FeedbackEvent.PersonalRecord)
}
```

### Step 8: Navigation Update

Replace History route with Analytics, or add Analytics as a new route alongside
History:

```kotlin
// In NavGraph.kt
composable(Screen.Analytics.route) {
    AnalyticsScreen(
        onNavigateToWorkoutDetail = { workoutId ->
            navController.navigate(Screen.WorkoutDetail.createRoute(workoutId))
        }
    )
}
```

Update Home screen navigation:
```kotlin
// Change "View History" to "Analytics" or keep both
Button(onClick = { navController.navigate(Screen.Analytics.route) }) {
    Text("Analytics")
}
```

### Step 9: Weight Filter for Velocity Trend

Allow filtering velocity trend by weight to see "am I faster at 100kg than I was
last month?":

```kotlin
// In AnalyticsViewModel
private val _selectedWeight = MutableStateFlow<Kilograms?>(null)

// Available weights are derived from historical data
val availableWeights: StateFlow<List<Kilograms>> = _uiState
    .filterIsInstance<AnalyticsState.Loaded>()
    .map { state ->
        state.data.velocityTrend
            .map { it.weight }
            .distinct()
            .sortedBy { it.value }
    }
    .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
```

## Files to Create

| File | Layer | Purpose |
|------|-------|---------|
| `domain/model/PersonalRecord.kt` | Domain | PR data model + PRType enum |
| `domain/model/AnalyticsData.kt` | Domain | Trend point models + ExerciseAnalytics |
| `domain/usecase/GetExerciseAnalyticsUseCase.kt` | Domain | Compute analytics from workout data |
| `domain/usecase/DetectPersonalRecordsUseCase.kt` | Domain | Check for new PRs after set completion |
| `ui/screens/analytics/AnalyticsScreen.kt` | UI | Main analytics screen with tabs and charts |
| `ui/screens/analytics/AnalyticsViewModel.kt` | UI | State management for analytics |
| `ui/components/charts/VelocityTrendChart.kt` | UI | Velocity line chart |
| `ui/components/charts/VolumeTrendChart.kt` | UI | Volume bar chart |
| `ui/components/charts/OneRMTrendChart.kt` | UI | 1RM progression line chart |
| `ui/components/charts/QualityTrendChart.kt` | UI | Quality score line chart |
| `ui/components/PRCelebration.kt` | UI | PR celebration overlay |

## Files to Modify

| File | Change |
|------|--------|
| `domain/repository/WorkoutRepository.kt` | Add filtered query methods |
| `data/repository/WorkoutRepositoryImpl.kt` | Implement new queries |
| `data/local/dao/SetDao.kt` | Add efficient aggregate queries |
| `ui/navigation/Screen.kt` | Add Analytics route |
| `ui/navigation/NavGraph.kt` | Wire Analytics screen, update History route |
| `ui/screens/home/HomeScreen.kt` | Update navigation button (History → Analytics) |
| `ui/screens/workout/WorkoutViewModel.kt` | PR detection after set completion |
| `ui/screens/workout/WorkoutState.kt` | Add newPersonalRecords field |
| `res/values/strings.xml` | Analytics labels, PR type names, chart labels |

## Dependencies

- **MPAndroidChart** (already included) — for all charts
- No new dependencies required

## Testing Plan

| Test | Type | What |
|------|------|------|
| `GetExerciseAnalyticsUseCaseTest` | Unit | Filtering, aggregation, time ranges |
| `DetectPersonalRecordsUseCaseTest` | Unit | PR detection for each type, no false PRs |
| `VelocityTrendCalculationTest` | Unit | Grouping by date, weight filtering |
| `VolumeTrendCalculationTest` | Unit | Weekly aggregation, tonnage math |
| `OneRMTrendCalculationTest` | Unit | Regression over time, insufficient data |
| `AnalyticsViewModelTest` | Unit | State transitions, exercise/time filtering |
| `PersonalRecordTest` | Unit | Comparison logic, formatting |

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Loading all workouts for analytics is slow | Add indexed Room queries; paginate recent workouts |
| Charts are complex to render in Compose | MPAndroidChart via AndroidView is proven; wrap in reusable composables |
| Too many PRs detected (trivially new weights) | Only track PRs for weights the user has used 2+ times |
| 1RM estimation requires Feature 01 | Load-velocity profile can be computed independently; Feature 01 just adds real-time display |
| Quality trend requires Feature 04 | Quality trend section hidden when no quality scores exist; graceful degradation |
| Screen is too long/busy | Use collapsible sections; show summary cards that expand to full charts |

## Data Flow

```
WorkoutRepository (Room DB)
    │
    ▼
GetExerciseAnalyticsUseCase
    ├── Filter by exercise + time range
    ├── Calculate PRs
    ├── Calculate velocity trend points
    ├── Calculate weekly volume
    ├── Calculate 1RM trend (via GetLoadVelocityProfileUseCase)
    └── Calculate quality trend
    │
    ▼
AnalyticsViewModel (StateFlow<AnalyticsState>)
    │
    ▼
AnalyticsScreen (Compose)
    ├── ExerciseFilterTabs
    ├── TimeRangeSelector
    ├── PersonalRecordsCard
    ├── VelocityTrendChart (MPAndroidChart)
    ├── VolumeTrendChart (MPAndroidChart)
    ├── OneRMTrendChart (MPAndroidChart)
    ├── QualityTrendChart (MPAndroidChart)
    └── RecentWorkoutsList
```
