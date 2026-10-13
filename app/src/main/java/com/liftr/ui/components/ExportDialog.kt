package com.liftr.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.liftr.R
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.ExportConfig
import com.liftr.domain.model.ExportFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportDialog(
    onDismiss: () -> Unit,
    onExport: (ExportConfig) -> Unit
) {
    var format by remember { mutableStateOf(ExportFormat.CSV_SETS) }
    var range by remember { mutableStateOf(ExportRange.ALL) }
    var exerciseFilter by remember { mutableStateOf<ExerciseType?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.export_format_label),
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(4.dp))
                FormatChoice(ExportFormat.CSV_SETS, stringResource(R.string.export_format_csv_sets), format) { format = it }
                FormatChoice(ExportFormat.CSV_REPS, stringResource(R.string.export_format_csv_reps), format) { format = it }
                FormatChoice(ExportFormat.JSON, stringResource(R.string.export_format_json), format) { format = it }

                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.export_range_label),
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(4.dp))
                ExportRangeDropdown(current = range, onChange = { range = it })

                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.export_exercise_label),
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(4.dp))
                ExerciseFilterDropdown(current = exerciseFilter, onChange = { exerciseFilter = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val now = System.currentTimeMillis()
                val startDate = range.windowMillis?.let { now - it }
                onExport(
                    ExportConfig(
                        format = format,
                        startDate = startDate,
                        endDate = null,
                        exerciseType = exerciseFilter
                    )
                )
            }) {
                Text(stringResource(R.string.export_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.export_cancel))
            }
        }
    )
}

@Composable
private fun FormatChoice(
    option: ExportFormat,
    label: String,
    selected: ExportFormat,
    onSelect: (ExportFormat) -> Unit
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        RadioButton(selected = option == selected, onClick = { onSelect(option) })
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportRangeDropdown(
    current: ExportRange,
    onChange: (ExportRange) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (current) {
        ExportRange.ALL -> stringResource(R.string.export_range_all)
        ExportRange.LAST_MONTH -> stringResource(R.string.export_range_last_month)
        ExportRange.LAST_3_MONTHS -> stringResource(R.string.export_range_last_3_months)
        ExportRange.LAST_YEAR -> stringResource(R.string.export_range_last_year)
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            ExportRange.entries.forEach { option ->
                val optionLabel = when (option) {
                    ExportRange.ALL -> stringResource(R.string.export_range_all)
                    ExportRange.LAST_MONTH -> stringResource(R.string.export_range_last_month)
                    ExportRange.LAST_3_MONTHS -> stringResource(R.string.export_range_last_3_months)
                    ExportRange.LAST_YEAR -> stringResource(R.string.export_range_last_year)
                }
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = {
                        onChange(option)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExerciseFilterDropdown(
    current: ExerciseType?,
    onChange: (ExerciseType?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val allLabel = stringResource(R.string.export_exercise_all)
    val label = current?.getDisplayName() ?: allLabel

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(allLabel) },
                onClick = {
                    onChange(null)
                    expanded = false
                },
                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
            )
            ExerciseType.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.getDisplayName()) },
                    onClick = {
                        onChange(option)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

private enum class ExportRange(val windowMillis: Long?) {
    ALL(null),
    LAST_MONTH(30L * 24 * 60 * 60 * 1000),
    LAST_3_MONTHS(90L * 24 * 60 * 60 * 1000),
    LAST_YEAR(365L * 24 * 60 * 60 * 1000)
}
