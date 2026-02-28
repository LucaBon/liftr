package com.lifthelper.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 24.dp)
            )

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
                        contentDescription = null,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "Re-calibrate",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Update your height and shoulder width measurements",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

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
                        contentDescription = null,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "Capture Body Measurements",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Use camera to capture detailed body proportions",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

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
                        contentDescription = null,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "View/Edit Measurements",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "View and manually adjust your body measurements",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Exercise Settings Section
            Text(
                text = "Exercise Settings",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Back Squat Depth Selector
            SquatDepthSelector(
                title = "Back Squat Depth",
                currentDepth = backSquatDepthType,
                onDepthSelected = { viewModel.updateBackSquatDepth(it) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Front Squat Depth Selector
            SquatDepthSelector(
                title = "Front Squat Depth",
                currentDepth = frontSquatDepthType,
                onDepthSelected = { viewModel.updateFrontSquatDepth(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // VBT Settings Section
            Text(
                text = stringResource(R.string.vbt_settings_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

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

            Spacer(modifier = Modifier.height(16.dp))

            // Velocity Loss Threshold Selector
            VelocityLossThresholdSelector(
                currentThreshold = velocityLossThreshold,
                onThresholdSelected = { viewModel.updateVelocityLossThreshold(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Feedback Settings Section
            Text(
                text = stringResource(R.string.feedback_settings_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

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

            Spacer(modifier = Modifier.height(16.dp))

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
        }
    }
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
                    label = { Text("Depth Type") },
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
                    label = { Text("Threshold") },
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
