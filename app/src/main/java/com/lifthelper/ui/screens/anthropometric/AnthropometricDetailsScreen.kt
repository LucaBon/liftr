package com.lifthelper.ui.screens.anthropometric

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnthropometricDetailsScreen(
    onNavigateBack: () -> Unit,
    viewModel: AnthropometricDetailsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Body Measurements") },
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
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            if (!uiState.hasData) {
                // No data available
                Text(
                    text = "No body measurements available",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp)
                )
                Text(
                    text = "Go to Settings → Body Measurements to capture your measurements using the camera.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                )
            } else {
                // Quality indicator
                QualityCard(quality = uiState.captureQuality)

                Spacer(modifier = Modifier.height(8.dp))

                // Alignment warning if present
                uiState.alignmentMessage?.let { message ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Text(
                            text = "⚠️ Alignment Issue: $message",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Spacer(modifier = Modifier.height(16.dp))

                // User height and weight
                Text(
                    text = "General",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                OutlinedTextField(
                    value = uiState.userHeight,
                    onValueChange = { viewModel.onUserHeightChanged(it) },
                    label = { Text("Total Height (cm)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = uiState.userWeight,
                    onValueChange = { viewModel.onUserWeightChanged(it) },
                    label = { Text("Body Weight (kg) - Optional") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Vertical segments
                Text(
                    text = "Vertical Segments (cm)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                MeasurementField("Neck Height", uiState.neckHeight) { viewModel.onNeckHeightChanged(it) }
                MeasurementField("Torso Height", uiState.torsoHeight) { viewModel.onTorsoHeightChanged(it) }
                MeasurementField("Upper Leg (Thigh)", uiState.upperLegHeight) { viewModel.onUpperLegHeightChanged(it) }
                MeasurementField("Lower Leg (Shin)", uiState.lowerLegHeight) { viewModel.onLowerLegHeightChanged(it) }

                Spacer(modifier = Modifier.height(24.dp))

                // Widths
                Text(
                    text = "Body Widths (cm)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                MeasurementField("Shoulder Width", uiState.shoulderWidth) { viewModel.onShoulderWidthChanged(it) }
                MeasurementField("Hip Width", uiState.hipWidth) { viewModel.onHipWidthChanged(it) }

                Spacer(modifier = Modifier.height(24.dp))

                // Limb lengths
                Text(
                    text = "Limb Lengths (cm)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                MeasurementField("Upper Arm", uiState.upperArmLength) { viewModel.onUpperArmLengthChanged(it) }
                MeasurementField("Forearm", uiState.forearmLength) { viewModel.onForearmLengthChanged(it) }
                MeasurementField("Thigh", uiState.thighLength) { viewModel.onThighLengthChanged(it) }
                MeasurementField("Shin", uiState.shinLength) { viewModel.onShinLengthChanged(it) }

                Spacer(modifier = Modifier.height(32.dp))

                // Save button
                Button(
                    onClick = { viewModel.onSave(onNavigateBack) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Changes")
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Info text
                Text(
                    text = "These measurements are used for advanced biomechanics calculations. You can manually adjust any value for more precise tracking.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun MeasurementField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    )
}

@Composable
private fun QualityCard(quality: com.lifthelper.domain.model.CalibrationQuality) {
    val (text, color) = when (quality) {
        com.lifthelper.domain.model.CalibrationQuality.OPTIMAL ->
            "Excellent quality - Full body captured" to MaterialTheme.colorScheme.primary
        com.lifthelper.domain.model.CalibrationQuality.ESTIMATED ->
            "Good quality - Some measurements estimated" to MaterialTheme.colorScheme.tertiary
        com.lifthelper.domain.model.CalibrationQuality.FALLBACK ->
            "Using standard proportions" to MaterialTheme.colorScheme.error
        com.lifthelper.domain.model.CalibrationQuality.NONE ->
            "No measurement data" to MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier.padding(16.dp)
        )
    }
}
