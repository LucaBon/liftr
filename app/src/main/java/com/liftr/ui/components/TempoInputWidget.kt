package com.liftr.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.liftr.R
import com.liftr.domain.model.TempoPrescription

/**
 * Widget for inputting tempo prescription in E-P1-C-P2 notation.
 * Shows 4 number inputs and preset buttons.
 */
@Composable
fun TempoInputWidget(
    prescription: TempoPrescription?,
    onPrescriptionChanged: (TempoPrescription) -> Unit,
    modifier: Modifier = Modifier
) {
    val current = prescription ?: TempoPrescription.HYPERTROPHY

    var eccText by remember(current.eccentricSeconds) {
        mutableStateOf(current.eccentricSeconds.toInt().toString())
    }
    var pauseBottomText by remember(current.pauseBottomSeconds) {
        mutableStateOf(current.pauseBottomSeconds.toInt().toString())
    }
    var concText by remember(current.concentricSeconds) {
        mutableStateOf(
            if (current.isExplosiveConcentric) "X" else current.concentricSeconds.toInt().toString()
        )
    }
    var pauseTopText by remember(current.pauseTopSeconds) {
        mutableStateOf(current.pauseTopSeconds.toInt().toString())
    }

    fun buildPrescription() {
        val ecc = eccText.toFloatOrNull() ?: return
        val p1 = pauseBottomText.toFloatOrNull() ?: return
        val con = if (concText.uppercase() == "X") 0f else concText.toFloatOrNull() ?: return
        val p2 = pauseTopText.toFloatOrNull() ?: return
        onPrescriptionChanged(TempoPrescription(ecc, p1, con, p2))
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 4 number inputs in a row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TempoField(
                value = eccText,
                label = stringResource(R.string.tempo_eccentric_label),
                onValueChange = { eccText = it; buildPrescription() },
                modifier = Modifier.weight(1f)
            )
            Text("-", style = MaterialTheme.typography.titleMedium)
            TempoField(
                value = pauseBottomText,
                label = stringResource(R.string.tempo_pause_bottom_label),
                onValueChange = { pauseBottomText = it; buildPrescription() },
                modifier = Modifier.weight(1f)
            )
            Text("-", style = MaterialTheme.typography.titleMedium)
            TempoField(
                value = concText,
                label = stringResource(R.string.tempo_concentric_label),
                onValueChange = { concText = it; buildPrescription() },
                modifier = Modifier.weight(1f),
                allowX = true
            )
            Text("-", style = MaterialTheme.typography.titleMedium)
            TempoField(
                value = pauseTopText,
                label = stringResource(R.string.tempo_pause_top_label),
                onValueChange = { pauseTopText = it; buildPrescription() },
                modifier = Modifier.weight(1f)
            )
        }

        // Preset buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TempoPresetChip(
                label = stringResource(R.string.tempo_preset_hypertrophy),
                selected = current == TempoPrescription.HYPERTROPHY,
                onClick = { onPrescriptionChanged(TempoPrescription.HYPERTROPHY) },
                modifier = Modifier.weight(1f)
            )
            TempoPresetChip(
                label = stringResource(R.string.tempo_preset_strength),
                selected = current == TempoPrescription.STRENGTH,
                onClick = { onPrescriptionChanged(TempoPrescription.STRENGTH) },
                modifier = Modifier.weight(1f)
            )
            TempoPresetChip(
                label = stringResource(R.string.tempo_preset_slow),
                selected = current == TempoPrescription.SLOW,
                onClick = { onPrescriptionChanged(TempoPrescription.SLOW) },
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TempoPresetChip(
                label = stringResource(R.string.tempo_preset_explosive),
                selected = current == TempoPrescription.EXPLOSIVE,
                onClick = { onPrescriptionChanged(TempoPrescription.EXPLOSIVE) },
                modifier = Modifier.weight(1f)
            )
            TempoPresetChip(
                label = stringResource(R.string.tempo_preset_isometric),
                selected = current == TempoPrescription.ISOMETRIC,
                onClick = { onPrescriptionChanged(TempoPrescription.ISOMETRIC) },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun TempoField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    allowX: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = { text ->
            val filtered = if (allowX) {
                text.filter { it.isDigit() || it.uppercase() == "X" }.take(2)
            } else {
                text.filter { it.isDigit() }.take(2)
            }
            onValueChange(filtered)
        },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center),
        modifier = modifier,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        )
    )
}

@Composable
private fun TempoPresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        },
        modifier = modifier,
        shape = RoundedCornerShape(8.dp)
    )
}
