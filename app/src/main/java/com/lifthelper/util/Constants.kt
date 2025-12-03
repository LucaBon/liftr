package com.lifthelper.util

object Constants {
    // Logging Tags
    const val TAG_CAMERA = "LiftHelper_Camera"
    const val TAG_POSE = "LiftHelper_Pose"
    const val TAG_WORKOUT = "LiftHelper_Workout"
    const val TAG_VELOCITY = "LiftHelper_Velocity"
    const val TAG_CALIBRATION = "LiftHelper_Calibration"
    const val TAG_MOVEMENT = "LiftHelper_Movement"
    const val TAG_APP = "LiftHelper_App"

    // Camera Configuration
    const val CAMERA_TARGET_WIDTH = 640
    const val CAMERA_TARGET_HEIGHT = 480
    const val TARGET_FPS = 30

    // Pose Detection
    const val POSE_MIN_DETECTION_CONFIDENCE = 0.3f
    const val POSE_MIN_TRACKING_CONFIDENCE = 0.3f
    const val POSE_MIN_PRESENCE_CONFIDENCE = 0.3f

    // Velocity Calculation
    const val VELOCITY_HISTORY_SIZE = 5 // Buffer size for 2nd order backward difference (requires minimum 3 points)
    const val KALMAN_PROCESS_NOISE = 0.01f
    const val KALMAN_MEASUREMENT_NOISE = 0.1f

    // Movement Detection
    const val MOVEMENT_VELOCITY_THRESHOLD = 0.1f // m/s
    const val TRANSITION_VELOCITY_THRESHOLD = 0.05f // m/s
    const val IDLE_VELOCITY_THRESHOLD = 0.02f // m/s

    // Jump Detection
    const val TAKEOFF_VELOCITY_THRESHOLD = 0.02f
    const val MIN_FLIGHT_FRAMES = 5
    const val STABILIZATION_WINDOW = 10

    // Physics
    const val GRAVITY = 9.81f // m/s²

    // DataStore Keys
    const val KEY_USER_HEIGHT = "user_height"
    const val KEY_SHOULDER_WIDTH = "shoulder_width"
    const val KEY_PIXELS_PER_METER = "pixels_per_meter"
    const val KEY_IS_CALIBRATED = "is_calibrated"

    // MediaPipe Model
    const val POSE_MODEL_PATH = "pose_landmarker_lite.task"
}
