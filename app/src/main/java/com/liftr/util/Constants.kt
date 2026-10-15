package com.liftr.util

import com.liftr.domain.model.PoseKeypoint

object Constants {
    // Logging Tags
    const val TAG_CAMERA = "Liftr_Camera"
    const val TAG_POSE = "Liftr_Pose"
    const val TAG_WORKOUT = "Liftr_Workout"
    const val TAG_VELOCITY = "Liftr_Velocity"
    const val TAG_CALIBRATION = "Liftr_Calibration"
    const val TAG_MOVEMENT = "Liftr_Movement"
    const val TAG_APP = "Liftr_App"

    // Camera Configuration - Workout (Fast tracking)
    const val CAMERA_TARGET_WIDTH = 640
    const val CAMERA_TARGET_HEIGHT = 480
    const val TARGET_FPS = 30

    // High-speed (120/240 fps) recording for fast movements. Off: it froze the
    // camera on test devices and darkens the image (shutter capped at 1/fps),
    // which hurts pose quality more than the extra frames help the flight fit.
    const val HIGH_SPEED_RECORDING = false

    // Camera Configuration - Calibration (High accuracy)
    const val CAMERA_CALIBRATION_WIDTH = 1280
    const val CAMERA_CALIBRATION_HEIGHT = 960
    // Note: Will fall back to best available resolution if device doesn't support this

    // Pose Detection
    const val POSE_MIN_DETECTION_CONFIDENCE = 0.3f
    const val POSE_MIN_TRACKING_CONFIDENCE = 0.3f
    const val POSE_MIN_PRESENCE_CONFIDENCE = 0.3f

    // Landmark visibility threshold — minimum confidence to consider a landmark valid.
    const val LANDMARK_VISIBILITY_THRESHOLD = PoseKeypoint.VISIBILITY_THRESHOLD

    // Velocity Calculation
    const val VELOCITY_HISTORY_SIZE = 5 // Buffer size for 2nd order backward difference (requires minimum 3 points)
    const val KALMAN_PROCESS_NOISE = 0.01f
    const val KALMAN_MEASUREMENT_NOISE = 0.1f

    // OneEuro landmark smoothing — tames jitter, especially during self-occlusion
    // (e.g. bottom of a squat where knees/hips overlap). Operates on normalized
    // MediaPipe coordinates (0..1). Tuning: lower minCutoff = smoother but laggier;
    // higher beta = more responsive during fast motion. High beta is important so
    // the filter does not clip the true descent peak and break rep counting.
    const val ONE_EURO_MIN_CUTOFF = 1.5f
    const val ONE_EURO_BETA = 0.4f
    const val ONE_EURO_DCUTOFF = 1.0f

    // Movement Detection
    const val MOVEMENT_VELOCITY_THRESHOLD = 0.1f // m/s
    const val TRANSITION_VELOCITY_THRESHOLD = 0.05f // m/s
    const val IDLE_VELOCITY_THRESHOLD = 0.02f // m/s

    // Physics
    const val GRAVITY = 9.81f // m/s²

    // DataStore Keys
    const val KEY_USER_HEIGHT = "user_height"
    const val KEY_SHOULDER_WIDTH = "shoulder_width"
    const val KEY_PIXELS_PER_METER = "pixels_per_meter"
    const val KEY_IS_CALIBRATED = "is_calibrated"

    // MediaPipe Models
    const val POSE_MODEL_LITE = "pose_landmarker_lite.task"    // Fast, for real-time workouts
    const val POSE_MODEL_FULL = "pose_landmarker_full.task"    // Accurate, for calibration
    const val POSE_MODEL_HEAVY = "pose_landmarker_heavy.task"  // Most accurate, for calibration

    // Model selection for different use cases
    const val POSE_MODEL_WORKOUT = POSE_MODEL_FULL    // Balanced accuracy/speed; robust under self-occlusion
    const val POSE_MODEL_CALIBRATION = POSE_MODEL_HEAVY // Precision for measurements
}
