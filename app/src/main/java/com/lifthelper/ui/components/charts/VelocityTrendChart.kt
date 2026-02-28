package com.lifthelper.ui.components.charts

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.lifthelper.domain.model.TrendDataPoint
import com.lifthelper.ui.theme.ChartPrimary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VelocityTrendChart(
    dataPoints: List<TrendDataPoint>,
    modifier: Modifier = Modifier
) {
    if (dataPoints.isEmpty()) return

    val lineColor = ChartPrimary.toArgb()

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp),
        factory = { context ->
            LineChart(context).apply {
                description.isEnabled = false
                setTouchEnabled(true)
                setDrawGridBackground(false)
                legend.isEnabled = false
                setNoDataText("")

                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM
                    setDrawGridLines(false)
                    granularity = 1f
                    textColor = AndroidColor.GRAY
                    valueFormatter = DateAxisFormatter()
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
            val entries = dataPoints.mapIndexed { index, point ->
                Entry(index.toFloat(), point.value).apply {
                    data = point.timestamp
                }
            }

            val dataSet = LineDataSet(entries, "Velocity").apply {
                color = lineColor
                setCircleColor(lineColor)
                circleRadius = 3f
                lineWidth = 2f
                mode = LineDataSet.Mode.CUBIC_BEZIER
                setDrawValues(false)
                setDrawFilled(true)
                fillColor = lineColor
                fillAlpha = 30
            }

            chart.xAxis.valueFormatter = object : ValueFormatter() {
                private val dateFormat = SimpleDateFormat("MMM dd", Locale.getDefault())
                override fun getFormattedValue(value: Float): String {
                    val index = value.toInt()
                    if (index < 0 || index >= dataPoints.size) return ""
                    return dateFormat.format(Date(dataPoints[index].timestamp))
                }
            }

            chart.data = LineData(dataSet)
            chart.invalidate()
        }
    )
}

private class DateAxisFormatter : ValueFormatter() {
    private val dateFormat = SimpleDateFormat("MMM dd", Locale.getDefault())
    override fun getFormattedValue(value: Float): String {
        return dateFormat.format(Date(value.toLong()))
    }
}
