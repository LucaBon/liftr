package com.liftr.ui.components.charts

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
import com.liftr.domain.model.TrendDataPoint
import com.liftr.ui.theme.ChartBackSquat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun QualityScoreChart(
    dataPoints: List<TrendDataPoint>,
    modifier: Modifier = Modifier
) {
    if (dataPoints.isEmpty()) return

    val lineColor = ChartBackSquat.toArgb()

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
                }

                axisLeft.apply {
                    setDrawGridLines(true)
                    gridColor = AndroidColor.parseColor("#20000000")
                    textColor = AndroidColor.GRAY
                    axisMinimum = 0f
                    axisMaximum = 100f
                }

                axisRight.isEnabled = false
                setExtraOffsets(8f, 8f, 8f, 8f)
            }
        },
        update = { chart ->
            val entries = dataPoints.mapIndexed { index, point ->
                Entry(index.toFloat(), point.value)
            }

            val dataSet = LineDataSet(entries, "Quality Score").apply {
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
