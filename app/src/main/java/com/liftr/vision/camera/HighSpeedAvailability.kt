package com.liftr.vision.camera

import com.liftr.domain.model.ExerciseType

/**
 * True when a fast movement will be recorded in-app below 120 fps, so the
 * user should be pointed at the phone's slow-motion mode instead.
 * [recordingFps] is the high-speed session's rate, null when the camera
 * bound a normal session (no profiles, flag off, or the rep counter is on).
 */
fun shouldWarnHighSpeedUnavailable(
    exercise: ExerciseType,
    recordingFps: Int?,
    minHighSpeedFps: Int = 120
): Boolean = exercise.isFastMovement() && (recordingFps ?: 0) < minHighSpeedFps
