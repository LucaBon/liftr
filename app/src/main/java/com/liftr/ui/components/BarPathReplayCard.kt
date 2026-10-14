package com.liftr.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.liftr.R
import com.liftr.domain.exercise.BarPathAnalyzer
import com.liftr.domain.model.BarPathData
import com.liftr.domain.model.DriftDirection
import com.liftr.ui.theme.BarPathEnd
import com.liftr.ui.theme.BarPathReference
import com.liftr.ui.theme.BarPathStart
import kotlin.math.abs

/**
 * Post-set bar path replay card with rep-by-rep navigation.
 *
 * Displays a scaled canvas showing the full bar path for the selected rep,
 * with ideal vertical reference, start/end markers, max deviation annotation,
 * and bar path metrics.
 */
@Composable
fun BarPathReplayCard(
    repBarPaths: List<BarPathData>,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.bar_path_title),
    /**
     * Drift seen from the front (jumps): sideways, named from the athlete's
     * point of view. Recordings aren't mirrored, so image right is their left.
     */
    frontView: Boolean = false
) {
    if (repBarPaths.isEmpty()) return

    var selectedRepIndex by remember { mutableIntStateOf(0) }
    val currentPath = repBarPaths[selectedRepIndex]

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Title
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Rep navigator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { if (selectedRepIndex > 0) selectedRepIndex-- },
                    enabled = selectedRepIndex > 0
                ) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Previous rep")
                }

                Text(
                    text = stringResource(
                        R.string.bar_path_rep_format,
                        selectedRepIndex + 1,
                        repBarPaths.size
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )

                IconButton(
                    onClick = { if (selectedRepIndex < repBarPaths.size - 1) selectedRepIndex++ },
                    enabled = selectedRepIndex < repBarPaths.size - 1
                ) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Next rep")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Bar path canvas
            if (currentPath.isValid) {
                BarPathCanvas(
                    barPathData = currentPath,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.75f) // 3:4 aspect ratio
                )
            } else {
                Text(
                    text = "Insufficient data for this rep",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Metrics row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricItem(
                    label = stringResource(R.string.bar_path_max_drift),
                    value = "%.1f cm".format(currentPath.maxDriftCm)
                )
                MetricItem(
                    label = stringResource(R.string.bar_path_linearity),
                    value = "${currentPath.linearityScore}/100"
                )
                MetricItem(
                    label = stringResource(R.string.bar_path_drift_direction),
                    value = when (currentPath.driftDirection) {
                        DriftDirection.FORWARD -> stringResource(
                            if (frontView) R.string.drift_your_left else R.string.bar_path_drift_forward
                        )
                        DriftDirection.BACKWARD -> stringResource(
                            if (frontView) R.string.drift_your_right else R.string.bar_path_drift_backward
                        )
                        DriftDirection.NONE -> stringResource(R.string.bar_path_drift_centered)
                    }
                )
            }

            currentPath.excursion?.let { excursion ->
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MetricItem(
                        label = stringResource(R.string.jump_countermovement),
                        value = "%.0f cm".format(excursion.countermovementCm)
                    )
                    MetricItem(
                        label = stringResource(R.string.jump_peak_rise),
                        value = "%.0f cm".format(excursion.peakRiseCm)
                    )
                    excursion.flightRiseCm?.let {
                        MetricItem(
                            label = stringResource(R.string.jump_com_height),
                            value = "%.0f cm".format(it)
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.jump_from_standing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BarPathCanvas(
    barPathData: BarPathData,
    modifier: Modifier = Modifier
) {
    val points = barPathData.points
    if (points.size < 2) return

    Canvas(modifier = modifier) {
        val padding = 24f

        // Calculate bounds from real-world coordinates
        val standingY = barPathData.excursion?.standingYCm
        val xValues = points.map { it.realWorldXCm }
        val yValues = points.map { it.realWorldYCm } + listOfNotNull(standingY)

        val minX = xValues.min()
        val maxX = xValues.max()
        val minY = yValues.min()
        val maxY = yValues.max()

        // Add margin so path doesn't touch edges
        val rangeX = (maxX - minX).coerceAtLeast(2f) // minimum 2cm range
        val rangeY = (maxY - minY).coerceAtLeast(2f)

        val drawWidth = size.width - padding * 2
        val drawHeight = size.height - padding * 2

        // Scale to fit, maintaining aspect ratio
        val scaleX = drawWidth / rangeX
        val scaleY = drawHeight / rangeY
        val scale = minOf(scaleX, scaleY)

        val centerOffsetX = (size.width - rangeX * scale) / 2f
        val centerOffsetY = (size.height - rangeY * scale) / 2f

        // Image y grows downward, like the canvas: no flip.
        fun toCanvas(xCm: Float, yCm: Float): Offset {
            return Offset(
                x = (xCm - minX) * scale + centerOffsetX,
                y = (yCm - minY) * scale + centerOffsetY
            )
        }

        // Standing height of the center of mass (jumps)
        if (standingY != null) {
            drawLine(
                color = Color.White.copy(alpha = 0.5f),
                start = Offset(padding, toCanvas(minX, standingY).y),
                end = Offset(size.width - padding, toCanvas(minX, standingY).y),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 6f))
            )
        }

        // Draw ideal vertical reference (from first point's X)
        val idealX = points.first().realWorldXCm
        val refTop = toCanvas(idealX, maxY)
        val refBottom = toCanvas(idealX, minY)
        drawLine(
            color = BarPathReference,
            start = refTop,
            end = refBottom,
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
        )

        // Draw velocity-colored path segments
        for (i in 0 until points.size - 1) {
            val current = points[i]
            val next = points[i + 1]

            val colorArgb = BarPathAnalyzer.velocityToColorArgb(next.velocity)
            drawLine(
                color = Color(colorArgb),
                start = toCanvas(current.realWorldXCm, current.realWorldYCm),
                end = toCanvas(next.realWorldXCm, next.realWorldYCm),
                strokeWidth = 3f
            )
        }

        // Draw max deviation annotation
        val maxDevPoint = points.maxByOrNull { abs(it.realWorldXCm - idealX) }
        if (maxDevPoint != null && abs(maxDevPoint.realWorldXCm - idealX) > 0.1f) {
            val devStart = toCanvas(idealX, maxDevPoint.realWorldYCm)
            val devEnd = toCanvas(maxDevPoint.realWorldXCm, maxDevPoint.realWorldYCm)
            drawLine(
                color = Color.White.copy(alpha = 0.6f),
                start = devStart,
                end = devEnd,
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
            )
        }

        // Start marker (green dot)
        val startPoint = points.first()
        drawCircle(
            color = BarPathStart,
            radius = 8f,
            center = toCanvas(startPoint.realWorldXCm, startPoint.realWorldYCm)
        )

        // End marker (red dot)
        val endPoint = points.last()
        drawCircle(
            color = BarPathEnd,
            radius = 8f,
            center = toCanvas(endPoint.realWorldXCm, endPoint.realWorldYCm)
        )
    }
}
