package com.lifthelper.ui.components.charts

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.WeeklyVolume
import com.lifthelper.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VolumeTonnageChart(
    weeklyVolumes: List<WeeklyVolume>,
    modifier: Modifier = Modifier
) {
    if (weeklyVolumes.isEmpty()) return

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp),
        factory = { context ->
            BarChart(context).apply {
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
                }

                axisRight.isEnabled = false
                setExtraOffsets(8f, 8f, 8f, 8f)
            }
        },
        update = { chart ->
            val entries = weeklyVolumes.mapIndexed { index, volume ->
                BarEntry(index.toFloat(), volume.tonnage.value)
            }

            val barColor = weeklyVolumes.firstOrNull()?.exerciseType?.let {
                getExerciseColor(it)
            } ?: ChartPrimary.toArgb()

            val dataSet = BarDataSet(entries, "Volume").apply {
                color = barColor
                setDrawValues(false)
            }

            chart.xAxis.valueFormatter = object : ValueFormatter() {
                private val dateFormat = SimpleDateFormat("MMM dd", Locale.getDefault())
                override fun getFormattedValue(value: Float): String {
                    val index = value.toInt()
                    if (index < 0 || index >= weeklyVolumes.size) return ""
                    return dateFormat.format(Date(weeklyVolumes[index].weekStartTimestamp))
                }
            }

            chart.data = BarData(dataSet).apply {
                barWidth = 0.7f
            }
            chart.invalidate()
        }
    )
}

private fun getExerciseColor(exerciseType: ExerciseType): Int = when (exerciseType) {
    ExerciseType.BACK_SQUAT -> ChartBackSquat.toArgb()
    ExerciseType.FRONT_SQUAT -> ChartFrontSquat.toArgb()
    ExerciseType.BENCH_PRESS -> ChartBenchPress.toArgb()
    ExerciseType.DEADLIFT -> ChartDeadlift.toArgb()
    ExerciseType.CLEAN -> ChartClean.toArgb()
    ExerciseType.SNATCH -> ChartSnatch.toArgb()
    else -> ChartPrimary.toArgb()
}
