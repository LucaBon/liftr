package com.lifthelper.domain.model

data class RestTimerState(
    val totalSeconds: Int,
    val remainingSeconds: Int,
    val isRunning: Boolean = true,
    val exerciseType: ExerciseType? = null
) {
    val progress: Float
        get() = if (totalSeconds > 0) 1f - (remainingSeconds.toFloat() / totalSeconds) else 1f

    val isComplete: Boolean
        get() = remainingSeconds <= 0

    val displayMinutes: Int get() = remainingSeconds / 60
    val displaySeconds: Int get() = remainingSeconds % 60
}
