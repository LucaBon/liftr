package com.liftr.ui.screens.analytics

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.liftr.R
import com.liftr.domain.calculator.ForceVelocityDeficit
import com.liftr.domain.calculator.ForceVelocityProfile
import com.liftr.domain.calculator.ForceVelocityProfileResult
import com.liftr.domain.calculator.ForceVelocityRejection
import com.liftr.domain.model.JumpRepRecord
import com.liftr.domain.model.allJumpsSuspect
import com.liftr.domain.model.jumpsThatCount
import com.liftr.domain.model.RepJumpData
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
private val MeanPowerColor = Color(0xFF009688)
private val FvLineColor = Color(0xFF607D8B)

/**
 * Jump analytics: averages, the force-velocity profile for loaded jumps, jump
 * height and center-of-mass trends per jump, and the latest set's
 * center-of-mass paths. Jumps that failed the free-fall check are left out of
 * the averages and trends (see [jumpsThatCount]).
 *
 * @param forceVelocityProfile The profile over the same range; null when the
 *   exercise has none (anything but loaded jumps)
 */
@Composable
fun JumpAnalyticsSection(
    jumps: List<JumpRepRecord>,
    modifier: Modifier = Modifier,
    forceVelocityProfile: ForceVelocityProfileResult? = null
) {
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

        when (forceVelocityProfile) {
            null -> Unit
            is ForceVelocityProfileResult.Fitted -> ForceVelocityProfileCard(forceVelocityProfile.profile)
            is ForceVelocityProfileResult.Rejected -> InfoCard(
                stringResource(
                    if (forceVelocityProfile.reason == ForceVelocityRejection.TOO_FEW_LOADS) R.string.analytics_fv_hint
                    else R.string.analytics_fv_rejected
                )
            )
        }

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

        if (counted.any { it.jump.peakPowerW != null || it.jump.meanPowerW != null }) {
            AnalyticsSectionTitle(stringResource(R.string.analytics_jump_power))
            MultiLineTrendChart(
                series = listOf(
                    ChartSeries(stringResource(R.string.jump_peak_power), PeakColor, counted.map { it.jump.peakPowerW }),
                    ChartSeries(stringResource(R.string.jump_mean_power), MeanPowerColor, counted.map { it.jump.meanPowerW })
                ),
                xLabels = xLabels,
                unit = "W"
            )
        }

        if (counted.any { it.jump.meanForceN != null }) {
            AnalyticsSectionTitle(stringResource(R.string.analytics_jump_push_off_force))
            MultiLineTrendChart(
                series = listOf(
                    ChartSeries(stringResource(R.string.jump_mean_force), DipColor, counted.map { it.jump.meanForceN })
                ),
                xLabels = xLabels,
                unit = "N"
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
            // Push-off fields are all null together, so averaging each over its non-null values matches up.
            val pushOffs = jumps.map { it.jump }.filter { it.meanForceN != null }
            if (pushOffs.isNotEmpty()) {
                fun avgOf(format: String, value: (RepJumpData) -> Float?) =
                    format.format(pushOffs.mapNotNull(value).average())
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatItem(label = stringResource(R.string.jump_push_off), value = avgOf("%.0f cm") { it.pushOffCm })
                    StatItem(label = stringResource(R.string.jump_mean_force), value = avgOf("%.0f N") { it.meanForceN })
                    StatItem(label = stringResource(R.string.jump_mean_velocity), value = avgOf("%.2f m/s") { it.meanVelocity })
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatItem(label = stringResource(R.string.jump_mean_power), value = avgOf("%.0f W") { it.meanPowerW })
                    StatItem(label = stringResource(R.string.jump_relative_force), value = avgOf("%.1f N/kg") { it.relativeForce })
                    StatItem(label = stringResource(R.string.jump_relative_power), value = avgOf("%.1f W/kg") { it.relativePower })
                }
            }
        }
    }
}

/**
 * F0, V0, Pmax and the fit, the slope against the optimal one with the
 * imbalance it means, and the points with their line.
 */
@Composable
private fun ForceVelocityProfileCard(profile: ForceVelocityProfile) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Text(text = stringResource(R.string.analytics_fv_profile), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(R.string.analytics_fv_based_on, profile.points.size, profile.pushOffM * 100f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(label = stringResource(R.string.analytics_fv_f0), value = "%.1f N/kg".format(profile.f0))
                StatItem(label = stringResource(R.string.analytics_fv_v0), value = "%.2f m/s".format(profile.v0))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(label = stringResource(R.string.analytics_fv_pmax), value = "%.1f W/kg".format(profile.pmax))
                StatItem(label = stringResource(R.string.analytics_fv_r2), value = "%.2f".format(profile.rSquared))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(label = stringResource(R.string.analytics_fv_slope), value = "%.1f".format(profile.sfv))
                StatItem(label = stringResource(R.string.analytics_fv_optimal_slope), value = "%.1f".format(profile.optimalSfv))
            }
            Text(
                text = when {
                    profile.isWellBalanced -> stringResource(R.string.analytics_fv_balanced, profile.imbalancePercent)
                    profile.deficit == ForceVelocityDeficit.FORCE ->
                        stringResource(R.string.analytics_fv_force_deficit, profile.imbalancePercent)
                    else -> stringResource(R.string.analytics_fv_velocity_deficit, profile.imbalancePercent)
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Spacing.sm)
            )
            Text(
                text = stringResource(
                    R.string.analytics_fv_heights, profile.profileHeightM * 100f, profile.optimalHeightM * 100f
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (profile.lowConfidence) {
                Text(
                    text = stringResource(R.string.analytics_fv_low_confidence),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }
            ForceVelocityChart(profile, modifier = Modifier.padding(top = Spacing.sm))
        }
    }
}

/**
 * Force against velocity: the best jump per load, labeled with its load, and
 * the fitted line from F0 on the force axis to V0 on the velocity axis.
 * Velocity is continuous here, so this is its own chart rather than a
 * [MultiLineTrendChart], whose x is an event index.
 */
@Composable
private fun ForceVelocityChart(profile: ForceVelocityProfile, modifier: Modifier = Modifier) {
    val legendColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val pointsLabel = stringResource(R.string.analytics_fv_points)
    val lineLabel = stringResource(R.string.analytics_fv_line)

    AndroidView(
        modifier = modifier.fillMaxWidth().height(220.dp),
        factory = { context ->
            LineChart(context).apply {
                description.isEnabled = false
                setTouchEnabled(false)
                setDrawGridBackground(false)
                setNoDataText("")
                legend.apply {
                    isEnabled = true
                    verticalAlignment = Legend.LegendVerticalAlignment.TOP
                    horizontalAlignment = Legend.LegendHorizontalAlignment.LEFT
                    textColor = legendColor
                }
                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM
                    setDrawGridLines(false)
                    textColor = AndroidColor.GRAY
                    axisMinimum = 0f
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float) = "%.1f m/s".format(value)
                    }
                }
                axisLeft.apply {
                    setDrawGridLines(true)
                    gridColor = AndroidColor.parseColor("#20000000")
                    textColor = AndroidColor.GRAY
                    axisMinimum = 0f
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float) = "%.0f N/kg".format(value)
                    }
                }
                axisRight.isEnabled = false
                setExtraOffsets(8f, 8f, 8f, 8f)
            }
        },
        update = { chart ->
            val pointColor = ChartPrimary.toArgb()
            val points = LineDataSet(
                profile.points.map { Entry(it.meanVelocity, it.relativeForce, it.loadKg) }.sortedBy { it.x },
                pointsLabel
            ).apply {
                color = AndroidColor.TRANSPARENT
                setCircleColor(pointColor)
                circleRadius = 4f
                setDrawValues(true)
                valueTextColor = legendColor
                valueFormatter = object : ValueFormatter() {
                    override fun getPointLabel(entry: Entry) = "%.1f kg".format(entry.data as Float)
                }
            }
            val lineColor = FvLineColor.toArgb()
            val line = LineDataSet(listOf(Entry(0f, profile.f0), Entry(profile.v0, 0f)), lineLabel).apply {
                color = lineColor
                lineWidth = 2f
                enableDashedLine(12f, 8f, 0f)
                setDrawCircles(false)
                setDrawValues(false)
            }
            chart.data = LineData(line, points)
            chart.invalidate()
        }
    )
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
