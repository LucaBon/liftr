package com.liftr.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.liftr.R
import com.liftr.domain.exercise.RepQualityScorer
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.ScoreBreakdown
import com.liftr.ui.common.depthPartLabelRes
import com.liftr.ui.theme.Spacing
import kotlin.math.roundToInt

/** Average points in each scoring component, out of that component's maximum. */
@Composable
fun ScoreBreakdownRow(
    breakdown: ScoreBreakdown,
    exerciseType: ExerciseType,
    modifier: Modifier = Modifier
) {
    val parts = listOf(
        Triple(exerciseType.depthPartLabelRes, breakdown.depth, RepQualityScorer.MAX_DEPTH),
        Triple(R.string.set_summary_bar_path_header, breakdown.barPath, RepQualityScorer.MAX_BAR_PATH),
        Triple(R.string.set_summary_tempo_header, breakdown.tempo, RepQualityScorer.MAX_TEMPO),
        Triple(R.string.set_summary_form_header, breakdown.form, RepQualityScorer.MAX_FORM)
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        parts.forEach { (labelRes, avg, max) ->
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(labelRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${avg.roundToInt()}/$max",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                LinearProgressIndicator(
                    progress = { avg / max },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}
