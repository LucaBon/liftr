package com.liftr.domain.model

data class PersonalRecord(
    val type: PersonalRecordType,
    val exerciseType: ExerciseType,
    val value: String,
    val numericValue: Float,
    val date: Long,
    val workoutId: Long
)

enum class PersonalRecordType(val displayName: String) {
    HEAVIEST_SET("Heaviest Set"),
    FASTEST_REP("Fastest Rep"),
    BEST_QUALITY("Best Quality Score"),
    ESTIMATED_1RM("Estimated 1RM"),
    HIGHEST_VOLUME_SET("Highest Volume Set"),
    MOST_TONNAGE_SESSION("Most Tonnage Session"),
    BEST_JUMP_HEIGHT("Best Jump")
}
