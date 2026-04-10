package com.lifthelper.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lifthelper.R
import com.lifthelper.domain.exercise.VelocityLossState
import com.lifthelper.domain.model.SquatDepthType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onRecalibrate: () -> Unit = {},
    onAnthropometricCalibration: () -> Unit = {},
    onAnthropometricDetails: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val backSquatDepthType by viewModel.backSquatDepthType.collectAsState()
    val frontSquatDepthType by viewModel.frontSquatDepthType.collectAsState()
    val vbtZonesEnabled by viewModel.vbtZonesEnabled.collectAsState()
    val velocityLossThreshold by viewModel.velocityLossThreshold.collectAsState()
    val audioFeedbackEnabled by viewModel.audioFeedbackEnabled.collectAsState()
    val hapticFeedbackEnabled by viewModel.hapticFeedbackEnabled.collectAsState()
    val tempoModeEnabled by viewModel.tempoModeEnabled.collectAsState()
    val restTimerEnabled by viewModel.restTimerEnabled.collectAsState()
    val restDurationDefault by viewModel.restDurationDefault.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            // ── Calibration Section ──
            SectionHeader(stringResource(R.string.settings_section_calibration))

            Spacer(modifier = Modifier.height(8.dp))

            // Re-calibrate button
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = onRecalibrate
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = stringResource(R.string.settings_recalibrate),
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.settings_recalibrate),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.settings_recalibrate_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Anthropometric Calibration button
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = onAnthropometricCalibration
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.FitnessCenter,
                        contentDescription = stringResource(R.string.settings_capture_body),
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.settings_capture_body),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.settings_capture_body_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // View/Edit Anthropometric Details button
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = onAnthropometricDetails
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = stringResource(R.string.settings_view_measurements),
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.settings_view_measurements),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.settings_view_measurements_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Exercise Settings Section ──
            SectionHeader(stringResource(R.string.settings_section_exercise))

            Spacer(modifier = Modifier.height(8.dp))

            // Back Squat Depth Selector
            SquatDepthSelector(
                title = stringResource(R.string.settings_back_squat_depth),
                currentDepth = backSquatDepthType,
                onDepthSelected = { viewModel.updateBackSquatDepth(it) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Front Squat Depth Selector
            SquatDepthSelector(
                title = stringResource(R.string.settings_front_squat_depth),
                currentDepth = frontSquatDepthType,
                onDepthSelected = { viewModel.updateFrontSquatDepth(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── VBT Settings Section ──
            SectionHeader(stringResource(R.string.vbt_settings_title))

            Spacer(modifier = Modifier.height(8.dp))

            // VBT Zones Toggle
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.vbt_zones_enabled),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.vbt_zones_enabled_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = vbtZonesEnabled,
                        onCheckedChange = { viewModel.updateVbtZonesEnabled(it) }
                    )
                }
            }

            // Velocity Loss Threshold — collapse when VBT zones disabled
            AnimatedVisibility(
                visible = vbtZonesEnabled,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))

                    VelocityLossThresholdSelector(
                        currentThreshold = velocityLossThreshold,
                        onThresholdSelected = { viewModel.updateVelocityLossThreshold(it) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Feedback Settings Section ──
            SectionHeader(stringResource(R.string.feedback_settings_title))

            Spacer(modifier = Modifier.height(8.dp))

            // Audio Feedback Toggle
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.audio_feedback_enabled),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.audio_feedback_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = audioFeedbackEnabled,
                        onCheckedChange = { viewModel.updateAudioFeedbackEnabled(it) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Haptic Feedback Toggle
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.haptic_feedback_enabled),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.haptic_feedback_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = hapticFeedbackEnabled,
                        onCheckedChange = { viewModel.updateHapticFeedbackEnabled(it) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Tempo Training Settings Section ──
            SectionHeader(stringResource(R.string.tempo_settings_title))

            Spacer(modifier = Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.tempo_mode_enabled),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.tempo_mode_enabled_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = tempoModeEnabled,
                        onCheckedChange = { viewModel.updateTempoModeEnabled(it) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Rest Timer Settings Section ──
            SectionHeader(stringResource(R.string.rest_timer_settings_title))

            Spacer(modifier = Modifier.height(8.dp))

            // Rest Timer Toggle
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.rest_timer_enabled),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.rest_timer_enabled_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = restTimerEnabled,
                        onCheckedChange = { viewModel.updateRestTimerEnabled(it) }
                    )
                }
            }

            // Default rest duration — collapse when rest timer disabled
            AnimatedVisibility(
                visible = restTimerEnabled,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))

                    RestDurationSelector(
                        currentDuration = restDurationDefault,
                        onDurationSelected = { viewModel.updateRestDurationDefault(it) }
                    )
                }
            }

            // Bottom spacing for edge-to-edge
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

/**
 * Composable for selecting squat depth type.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SquatDepthSelector(
    title: String,
    currentDepth: SquatDepthType,
    onDepthSelected: (SquatDepthType) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = currentDepth.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.settings_depth_type_label)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    SquatDepthType.entries.forEach { depthType ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = depthType.displayName,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = depthType.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                onDepthSelected(depthType)
                                expanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                    }
                }
            }

            // Show description of current selection
            Text(
                text = currentDepth.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/**
 * Composable for selecting velocity loss threshold.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VelocityLossThresholdSelector(
    currentThreshold: Float,
    onThresholdSelected: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    val thresholdOptions = listOf(
        0.10f to "10%",
        0.15f to "15%",
        0.20f to "20%",
        0.25f to "25%",
        0.30f to "30%",
        VelocityLossState.DISABLED_THRESHOLD to stringResource(R.string.vbt_threshold_disabled)
    )

    val currentLabel = thresholdOptions
        .firstOrNull { it.first == currentThreshold }?.second
        ?: "${(currentThreshold * 100).toInt()}%"

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.vbt_velocity_loss_threshold),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            Text(
                text = stringResource(R.string.vbt_velocity_loss_threshold_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = currentLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.settings_threshold_label)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    thresholdOptions.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label, style = MaterialTheme.typography.bodyLarge) },
                            onClick = {
                                onThresholdSelected(value)
                                expanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestDurationSelector(
    currentDuration: Int,
    onDurationSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    val durationOptions = listOf(
        30 to "30s",
        60 to "1 min",
        90 to "1 min 30s",
        120 to "2 min",
        150 to "2 min 30s",
        180 to "3 min",
        240 to "4 min",
        300 to "5 min"
    )

    val currentLabel = durationOptions
        .firstOrNull { it.first == currentDuration }?.second
        ?: "${currentDuration / 60}m ${currentDuration % 60}s"

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.rest_timer_default_duration),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            Text(
                text = stringResource(R.string.rest_timer_default_duration_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = currentLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.rest_timer_duration_label)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    durationOptions.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label, style = MaterialTheme.typography.bodyLarge) },
                            onClick = {
                                onDurationSelected(value)
                                expanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                    }
                }
            }
        }
    }
}
