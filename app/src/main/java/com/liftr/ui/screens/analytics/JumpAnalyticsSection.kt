package com.liftr.ui.screens.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.liftr.R
import com.liftr.domain.model.JumpRepRecord
import com.liftr.domain.model.allJumpsSuspect
import com.liftr.domain.model.jumpsThatCount
import com.liftr.ui.components.BarPathReplayCard
import com.liftr.ui.components.charts.ChartSeries
import com.liftr.ui.components.charts.MultiLineTrendChart
import com.liftr.ui.components.common.StatItem
import com.liftr.ui.theme.ChartPrimary
import com.liftr.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ComColor = Color(0xFFFF9800)
private val ApexColor = Color(0xFF4CAF50)
private val DipColor = Color(0xFF2196F3)
private val PeakColor = Color(0xFFE91E63)
private val VelocityColor = Color(0xFF9C27B0)

/**
 * Jump analytics: averages, jump height and center-of-mass trends per jump,
 * and the latest set's center-of-mass paths. Jumps that failed the free-fall
 * check are left out of the averages and trends (see [jumpsThatCount]).
 */
@Composable
fun JumpAnalyticsSection(jumps: List<JumpRepRecord>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        if (jumps.isEmpty()) {
            InfoCard(stringResource(R.string.analytics_no_jumps))
            return@Column
        }

        // Jumps whose flight failed the free-fall check were tracked badly: keep
        // them out of the averages and trends, and say how many were left out.
        val counted = jumps.jumpsThatCount { it.quality }
        val excluded = jumps.size - counted.size
        when {
            jumps.allJumpsSuspect { it.quality } -> InfoCard(stringResource(R.string.jump_all_suspect))
            excluded > 0 -> InfoCard(pluralStringResource(R.plurals.jump_suspect_excluded, excluded, excluded))
        }

        JumpAveragesCard(counted)

        val dateFormat = remember { SimpleDateFormat("dd/MM", Locale.getDefault()) }
        val xLabels = counted.map { dateFormat.format(Date(it.timestamp)) }

        AnalyticsSectionTitle(stringResource(R.string.analytics_jump_height))
        MultiLineTrendChart(
            series = listOf(
                ChartSeries(stringResource(R.string.analytics_jump_flight_time), ChartPrimary, counted.map { it.jump.flightHeightCm }),
                ChartSeries(stringResource(R.string.analytics_jump_takeoff_to_apex), ApexColor, counted.map { it.jump.apexHeightCm }),
                ChartSeries(stringResource(R.string.analytics_jump_com_displacement), ComColor, counted.map { it.jump.comFlightRiseCm }),
                ChartSeries(stringResource(R.string.analytics_jump_takeoff_velocity), VelocityColor, counted.map { it.jump.velocityHeightCm })
            ),
            xLabels = xLabels,
            unit = "cm"
        )

        if (counted.any { it.jump.takeoffVelocity != null }) {
            AnalyticsSectionTitle(stringResource(R.string.jump_takeoff_velocity))
            MultiLineTrendChart(
                series = listOf(
                    ChartSeries(stringResource(R.string.jump_takeoff_velocity), VelocityColor, counted.map { it.jump.takeoffVelocity })
                ),
                xLabels = xLabels,
                unit = "m/s",
                decimals = 1
            )
        }

        if (counted.any { it.jump.countermovementCm != null }) {
            AnalyticsSectionTitle(stringResource(R.string.jump_from_standing))
            MultiLineTrendChart(
                series = listOf(
                    ChartSeries(stringResource(R.string.jump_countermovement), DipColor, counted.map { it.jump.countermovementCm }),
                    ChartSeries(stringResource(R.string.jump_peak_rise), PeakColor, counted.map { it.jump.peakRiseCm })
                ),
                xLabels = xLabels,
                unit = "cm"
            )
        } else {
            InfoCard(stringResource(R.string.analytics_jump_com_hint))
        }

        // The paths are where a badly tracked jump shows, so suspect ones stay in.
        val latestSet = jumps.last().setId
        val paths = jumps.filter { it.setId == latestSet && it.jump.path.size >= 3 }
            .map { it.jump.toBarPathData(it.repNumber) }
        if (paths.isNotEmpty()) {
            BarPathReplayCard(
                repBarPaths = paths,
                title = stringResource(R.string.analytics_latest_com_paths)
            )
        }
    }
}

@Composable
private fun JumpAveragesCard(jumps: List<JumpRepRecord>) {
    fun avg(values: List<Float?>): String =
        values.filterNotNull().takeIf { it.isNotEmpty() }?.average()?.let { "%.0f cm".format(it) } ?: "-"
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Text(
                text = stringResource(R.string.analytics_jump_averages, jumps.size),
                style = MaterialTheme.typography.titleSmall
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(label = stringResource(R.string.analytics_jump_flight_time), value = avg(jumps.map { it.jump.flightHeightCm }))
                StatItem(label = stringResource(R.string.analytics_jump_takeoff_to_apex), value = avg(jumps.map { it.jump.apexHeightCm }))
                StatItem(label = stringResource(R.string.analytics_jump_com_displacement), value = avg(jumps.map { it.jump.comFlightRiseCm }))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(label = stringResource(R.string.jump_countermovement), value = avg(jumps.map { it.jump.countermovementCm }))
                StatItem(label = stringResource(R.string.jump_peak_rise), value = avg(jumps.map { it.jump.peakRiseCm }))
                StatItem(label = stringResource(R.string.analytics_jump_takeoff_velocity), value = avg(jumps.map { it.jump.velocityHeightCm }))
            }
            jumps.mapNotNull { it.jump.takeoffVelocity }.takeIf { it.isNotEmpty() }?.let { speeds ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatItem(label = stringResource(R.string.jump_takeoff_velocity), value = "%.2f m/s".format(speeds.average()))
                    StatItem(label = stringResource(R.string.analytics_best_takeoff_velocity), value = "%.2f m/s".format(speeds.max()))
                }
            }
            jumps.mapNotNull { it.jump.peakPowerW }.takeIf { it.isNotEmpty() }?.let { powers ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatItem(label = stringResource(R.string.jump_peak_power), value = "%.0f W".format(powers.average()))
                    StatItem(label = stringResource(R.string.analytics_best_peak_power), value = "%.0f W".format(powers.max()))
                }
            }
        }
    }
}

@Composable
private fun AnalyticsSectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun InfoCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(Spacing.md),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
