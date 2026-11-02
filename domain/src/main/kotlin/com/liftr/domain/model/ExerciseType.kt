package com.liftr.domain.model

enum class ExerciseType {
    FRONT_SQUAT,
    BACK_SQUAT,
    BENCH_PRESS,
    DEADLIFT,
    CLEAN,
    SNATCH,
    VERTICAL_JUMP,
    LOADED_JUMP,
    APPROACH_JUMP;

    fun isJumpExercise(): Boolean = this == VERTICAL_JUMP || this == LOADED_JUMP || this == APPROACH_JUMP

    /**
     * Exercises timed from very fast movement (jump flight, the pull of a
     * clean or snatch), which benefit from the highest recording frame rate.
     */
    fun isFastMovement(): Boolean = isJumpExercise() || this == CLEAN || this == SNATCH

    /** False for bodyweight exercises, which are performed without a weight. */
    fun hasExternalLoad(): Boolean = this != VERTICAL_JUMP && this != APPROACH_JUMP

    /**
     * A rep that starts with the lift (the pull from the floor) rather than
     * with lowering, so it is complete at lockout and its lowering comes after.
     */
    fun startsWithConcentric(): Boolean = this == DEADLIFT

    /**
     * Whether a side-on camera can be told from a front one by the athlete's
     * standing shoulder width. Every exercise is filmed from the side, but a
     * lifter lying on a bench never stands, so its view can't be judged.
     */
    fun canJudgeCameraView(): Boolean = this != BENCH_PRESS

    /**
     * Phases in which the athlete stands upright, where shoulder width shows
     * the camera view. A deadlift, clean or snatch waits bent over the bar, so
     * only its top counts; a jump's top is in flight, so only its rest counts.
     */
    fun isStandingPhase(phase: MovementPhase): Boolean = when {
        isJumpExercise() -> phase == MovementPhase.IDLE
        this == DEADLIFT || this == CLEAN || this == SNATCH -> phase == MovementPhase.TOP
        else -> phase == MovementPhase.IDLE || phase == MovementPhase.TOP
    }

    fun getDisplayName(): String = when (this) {
        FRONT_SQUAT -> "Front Squat"
        BACK_SQUAT -> "Back Squat"
        BENCH_PRESS -> "Bench Press"
        DEADLIFT -> "Deadlift"
        CLEAN -> "Clean"
        SNATCH -> "Snatch"
        VERTICAL_JUMP -> "Vertical Jump"
        LOADED_JUMP -> "Loaded Jump"
        APPROACH_JUMP -> "Approach Jump"
    }
}
