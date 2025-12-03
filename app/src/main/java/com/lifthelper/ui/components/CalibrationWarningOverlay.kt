package com.lifthelper.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lifthelper.domain.model.CalibrationQuality

@Composable
fun CalibrationWarningOverlay(
    calibrationQuality: CalibrationQuality,
    modifier: Modifier = Modifier
) {
    // Only show warning if not optimal
    AnimatedVisibility(
        visible = calibrationQuality.shouldShowWarning(),
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        // Color based on severity
        val backgroundColor = when (calibrationQuality) {
            CalibrationQuality.ESTIMATED -> Color(0xFFFF9800).copy(alpha = 0.9f) // Orange
            CalibrationQuality.FALLBACK, CalibrationQuality.NONE -> Color(0xFFD32F2F).copy(alpha = 0.9f) // Red
            else -> Color.Transparent
        }

        val icon = when (calibrationQuality) {
            CalibrationQuality.ESTIMATED -> Icons.Default.Warning
            CalibrationQuality.FALLBACK, CalibrationQuality.NONE -> Icons.Default.Warning
            else -> null
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = MaterialTheme.shapes.medium,
            color = backgroundColor,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                icon?.let {
                    Icon(
                        imageVector = it,
                        contentDescription = "Warning",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Text(
                    text = calibrationQuality.getWarningMessage() ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
