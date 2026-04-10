package com.lifthelper.ui.components.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Shared stat display component used across Workout, History, and Analytics screens.
 * Shows a numeric value above a descriptive label, center-aligned.
 */
@Composable
fun StatItem(
    label: String,
    value: String,
    valueStyle: TextStyle = MaterialTheme.typography.titleMedium,
    valueColor: Color = Color.Unspecified,
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
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor
        )
    }
}
