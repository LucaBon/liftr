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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lifthelper.R
import com.lifthelper.domain.model.VelocityData
import com.lifthelper.domain.model.VelocityZone
import com.lifthelper.ui.theme.*

@Composable
fun RealTimeFeedbackOverlay(
    velocityData: VelocityData?,
    repCount: Int,
    targetVelocityRange: ClosedFloatingPointRange<Float>?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Velocity display with color zone (showing bar speed for front squats)
        velocityData?.let { data ->
            VelocityDisplay(
                velocity = data.average,
                zone = VelocityZone.fromVelocity(data.average)
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Rep counter
        Text(
            text = stringResource(R.string.reps_label, repCount),
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // Target velocity range indicator
        targetVelocityRange?.let { range ->
            velocityData?.let { data ->
                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(16.dp)
                ) {
                    val progress = ((data.average - range.start) /
                            (range.endInclusive - range.start)).coerceIn(0f, 1f)

                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
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
    modifier: Modifier = Modifier
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
            .background(zoneColor.copy(alpha = 0.9f), RoundedCornerShape(16.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.velocity_label),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White
        )

        Text(
            text = "%.2f m/s".format(velocity),
            style = MaterialTheme.typography.displayLarge,
            color = Color.White
        )

        Text(
            text = zone.name.replace("_", " "),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White
        )
    }
}
