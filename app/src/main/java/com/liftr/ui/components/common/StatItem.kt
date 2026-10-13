package com.liftr.ui.components.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign

/**
 * Shared stat display used across Workout, History, and Analytics screens.
 * Shows a bold performance number above an uppercase descriptive label,
 * center-aligned. The value uses the display typeface for an athletic,
 * data-forward feel.
 */
@Composable
fun StatItem(
    label: String,
    value: String,
    valueStyle: TextStyle = MaterialTheme.typography.titleLarge,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = valueStyle,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            textAlign = TextAlign.Center
        )
    }
}
