package com.liftr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.liftr.R
import com.liftr.ui.components.common.pressScale
import com.liftr.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEntryForm(
    exerciseNames: List<String>,
    onLogSet: (exerciseName: String, weight: Float, reps: Int, notes: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var exerciseName by remember { mutableStateOf("") }
    var weightText by remember { mutableStateOf("") }
    var repsText by remember { mutableStateOf("") }
    var notesText by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    val filteredNames = remember(exerciseName, exerciseNames) {
        if (exerciseName.isBlank()) exerciseNames
        else exerciseNames.filter { it.contains(exerciseName, ignoreCase = true) }
    }

    val isValid = exerciseName.isNotBlank() &&
        (weightText.toFloatOrNull() ?: -1f) >= 0f &&
        (repsText.toIntOrNull() ?: 0) > 0

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                RoundedCornerShape(16.dp)
            )
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(
            text = stringResource(R.string.manual_entry_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Exercise name with autocomplete
        ExposedDropdownMenuBox(
            expanded = expanded && filteredNames.isNotEmpty(),
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = exerciseName,
                onValueChange = {
                    exerciseName = it
                    expanded = true
                },
                label = { Text(stringResource(R.string.manual_entry_exercise_name)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
            )

            if (filteredNames.isNotEmpty()) {
                ExposedDropdownMenu(
                    expanded = expanded && filteredNames.isNotEmpty(),
                    onDismissRequest = { expanded = false }
                ) {
                    filteredNames.take(5).forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                exerciseName = name
                                expanded = false
                            }
                        )
                    }
                }
            }
        }

        // Weight and Reps row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            OutlinedTextField(
                value = weightText,
                onValueChange = { weightText = it },
                label = { Text(stringResource(R.string.weight_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = repsText,
                onValueChange = { repsText = it },
                label = { Text(stringResource(R.string.manual_entry_reps)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        // Notes
        OutlinedTextField(
            value = notesText,
            onValueChange = { notesText = it },
            label = { Text(stringResource(R.string.manual_entry_notes)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Log Set button
        val logInteractionSource = remember { MutableInteractionSource() }
        Button(
            onClick = {
                val weight = weightText.toFloatOrNull() ?: return@Button
                val reps = repsText.toIntOrNull() ?: return@Button
                onLogSet(exerciseName.trim(), weight, reps, notesText.trim())
                // Reset form but keep exercise name for consecutive sets
                weightText = ""
                repsText = ""
                notesText = ""
            },
            enabled = isValid,
            modifier = Modifier
                .align(Alignment.End)
                .pressScale(logInteractionSource),
            interactionSource = logInteractionSource
        ) {
            Text(stringResource(R.string.manual_entry_log_set))
        }
    }
}
