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
import androidx.compose.ui.res.stringResource
import com.liftr.R
import com.liftr.domain.model.JumpRepRecord
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

/**
 * Jump analytics: averages, jump height and center-of-mass trends per jump,
 * and the latest set's center-of-mass paths.
 */
@Composable
fun JumpAnalyticsSection(jumps: List<JumpRepRecord>, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        if (jumps.isEmpty()) {
            InfoCard(stringResource(R.string.analytics_no_jumps))
            return@Column
        }

        JumpAveragesCard(jumps)

        val dateFormat = remember { SimpleDateFormat("dd/MM", Locale.getDefault()) }
        val xLabels = jumps.map { dateFormat.format(Date(it.timestamp)) }

        AnalyticsSectionTitle(stringResource(R.string.analytics_jump_height))
        MultiLineTrendChart(
            series = listOf(
                ChartSeries(stringResource(R.string.analytics_jump_flight_time), ChartPrimary, jumps.map { it.jump.flightHeightCm }),
                ChartSeries(stringResource(R.string.analytics_jump_takeoff_to_apex), ApexColor, jumps.map { it.jump.apexHeightCm }),
                ChartSeries(stringResource(R.string.analytics_jump_com_displacement), ComColor, jumps.map { it.jump.comFlightRiseCm })
            ),
            xLabels = xLabels,
            unit = "cm"
        )

        if (jumps.any { it.jump.countermovementCm != null }) {
            AnalyticsSectionTitle(stringResource(R.string.jump_from_standing))
            MultiLineTrendChart(
                series = listOf(
                    ChartSeries(stringResource(R.string.jump_countermovement), DipColor, jumps.map { it.jump.countermovementCm }),
                    ChartSeries(stringResource(R.string.jump_peak_rise), PeakColor, jumps.map { it.jump.peakRiseCm })
                ),
                xLabels = xLabels,
                unit = "cm"
            )
        } else {
            InfoCard(stringResource(R.string.analytics_jump_com_hint))
        }

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
