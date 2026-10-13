package com.liftr.domain.model

enum class ExerciseType {
    FRONT_SQUAT,
    BACK_SQUAT,
    BENCH_PRESS,
    DEADLIFT,
    CLEAN,
    SNATCH,
    VERTICAL_JUMP,
    LOADED_JUMP;

    fun isJumpExercise(): Boolean = this == VERTICAL_JUMP || this == LOADED_JUMP

    /** False for bodyweight exercises, which are performed without a weight. */
    fun hasExternalLoad(): Boolean = this != VERTICAL_JUMP

    fun getDisplayName(): String = when (this) {
        FRONT_SQUAT -> "Front Squat"
        BACK_SQUAT -> "Back Squat"
        BENCH_PRESS -> "Bench Press"
        DEADLIFT -> "Deadlift"
        CLEAN -> "Clean"
        SNATCH -> "Snatch"
        VERTICAL_JUMP -> "Vertical Jump"
        LOADED_JUMP -> "Loaded Jump"
    }
}
