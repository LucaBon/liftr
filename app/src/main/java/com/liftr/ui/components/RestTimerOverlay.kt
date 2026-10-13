package com.liftr.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liftr.R
import com.liftr.domain.model.RestTimerState

@Composable
fun RestTimerOverlay(
    timerState: RestTimerState,
    onSkip: () -> Unit,
    onExtend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progressFraction = timerState.progress
    val timerColor by animateColorAsState(
        targetValue = when {
            progressFraction < 0.50f -> RestTimerGreen
            progressFraction < 0.75f -> RestTimerYellow
            else -> RestTimerRed
        },
        label = "timerColor"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .background(
                    MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(24.dp)
                )
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.rest_timer_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Large countdown
            Text(
                text = "%d:%02d".format(timerState.displayMinutes, timerState.displaySeconds),
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                color = timerColor
            )

            // Progress bar
            LinearProgressIndicator(
                progress = { 1f - progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = timerColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            // Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                OutlinedButton(onClick = onSkip) {
                    Text(stringResource(R.string.rest_timer_skip))
                }
                Button(onClick = onExtend) {
                    Text(stringResource(R.string.rest_timer_extend))
                }
            }
        }
    }
}

private val RestTimerGreen = Color(0xFF4CAF50)
private val RestTimerYellow = Color(0xFFFFC107)
private val RestTimerRed = Color(0xFFF44336)
