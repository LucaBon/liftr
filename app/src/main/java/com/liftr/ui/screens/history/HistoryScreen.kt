package com.liftr.ui.screens.history

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.liftr.R
import com.liftr.domain.model.Workout
import com.liftr.ui.components.common.StatItem
import com.liftr.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    onNavigateBack: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.content_desc_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Crossfade(
            targetState = uiState,
            animationSpec = tween(durationMillis = 250),
            label = "history_state"
        ) { state ->
            when (state) {
                is HistoryState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is HistoryState.Empty -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .padding(Spacing.lg),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(Spacing.md))
                        Text(
                            text = stringResource(R.string.history_empty_title),
                            style = MaterialTheme.typography.headlineMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        Text(
                            text = stringResource(R.string.history_empty_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is HistoryState.Loaded -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        contentPadding = PaddingValues(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm + Spacing.xs)
                    ) {
                        items(state.workouts, key = { it.id }) { workout ->
                            WorkoutCard(
                                workout = workout,
                                onDelete = { viewModel.deleteWorkout(workout.id) },
                                modifier = Modifier.animateItemPlacement(
                                    animationSpec = tween(durationMillis = 300)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkoutCard(
    workout: Workout,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy  HH:mm", Locale.getDefault()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = dateFormat.format(workout.date),
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.content_desc_delete_workout),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(label = stringResource(R.string.home_stat_sets), value = "${workout.sets.size}")
                StatItem(label = stringResource(R.string.home_stat_reps), value = "${workout.getTotalReps()}")
                StatItem(
                    label = stringResource(R.string.home_stat_avg_vel),
                    value = "%.2f".format(workout.getAverageVelocity().value),
                    valueColor = MaterialTheme.colorScheme.primary
                )
                StatItem(
                    label = stringResource(R.string.history_stat_peak_vel),
                    value = "%.2f".format(workout.getPeakVelocity().value),
                    valueColor = MaterialTheme.colorScheme.secondary
                )
            }

            if (workout.sets.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(Spacing.sm))

                workout.sets.forEachIndexed { index, set ->
                    if (set.isManual) {
                        Text(
                            text = "Set ${index + 1}: ${set.displayName} - " +
                                    "${set.weight.value}kg x ${set.completedReps.value} reps" +
                                    if (set.notes.isNotBlank()) " \u00B7 ${set.notes}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "Set ${index + 1}: ${set.displayName} - " +
                                    "${set.weight.value}kg x ${set.completedReps.value} reps @ " +
                                    "%.2f m/s avg".format(set.avgVelocity.value),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (workout.notes.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = workout.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = { Text(stringResource(R.string.history_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteConfirm = false
                }) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

// Uses shared StatItem from com.liftr.ui.components.common
