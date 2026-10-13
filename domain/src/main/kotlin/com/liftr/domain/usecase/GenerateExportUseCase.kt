package com.liftr.domain.usecase

import com.liftr.domain.model.ExportConfig
import com.liftr.domain.model.ExportFormat
import com.liftr.domain.model.ExportResult
import com.liftr.domain.model.Rep
import com.liftr.domain.model.Set
import com.liftr.domain.model.Workout
import com.liftr.domain.repository.WorkoutRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * Generates a CSV or JSON export of workout history according to [ExportConfig].
 *
 * Pulls a snapshot of the reactive workout list, filters it in-memory, and
 * returns an [ExportResult] with the rendered content and a suggested filename.
 * Actual file writing and sharing is handled at the Android layer.
 */
class GenerateExportUseCase @Inject constructor(
    private val workoutRepository: WorkoutRepository
) : UseCase<ExportConfig, ExportResult>() {

    override suspend fun invoke(params: ExportConfig): ExportResult {
        val workouts = workoutRepository.getWorkouts().first()
            .filter { it.matches(params) }
            .map { it.filterSets(params) }
            .filter { it.sets.isNotEmpty() }

        val content = when (params.format) {
            ExportFormat.CSV_SETS -> generateCsvSets(workouts)
            ExportFormat.CSV_REPS -> generateCsvReps(workouts)
            ExportFormat.JSON -> generateJson(workouts)
        }

        val timestamp = FILE_TIMESTAMP_FORMAT.format(Date())
        return ExportResult(
            content = content,
            fileName = "liftr_export_$timestamp.${params.format.extension}",
            mimeType = params.format.mimeType,
            workoutCount = workouts.size,
            setCount = workouts.sumOf { it.sets.size }
        )
    }

    private fun Workout.matches(config: ExportConfig): Boolean {
        val time = date.time
        if (config.startDate != null && time < config.startDate) return false
        if (config.endDate != null && time > config.endDate) return false
        return true
    }

    private fun Workout.filterSets(config: ExportConfig): Workout {
        if (config.exerciseType == null) return this
        return copy(sets = sets.filter { it.exerciseType == config.exerciseType })
    }

    private fun generateCsvSets(workouts: List<Workout>): String {
        val sb = StringBuilder()
        sb.appendLine(
            "date,exercise,weight_kg,target_reps,completed_reps," +
                "avg_velocity_ms,peak_velocity_ms,quality_score,is_manual,notes"
        )
        for (workout in workouts) {
            val date = DATE_FORMAT.format(workout.date)
            for (set in workout.sets) {
                sb.append(date).append(',')
                    .append(csvField(set.displayName)).append(',')
                    .append(formatFloat(set.weight.value)).append(',')
                    .append(set.targetReps.value).append(',')
                    .append(set.completedReps.value).append(',')
                    .append(formatFloat(set.avgVelocity.value)).append(',')
                    .append(formatFloat(set.peakVelocity.value)).append(',')
                    .append(averageQualityScore(set)).append(',')
                    .append(set.isManual).append(',')
                    .append(csvField(set.notes))
                    .append('\n')
            }
        }
        return sb.toString()
    }

    private fun generateCsvReps(workouts: List<Workout>): String {
        val sb = StringBuilder()
        sb.appendLine(
            "date,exercise,weight_kg,set_number,rep_number,avg_velocity_ms," +
                "peak_velocity_ms,eccentric_s,concentric_s,rom_m,quality_score"
        )
        var setNumber = 0
        for (workout in workouts) {
            val date = DATE_FORMAT.format(workout.date)
            for (set in workout.sets) {
                setNumber++
                for (rep in set.reps) {
                    sb.append(date).append(',')
                        .append(csvField(set.displayName)).append(',')
                        .append(formatFloat(set.weight.value)).append(',')
                        .append(setNumber).append(',')
                        .append(rep.repNumber).append(',')
                        .append(formatFloat(rep.avgVelocity.value)).append(',')
                        .append(formatFloat(rep.peakVelocity.value)).append(',')
                        .append(formatFloat(rep.eccentricDuration)).append(',')
                        .append(formatFloat(rep.concentricDuration)).append(',')
                        .append(formatFloat(rep.rangeOfMotion.value)).append(',')
                        .append(rep.qualityScore)
                        .append('\n')
                }
            }
        }
        return sb.toString()
    }

    private fun generateJson(workouts: List<Workout>): String {
        val root: JsonObject = buildJsonObject {
            put("exportDate", DATE_FORMAT.format(Date()))
            put("workoutCount", workouts.size)
            putJsonArray("workouts") {
                workouts.forEach { workout ->
                    addJsonObject {
                        put("id", workout.id)
                        put("date", DATE_FORMAT.format(workout.date))
                        put("timestamp", workout.date.time)
                        put("durationMs", workout.duration)
                        if (workout.notes.isNotEmpty()) put("notes", workout.notes)
                        putJsonArray("sets") {
                            workout.sets.forEach { set -> add(set.toJson()) }
                        }
                    }
                }
            }
        }
        return root.toString()
    }

    private fun Set.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("exercise", exerciseType.name)
        put("displayName", displayName)
        put("weightKg", weight.value)
        put("targetReps", targetReps.value)
        put("completedReps", completedReps.value)
        put("avgVelocityMs", avgVelocity.value)
        put("peakVelocityMs", peakVelocity.value)
        put("timestamp", timestamp)
        put("isManual", isManual)
        if (notes.isNotEmpty()) put("notes", notes)
        putJsonArray("reps") {
            reps.forEach { rep -> add(rep.toJson()) }
        }
    }

    private fun Rep.toJson(): JsonObject = buildJsonObject {
        put("repNumber", repNumber)
        put("avgVelocityMs", avgVelocity.value)
        put("peakVelocityMs", peakVelocity.value)
        put("eccentricS", eccentricDuration)
        put("concentricS", concentricDuration)
        put("romM", rangeOfMotion.value)
        put("qualityScore", qualityScore)
        put("timestamp", timestamp)
    }

    private fun averageQualityScore(set: Set): String {
        val scored = set.reps.filter { it.qualityScore > 0 }
        if (scored.isEmpty()) return ""
        return (scored.sumOf { it.qualityScore } / scored.size).toString()
    }

    private fun formatFloat(value: Float): String = "%.4f".format(Locale.US, value)

    private fun csvField(raw: String): String {
        if (raw.isEmpty()) return ""

        // Spreadsheets evaluate a cell starting with =, +, -, @ (or a leading
        // tab/CR) as a formula. Exercise names and notes are free text the user
        // types, and exports are made to be shared, so prefix such values with a
        // single quote to force them to be read as literal text.
        val isFormula = raw.first() in FORMULA_TRIGGERS
        val body = if (isFormula) "'$raw" else raw

        val needsQuoting = isFormula ||
            body.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return body

        val escaped = body.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    companion object {
        /** Leading characters that make a spreadsheet treat a cell as a formula. */
        private val FORMULA_TRIGGERS = charArrayOf('=', '+', '-', '@', '\t', '\r')

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        private val FILE_TIMESTAMP_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}
