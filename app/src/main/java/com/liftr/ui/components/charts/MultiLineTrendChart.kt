package com.liftr.ui.components.charts

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter

/** One line of a [MultiLineTrendChart]: a value per x index, null where it wasn't measured. */
data class ChartSeries(val label: String, val color: Color, val values: List<Float?>)

/**
 * Several measurements over the same sequence of events (e.g. each jump), one
 * line each, with a legend. [xLabels] names each x index.
 */
@Composable
fun MultiLineTrendChart(
    series: List<ChartSeries>,
    xLabels: List<String>,
    unit: String,
    modifier: Modifier = Modifier,
    decimals: Int = 0
) {
    if (series.all { s -> s.values.all { it == null } }) return
    val legendColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp),
        factory = { context ->
            LineChart(context).apply {
                description.isEnabled = false
                setTouchEnabled(true)
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
                    granularity = 1f
                    textColor = AndroidColor.GRAY
                }
                axisLeft.apply {
                    setDrawGridLines(true)
                    gridColor = AndroidColor.parseColor("#20000000")
                    textColor = AndroidColor.GRAY
                    axisMinimum = 0f
                }
                axisRight.isEnabled = false
                setExtraOffsets(8f, 8f, 8f, 8f)
            }
        },
        update = { chart ->
            val dataSets = series.mapNotNull { s ->
                val entries = s.values.mapIndexedNotNull { i, v -> v?.let { Entry(i.toFloat(), it) } }
                if (entries.isEmpty()) return@mapNotNull null
                val color = s.color.toArgb()
                LineDataSet(entries, s.label).apply {
                    this.color = color
                    setCircleColor(color)
                    circleRadius = 3f
                    lineWidth = 2f
                    setDrawValues(entries.size <= 6)
                    valueTextColor = legendColor
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float) = "%.0f".format(value)
                    }
                }
            }
            chart.xAxis.valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String =
                    xLabels.getOrNull(value.toInt()) ?: ""
            }
            chart.axisLeft.valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float) = "%.${decimals}f %s".format(value, unit)
            }
            chart.data = LineData(dataSets)
            chart.invalidate()
        }
    )
}
