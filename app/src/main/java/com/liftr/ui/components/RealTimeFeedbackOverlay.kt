package com.liftr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liftr.R
import com.liftr.domain.exercise.VelocityLossState
import com.liftr.domain.model.VbtZone
import com.liftr.domain.model.VelocityData
import com.liftr.domain.model.VelocityZone
import com.liftr.domain.value.RepCount
import com.liftr.ui.theme.*
import com.liftr.util.OrientationMode
import com.liftr.util.rememberOrientation

/**
 * Primary live-workout HUD: a horizontal strip with a large rep-count badge
 * (dominant glance target) next to a compact velocity card. Velocity loss and
 * calibration warnings are rendered as separate peripheral components.
 */
@Composable
fun RealTimeFeedbackOverlay(
    velocityData: VelocityData?,
    repCount: RepCount,
    targetReps: RepCount,
    targetVelocityRange: ClosedFloatingPointRange<Float>?,
    modifier: Modifier = Modifier,
    vbtZone: VbtZone? = null,
    vbtZonesEnabled: Boolean = true,
    lastRepDepthOk: Boolean? = null
) {
    val orientation = rememberOrientation()
    val isPortrait = orientation == OrientationMode.PORTRAIT

    Row(
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .then(if (isPortrait) Modifier.fillMaxWidth() else Modifier.widthIn(max = 340.dp)),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Top
    ) {
        RepCountBadge(
            repCount = repCount.value,
            targetReps = targetReps.value,
            depthOk = lastRepDepthOk
        )

        velocityData?.let { data ->
            val zoneColor = if (vbtZonesEnabled && vbtZone != null) {
                Color(vbtZone.colorHex)
            } else {
                when (VelocityZone.fromVelocity(data.average)) {
                    VelocityZone.VERY_SLOW -> VelocityRed
                    VelocityZone.SLOW -> VelocityOrange
                    VelocityZone.MODERATE -> VelocityYellow
                    VelocityZone.FAST -> VelocityLightGreen
                    VelocityZone.VERY_FAST -> VelocityGreen
                }
            }
            val zoneLabel = if (vbtZonesEnabled && vbtZone != null) {
                vbtZone.displayName
            } else {
                VelocityZone.fromVelocity(data.average).name.replace("_", " ")
            }

            CompactVelocityCard(
                velocity = data.average.value,
                zoneColor = zoneColor,
                zoneLabel = zoneLabel,
                targetVelocityRange = targetVelocityRange
            )
        }
    }

}

@Composable
fun RepCountBadge(
    repCount: Int,
    targetReps: Int,
    modifier: Modifier = Modifier,
    depthOk: Boolean? = null
) {
    val announcement = stringResource(R.string.rep_count_announcement, repCount, targetReps)
    val depthFailed = depthOk == false
    val badgeBackground = if (depthFailed) {
        VbtStopAlert.copy(alpha = 0.85f)
    } else {
        Color.Black.copy(alpha = 0.65f)
    }
    val depthCueText = if (depthFailed) stringResource(R.string.depth_insufficient_cue) else null
    Column(
        modifier = modifier
            .size(width = 96.dp, height = 96.dp)
            .background(badgeBackground, RoundedCornerShape(16.dp))
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = announcement + (depthCueText?.let { " — $it" } ?: "")
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = repCount.toString(),
            color = Color.White,
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.displayMedium
        )
        if (depthCueText != null) {
            Text(
                text = depthCueText,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
        } else {
            Text(
                text = stringResource(R.string.reps_badge_label),
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun CompactVelocityCard(
    velocity: Float,
    zoneColor: Color,
    zoneLabel: String,
    targetVelocityRange: ClosedFloatingPointRange<Float>?,
    modifier: Modifier = Modifier
) {
    // Dark-glass panel so the velocity number reads as the hero metric, with a
    // soft volt halo behind it for an energetic glow. The zone is signalled by
    // a colored border, label, and progress bar rather than the panel fill.
    Column(
        modifier = modifier
            .widthIn(min = 140.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.62f))
            .border(1.5.dp, zoneColor.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "%.2f m/s".format(velocity),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.drawBehind {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                GradientVoltStart.copy(alpha = 0.55f),
                                GradientVoltEnd.copy(alpha = 0.18f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = size.maxDimension * 0.85f
                        ),
                        radius = size.maxDimension,
                        center = center
                    )
                }
            )
        }
        Text(
            text = zoneLabel,
            color = zoneColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )

        targetVelocityRange?.let { range ->
            val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
            val progress = ((velocity - range.start) / span).coerceIn(0f, 1f)
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = zoneColor,
                trackColor = Color.White.copy(alpha = 0.25f)
            )
        }
    }
}

/**
 * Peripheral velocity-loss indicator. Rendered separately from the primary HUD
 * strip so it can sit near the bottom of the screen (above the Stop button)
 * without competing with rep count and velocity for glance attention.
 */
@Composable
fun VelocityLossStrip(
    velocityLossState: VelocityLossState,
    modifier: Modifier = Modifier
) {
    if (!velocityLossState.isEnabled || velocityLossState.repCount < 2) return
    VelocityLossBar(
        lossPercent = velocityLossState.currentLossPercent,
        threshold = velocityLossState.threshold,
        modifier = modifier
            .widthIn(max = 320.dp)
            .fillMaxWidth()
    )
}

@Composable
fun VelocityLossBar(
    lossPercent: Float,
    threshold: Float,
    modifier: Modifier = Modifier
) {
    val lossInt = (lossPercent * 100).toInt()
    val maxDisplayLoss = 0.40f
    val normalizedLoss = (lossPercent / maxDisplayLoss).coerceIn(0f, 1f)
    val normalizedThreshold = (threshold / maxDisplayLoss).coerceIn(0f, 1f)
    val isOverThreshold = lossPercent >= threshold

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.2f))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(normalizedLoss)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isOverThreshold) VbtStopAlert else VelocityGreen)
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(2.dp)
                    .offset(x = (normalizedThreshold * 100).dp)
                    .background(Color.White)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.vbt_velocity_loss_label, lossInt),
            style = MaterialTheme.typography.bodySmall,
            color = if (isOverThreshold) Color.White else Color.White.copy(alpha = 0.85f),
            fontWeight = if (isOverThreshold) FontWeight.Bold else FontWeight.Normal
        )
    }
}
