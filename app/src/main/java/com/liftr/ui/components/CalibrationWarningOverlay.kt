package com.liftr.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.liftr.R
import com.liftr.domain.model.CalibrationQuality

@Composable
fun CalibrationWarningOverlay(
    calibrationQuality: CalibrationQuality,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }
    val announcement = stringResource(R.string.content_desc_calibration_warning)

    AnimatedVisibility(
        visible = calibrationQuality.shouldShowWarning(),
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val backgroundColor = when (calibrationQuality) {
            CalibrationQuality.ESTIMATED -> Color(0xFFFF9800).copy(alpha = 0.92f)
            CalibrationQuality.FALLBACK, CalibrationQuality.NONE -> Color(0xFFD32F2F).copy(alpha = 0.92f)
            else -> Color.Transparent
        }

        val shortLabel = calibrationQuality.getShortLabel()

        Row(
            modifier = Modifier
                .wrapContentWidth()
                .heightIn(min = 32.dp)
                .background(backgroundColor, RoundedCornerShape(16.dp))
                .clickable { showDialog = true }
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = "$announcement: $shortLabel"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = shortLabel,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White
            )
        }
    }

    if (showDialog) {
        CalibrationDetailDialog(
            calibrationQuality = calibrationQuality,
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
private fun CalibrationDetailDialog(
    calibrationQuality: CalibrationQuality,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.calibration_quality_dialog_title)) },
        text = {
            Text(
                text = calibrationQuality.getWarningMessage()
                    ?: calibrationQuality.getShortLabel()
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.calibration_quality_dialog_ok))
            }
        }
    )
}

@Composable
private fun CalibrationQuality.getShortLabel(): String = when (this) {
    CalibrationQuality.OPTIMAL -> ""
    CalibrationQuality.ESTIMATED -> stringResource(R.string.calibration_quality_short_estimated)
    CalibrationQuality.FALLBACK -> stringResource(R.string.calibration_quality_short_fallback)
    CalibrationQuality.NONE -> stringResource(R.string.calibration_quality_short_none)
}
