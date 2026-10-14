package com.liftr.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.liftr.R
import com.liftr.vision.camera.LensDistortionCorrector

/**
 * Dialog showing measurement accuracy information to users.
 *
 * Explains how lens distortion affects measurements and what the app
 * does to mitigate it.
 */
@Composable
fun AccuracyInfoDialog(
    onDismiss: () -> Unit,
    lensCorrection: LensDistortionCorrector.CorrectionMode = LensDistortionCorrector.CorrectionMode.AUTO,
    hasIntrinsics: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Accuracy Info"
            )
        },
        title = {
            Text(text = stringResource(R.string.accuracy_info_title))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.accuracy_info_description),
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Accuracy sections
                AccuracySection(
                    title = "✓ Best Accuracy (98-99%)",
                    description = stringResource(R.string.accuracy_centered),
                    color = MaterialTheme.colorScheme.primary
                )

                AccuracySection(
                    title = "⚠ Reduced Accuracy (90-95%)",
                    description = stringResource(R.string.accuracy_edge),
                    color = MaterialTheme.colorScheme.tertiary
                )

                AccuracySection(
                    title = "Optimal Conditions",
                    description = stringResource(R.string.accuracy_optimal),
                    color = MaterialTheme.colorScheme.secondary
                )

                Divider(modifier = Modifier.padding(vertical = 8.dp))

                // Lens distortion correction status
                Text(
                    text = "Lens Distortion Correction",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                val correctionStatus = when {
                    lensCorrection == LensDistortionCorrector.CorrectionMode.NONE ->
                        stringResource(R.string.lens_correction_disabled)
                    hasIntrinsics ->
                        stringResource(R.string.lens_correction_enabled)
                    else ->
                        stringResource(R.string.lens_correction_empirical)
                }

                Text(
                    text = correctionStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = stringResource(R.string.lens_correction_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Divider(modifier = Modifier.padding(vertical = 8.dp))

                // Orientation info
                Text(
                    text = "Orientation",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = stringResource(R.string.orientation_per_exercise),
                    style = MaterialTheme.typography.bodySmall
                )

                Divider(modifier = Modifier.padding(vertical = 8.dp))

                // Tip
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        text = stringResource(R.string.accuracy_tip),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        }
    )
}

@Composable
private fun AccuracySection(
    title: String,
    description: String,
    color: androidx.compose.ui.graphics.Color
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
