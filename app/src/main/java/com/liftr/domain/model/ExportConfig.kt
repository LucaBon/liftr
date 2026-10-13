package com.liftr.domain.model

enum class ExportFormat(val extension: String, val mimeType: String) {
    CSV_SETS(extension = "csv", mimeType = "text/csv"),
    CSV_REPS(extension = "csv", mimeType = "text/csv"),
    JSON(extension = "json", mimeType = "application/json")
}

data class ExportConfig(
    val format: ExportFormat,
    val startDate: Long? = null,
    val endDate: Long? = null,
    val exerciseType: ExerciseType? = null
)

data class ExportResult(
    val content: String,
    val fileName: String,
    val mimeType: String,
    val workoutCount: Int,
    val setCount: Int
)
