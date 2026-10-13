package com.liftr.ui.screens.calibration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.liftr.R

@Composable
fun CalibrationScreen(
    onCalibrationComplete: () -> Unit,
    viewModel: CalibrationViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Only show welcome title for initial calibration
        if (!uiState.isRecalibrating) {
            Text(
                text = stringResource(R.string.calibration_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            text = stringResource(R.string.calibration_description),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Height input
        OutlinedTextField(
            value = uiState.height,
            onValueChange = { viewModel.onHeightChanged(it) },
            label = { Text(stringResource(R.string.height_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            isError = uiState.heightError != null,
            supportingText = uiState.heightError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Shoulder width input (optional)
        OutlinedTextField(
            value = uiState.shoulderWidth,
            onValueChange = { viewModel.onShoulderWidthChanged(it) },
            label = { Text(stringResource(R.string.shoulder_width_label) + " (Optional)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            isError = uiState.shoulderWidthError != null,
            supportingText = uiState.shoulderWidthError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Continue/Update button
        Button(
            onClick = { viewModel.onContinue(onCalibrationComplete) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (uiState.isRecalibrating) "Update" else stringResource(R.string.continue_button))
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Skip button
        TextButton(
            onClick = { viewModel.onSkip(onCalibrationComplete) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.skip_button))
        }
    }
}
