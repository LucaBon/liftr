package com.lifthelper.ui.screens.analytics

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lifthelper.R
import com.lifthelper.ui.components.common.StatItem
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.TimeRange
import com.lifthelper.domain.model.Workout
import com.lifthelper.ui.components.PersonalRecordsCard
import com.lifthelper.ui.components.charts.Estimated1RMChart
import com.lifthelper.ui.components.charts.QualityScoreChart
import com.lifthelper.ui.components.charts.VelocityTrendChart
import com.lifthelper.ui.components.charts.VolumeTonnageChart
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    onNavigateBack: () -> Unit,
    viewModel: AnalyticsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.analytics_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            uiState.error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = uiState.error ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            uiState.summary == null || uiState.summary?.totalWorkouts == 0 -> {
                EmptyAnalytics(paddingValues)
            }

            else -> {
                val summary = uiState.summary!!
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Exercise filter chips
                    item {
                        ExerciseFilterRow(
                            selectedExercise = uiState.selectedExercise,
                            onExerciseSelected = viewModel::setExerciseFilter
                        )
                    }

                    // Time range selector
                    item {
                        TimeRangeSelector(
                            selectedTimeRange = uiState.selectedTimeRange,
                            onTimeRangeSelected = viewModel::setTimeRange
                        )
                    }

                    // Summary stats
                    item {
                        SummaryStatsRow(
                            totalWorkouts = summary.totalWorkouts,
                            totalSets = summary.totalSets,
                            totalReps = summary.totalReps
                        )
                    }

                    // Personal Records
                    if (summary.personalRecords.isNotEmpty()) {
                        item {
                            SectionHeader(
                            title = stringResource(R.string.analytics_personal_records),
                            icon = { Icon(Icons.Default.EmojiEvents, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
                        )
                        }
                        item {
                            PersonalRecordsCard(records = summary.personalRecords)
                        }
                    }

                    // Weekly Volume chart (always shown)
                    if (summary.weeklyVolumes.isNotEmpty()) {
                        item {
                            SectionHeader(
                            title = stringResource(R.string.analytics_volume),
                            icon = { Icon(Icons.Default.BarChart, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
                        )
                        }
                        item {
                            VolumeTonnageChart(weeklyVolumes = summary.weeklyVolumes)
                        }
                    }

                    // Exercise-specific charts
                    if (uiState.selectedExercise != null) {
                        // Velocity Trend
                        if (summary.velocityTrend.isNotEmpty()) {
                            item {
                                SectionHeader(
                                title = stringResource(R.string.analytics_velocity_trend),
                                icon = { Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
                            )
                            }
                            item {
                                VelocityTrendChart(dataPoints = summary.velocityTrend)
                            }
                        }

                        // Estimated 1RM
                        if (summary.estimated1RMTrend.isNotEmpty()) {
                            item {
                                SectionHeader(
                                title = stringResource(R.string.analytics_estimated_1rm),
                                icon = { Icon(Icons.Default.Timeline, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
                            )
                            }
                            item {
                                Estimated1RMChart(dataPoints = summary.estimated1RMTrend)
                            }
                        }

                        // Quality Score
                        if (summary.qualityScoreTrend.isNotEmpty()) {
                            item {
                                SectionHeader(
                                title = stringResource(R.string.analytics_quality_trend),
                                icon = { Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
                            )
                            }
                            item {
                                QualityScoreChart(dataPoints = summary.qualityScoreTrend)
                            }
                        }
                    } else {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Text(
                                    text = stringResource(R.string.analytics_select_exercise),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(16.dp),
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Recent Workouts
                    if (summary.recentWorkouts.isNotEmpty()) {
                        item {
                            SectionHeader(
                            title = stringResource(R.string.analytics_recent_workouts),
                            icon = { Icon(Icons.Default.Timeline, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
                        )
                        }
                        items(summary.recentWorkouts, key = { it.id }) { workout ->
                            RecentWorkoutCard(workout = workout)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyAnalytics(paddingValues: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.BarChart,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.analytics_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.analytics_no_data),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ExerciseFilterRow(
    selectedExercise: ExerciseType?,
    onExerciseSelected: (ExerciseType?) -> Unit
) {
    val exercises = ExerciseType.entries.filter { !it.isJumpExercise() }

    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selectedExercise == null,
            onClick = { onExerciseSelected(null) },
            label = { Text(stringResource(R.string.analytics_all_exercises)) }
        )
        exercises.forEach { exercise ->
            FilterChip(
                selected = selectedExercise == exercise,
                onClick = { onExerciseSelected(exercise) },
                label = { Text(exercise.getDisplayName()) }
            )
        }
    }
}

@Composable
private fun TimeRangeSelector(
    selectedTimeRange: TimeRange,
    onTimeRangeSelected: (TimeRange) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TimeRange.entries.forEach { range ->
            FilterChip(
                selected = selectedTimeRange == range,
                onClick = { onTimeRangeSelected(range) },
                label = { Text(range.displayName) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SummaryStatsRow(
    totalWorkouts: Int,
    totalSets: Int,
    totalReps: Int
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem(label = "Workouts", value = "$totalWorkouts")
            StatItem(label = "Sets", value = "$totalSets")
            StatItem(label = "Reps", value = "$totalReps")
        }
    }
}

// Uses shared StatItem from com.lifthelper.ui.components.common

@Composable
private fun SectionHeader(
    title: String,
    icon: @Composable (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        icon?.invoke()
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
private fun RecentWorkoutCard(workout: Workout) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy  HH:mm", Locale.getDefault()) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = dateFormat.format(workout.date),
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(label = "Sets", value = "${workout.sets.size}")
                StatItem(label = "Reps", value = "${workout.getTotalReps()}")
                StatItem(label = "Avg Vel", value = "%.2f m/s".format(workout.getAverageVelocity().value))
            }

            if (workout.sets.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                workout.sets.forEachIndexed { index, set ->
                    Text(
                        text = "Set ${index + 1}: ${set.exerciseType.getDisplayName()} - " +
                                "${set.weight.value}kg x ${set.completedReps.value} reps @ " +
                                "%.2f m/s avg".format(set.avgVelocity.value),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
