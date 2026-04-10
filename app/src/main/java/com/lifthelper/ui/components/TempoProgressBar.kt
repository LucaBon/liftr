package com.lifthelper.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifthelper.R
import com.lifthelper.domain.exercise.TempoCue
import com.lifthelper.domain.exercise.TempoState
import com.lifthelper.vision.tracking.MovementPhase

/**
 * Real-time tempo progress overlay shown during recording when tempo mode is active.
 *
 * - For ECCENTRIC/CONCENTRIC: horizontal progress bar with countdown
 * - For BOTTOM/TOP pauses: circular timer
 * - Color: green = on pace, yellow = slightly off, red = >30% deviation
 */
@Composable
fun TempoProgressBar(
    tempoState: TempoState,
    tempoCue: TempoCue?,
    modifier: Modifier = Modifier
) {
    val phase = tempoState.currentPhase
    if (phase == MovementPhase.IDLE) return

    val prescribed = tempoState.prescribedForCurrentPhase()
    if (prescribed == 0f && phase == MovementPhase.CONCENTRIC) {
        // Explosive mode — show "GO!" flash
        ExplosiveIndicator(modifier = modifier)
        return
    }
    if (prescribed == 0f) return

    val now = System.currentTimeMillis()
    val elapsed = tempoState.elapsedInPhase(now)
    val remaining = (prescribed - elapsed).coerceAtLeast(0f)
    val progress = (elapsed / prescribed).coerceIn(0f, 1.5f)

    val barColor = when {
        progress <= 1.0f -> TempoGreen
        progress <= 1.3f -> TempoYellow
        else -> TempoRed
    }

    val phaseLabel = when (phase) {
        MovementPhase.ECCENTRIC -> stringResource(R.string.tempo_phase_eccentric)
        MovementPhase.BOTTOM -> stringResource(R.string.tempo_phase_bottom)
        MovementPhase.CONCENTRIC -> stringResource(R.string.tempo_phase_concentric)
        MovementPhase.TOP -> stringResource(R.string.tempo_phase_top)
        MovementPhase.IDLE -> ""
    }

    val cueText = when (tempoCue) {
        is TempoCue.TooFast -> stringResource(R.string.tempo_cue_slower)
        is TempoCue.TooSlow -> stringResource(R.string.tempo_cue_faster)
        else -> null
    }

    if (phase == MovementPhase.BOTTOM || phase == MovementPhase.TOP) {
        // Circular timer for pause phases
        PauseTimer(
            phaseLabel = phaseLabel,
            remaining = remaining,
            prescribed = prescribed,
            barColor = barColor,
            cueText = cueText,
            modifier = modifier
        )
    } else {
        // Horizontal progress bar for eccentric/concentric
        PhaseProgressBar(
            phaseLabel = phaseLabel,
            remaining = remaining,
            progress = progress,
            barColor = barColor,
            cueText = cueText,
            modifier = modifier
        )
    }
}

@Composable
private fun PhaseProgressBar(
    phaseLabel: String,
    remaining: Float,
    progress: Float,
    barColor: Color,
    cueText: String?,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceAtMost(1f),
        animationSpec = tween(100),
        label = "tempo_progress"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = phaseLabel,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            cueText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge,
                    color = barColor,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "%.1fs".format(remaining),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color.White.copy(alpha = 0.2f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedProgress)
                    .clip(RoundedCornerShape(5.dp))
                    .background(barColor)
            )
        }
    }
}

@Composable
private fun PauseTimer(
    phaseLabel: String,
    remaining: Float,
    prescribed: Float,
    barColor: Color,
    cueText: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = phaseLabel,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(barColor.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "%.0f".format(remaining),
                style = MaterialTheme.typography.headlineMedium,
                color = barColor,
                fontWeight = FontWeight.Bold
            )
        }
        cueText?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = barColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ExplosiveIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.tempo_cue_explosive),
            style = MaterialTheme.typography.titleLarge,
            color = TempoGreen,
            fontWeight = FontWeight.Bold
        )
    }
}

private val TempoGreen = Color(0xFF4CAF50)
private val TempoYellow = Color(0xFFFFEB3B)
private val TempoRed = Color(0xFFE53935)
