package com.lifthelper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifthelper.R
import com.lifthelper.domain.exercise.VelocityLossState
import com.lifthelper.domain.model.VbtZone
import com.lifthelper.domain.model.VelocityData
import com.lifthelper.domain.model.VelocityZone
import com.lifthelper.domain.value.RepCount
import com.lifthelper.ui.theme.*
import com.lifthelper.util.OrientationMode
import com.lifthelper.util.rememberOrientation

@Composable
fun RealTimeFeedbackOverlay(
    velocityData: VelocityData?,
    repCount: RepCount,
    targetVelocityRange: ClosedFloatingPointRange<Float>?,
    modifier: Modifier = Modifier,
    vbtZone: VbtZone? = null,
    velocityLossState: VelocityLossState = VelocityLossState(),
    vbtZonesEnabled: Boolean = true
) {
    val orientation = rememberOrientation()
    val isPortrait = orientation == OrientationMode.PORTRAIT
    // Always use the compact variant: landscape has very little vertical space,
    // and the large variant covered the center of the preview.
    val isCompact = true

    Column(
        modifier = modifier
            .then(if (isPortrait) Modifier.fillMaxWidth() else Modifier.widthIn(max = 260.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Velocity display with VBT zone coloring
        velocityData?.let { data ->
            if (vbtZonesEnabled && vbtZone != null) {
                VbtZoneVelocityDisplay(
                    velocity = data.average.value,
                    vbtZone = vbtZone,
                    isCompact = isCompact
                )
            } else {
                VelocityDisplay(
                    velocity = data.average.value,
                    zone = VelocityZone.fromVelocity(data.average),
                    isCompact = isCompact
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
        }

        // Rep counter
        Text(
            text = stringResource(R.string.reps_label, repCount.value),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )

        // Velocity loss bar (shown from rep 2 onwards when tracking is enabled)
        if (velocityLossState.isEnabled && velocityLossState.repCount >= 2) {
            Spacer(modifier = Modifier.height(6.dp))

            VelocityLossBar(
                lossPercent = velocityLossState.currentLossPercent,
                threshold = velocityLossState.threshold,
                isCompact = isCompact
            )
        }

        // Target velocity range indicator
        targetVelocityRange?.let { range ->
            velocityData?.let { data ->
                Spacer(modifier = Modifier.height(6.dp))

                Column(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    val progress = ((data.average.value - range.start) /
                            (range.endInclusive - range.start)).coerceIn(0f, 1f)

                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (isPortrait) 6.dp else 8.dp),
                        color = if (progress in 0.4f..0.6f) VelocityGreen else VelocityOrange
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = stringResource(R.string.target_range, range.start, range.endInclusive),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun VelocityDisplay(
    velocity: Float,
    zone: VelocityZone,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false
) {
    val zoneColor = when (zone) {
        VelocityZone.VERY_SLOW -> VelocityRed
        VelocityZone.SLOW -> VelocityOrange
        VelocityZone.MODERATE -> VelocityYellow
        VelocityZone.FAST -> VelocityLightGreen
        VelocityZone.VERY_FAST -> VelocityGreen
    }

    Column(
        modifier = modifier
            .background(zoneColor.copy(alpha = 0.9f), RoundedCornerShape(if (isCompact) 12.dp else 16.dp))
            .padding(if (isCompact) 16.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.velocity_label),
            style = if (isCompact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            color = Color.White
        )

        Text(
            text = "%.2f m/s".format(velocity),
            style = if (isCompact) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge,
            color = Color.White
        )

        Text(
            text = zone.name.replace("_", " "),
            style = if (isCompact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = Color.White
        )
    }
}

@Composable
fun VbtZoneVelocityDisplay(
    velocity: Float,
    vbtZone: VbtZone,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false
) {
    val zoneColor = Color(vbtZone.colorHex)

    Column(
        modifier = modifier
            .background(zoneColor.copy(alpha = 0.9f), RoundedCornerShape(if (isCompact) 12.dp else 16.dp))
            .padding(if (isCompact) 16.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.velocity_label),
            style = if (isCompact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            color = Color.White
        )

        Text(
            text = "%.2f m/s".format(velocity),
            style = if (isCompact) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge,
            color = Color.White
        )

        Text(
            text = vbtZone.displayName,
            style = if (isCompact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun VelocityLossBar(
    lossPercent: Float,
    threshold: Float,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false
) {
    val lossInt = (lossPercent * 100).toInt()
    // Normalize loss to a 0-1 scale where 1.0 = 40% loss (for visual range)
    val maxDisplayLoss = 0.40f
    val normalizedLoss = (lossPercent / maxDisplayLoss).coerceIn(0f, 1f)
    val normalizedThreshold = (threshold / maxDisplayLoss).coerceIn(0f, 1f)
    val isOverThreshold = lossPercent >= threshold

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(if (isCompact) 8.dp else 12.dp)
    ) {
        // Progress bar with threshold marker
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isCompact) 8.dp else 10.dp)
        ) {
            // Background track
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.2f))
            )

            // Fill bar
            Box(
                modifier = Modifier
                    .fillMaxWidth(normalizedLoss)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isOverThreshold) VbtStopAlert else VelocityGreen)
            )

            // Threshold marker
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(2.dp)
                    .offset(x = (normalizedThreshold * 100).dp) // Approximate positioning
                    .background(Color.White)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.vbt_velocity_loss_label, lossInt),
            style = MaterialTheme.typography.bodySmall,
            color = if (isOverThreshold) VbtStopAlert else Color.White
        )
    }
}
