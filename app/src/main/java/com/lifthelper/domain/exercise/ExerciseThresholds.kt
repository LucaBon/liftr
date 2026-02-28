package com.lifthelper.domain.exercise

import com.lifthelper.domain.value.Meters
import com.lifthelper.domain.value.MetersPerSecond

/**
 * Configuration object containing threshold values for exercise detection.
 *
 * These values control phase transitions, depth validation, and movement detection.
 * Centralizing them here makes it easier to tune behavior and A/B test different values.
 */
object ExerciseThresholds {

    /**
     * Thresholds for back squat exercise.
     */
    object BackSquat {
        /** Minimum descent depth to count as a rep (30cm) */
        val MIN_DESCENT_DEPTH = Meters(0.30f)

        /** Return tolerance for reaching top position (15cm) */
        val RETURN_TOLERANCE = Meters(0.15f)

        /** Minimum velocity to be considered moving (0.1 m/s) */
        val MIN_VELOCITY = MetersPerSecond(0.1f)

        /** Tolerance for depth checking (2cm) */
        const val DEPTH_TOLERANCE = 0.02f

        /** Movement threshold to transition from IDLE to ECCENTRIC (5cm) */
        const val IDLE_TO_ECCENTRIC_THRESHOLD = 0.05f

        /** Movement threshold to transition from BOTTOM to CONCENTRIC (3cm) */
        const val BOTTOM_TO_CONCENTRIC_THRESHOLD = 0.03f

        /** Stabilization threshold at top position (2cm) */
        const val TOP_STABILIZATION_THRESHOLD = 0.02f
    }

    /**
     * Thresholds for front squat exercise.
     */
    object FrontSquat {
        /** Minimum descent depth to count as a rep (30cm) */
        val MIN_DESCENT_DEPTH = Meters(0.30f)

        /** Return tolerance for reaching top position (15cm) */
        val RETURN_TOLERANCE = Meters(0.15f)

        /** Minimum velocity to be considered moving (0.1 m/s) */
        val MIN_VELOCITY = MetersPerSecond(0.1f)

        /** Tolerance for depth checking (2cm) */
        const val DEPTH_TOLERANCE = 0.02f

        /** Movement threshold to transition from IDLE to ECCENTRIC (5cm) */
        const val IDLE_TO_ECCENTRIC_THRESHOLD = 0.05f

        /** Movement threshold to transition from BOTTOM to CONCENTRIC (3cm) */
        const val BOTTOM_TO_CONCENTRIC_THRESHOLD = 0.03f

        /** Stabilization threshold at top position (2cm) */
        const val TOP_STABILIZATION_THRESHOLD = 0.02f
    }

    /**
     * Thresholds for bench press exercise.
     */
    object BenchPress {
        /** Minimum bar descent to count as a rep (15cm) */
        val MIN_DESCENT_DEPTH = Meters(0.15f)

        /** Return tolerance for reaching lockout position (10cm) */
        val RETURN_TOLERANCE = Meters(0.10f)

        /** Minimum velocity to be considered moving */
        val MIN_VELOCITY = MetersPerSecond(0.05f)

        /** Movement threshold to transition from IDLE to ECCENTRIC (5cm) */
        const val IDLE_TO_ECCENTRIC_THRESHOLD = 0.05f

        /** Movement threshold to transition from BOTTOM to CONCENTRIC (3cm) */
        const val BOTTOM_TO_CONCENTRIC_THRESHOLD = 0.03f

        /** Stabilization threshold at top position (2cm) */
        const val TOP_STABILIZATION_THRESHOLD = 0.02f

        /** Maximum elbow flare angle in degrees before warning */
        const val ELBOW_FLARE_MAX_ANGLE = 80f

        /** Maximum horizontal bar drift in meters */
        const val BAR_DRIFT_MAX = 0.10f

        /** Maximum left-right wrist asymmetry in meters */
        const val ASYMMETRY_MAX = 0.03f

        /** Minimum elbow angle for lockout in degrees */
        const val LOCKOUT_MIN_ANGLE = 150f
    }

    /**
     * Thresholds for deadlift exercise.
     */
    object Deadlift {
        /** Minimum pull height to count as a rep (40cm) */
        val MIN_LIFT_HEIGHT = Meters(0.40f)

        /** Return tolerance for lowering back to floor (15cm) */
        val RETURN_TOLERANCE = Meters(0.15f)

        /** Minimum velocity to be considered moving */
        val MIN_VELOCITY = MetersPerSecond(0.05f)

        /** Movement threshold to transition from IDLE to CONCENTRIC (5cm) */
        const val IDLE_TO_CONCENTRIC_THRESHOLD = 0.05f

        /** Movement threshold to transition from TOP to ECCENTRIC (5cm) */
        const val TOP_TO_ECCENTRIC_THRESHOLD = 0.05f

        /** Stabilization threshold at top position (2cm) */
        const val TOP_STABILIZATION_THRESHOLD = 0.02f

        /** Minimum hip angle for full lockout in degrees */
        const val LOCKOUT_HIP_ANGLE = 170f

        /** Minimum knee angle for full lockout in degrees */
        const val LOCKOUT_KNEE_ANGLE = 170f

        /** Maximum torso angle change before back rounding warning in degrees */
        const val BACK_ROUNDING_THRESHOLD = 15f

        /** Maximum bar drift from midfoot in meters */
        const val BAR_DRIFT_MAX = 0.08f

        /** Near-zero velocity threshold for hitch detection in m/s */
        const val HITCH_VELOCITY_THRESHOLD = 0.02f

        /** Consecutive frames below hitch threshold to trigger warning */
        const val HITCH_FRAME_COUNT = 3
    }

    /**
     * Camera and pose detection thresholds.
     */
    object Camera {
        /** Minimum interval between processed frames in milliseconds (~15 FPS) */
        const val MIN_FRAME_INTERVAL_MS = 66L

        /** Target frame processing rate in FPS */
        const val TARGET_FPS = 15

        /** Log frame stats every N frames */
        const val FRAME_LOG_INTERVAL = 30

        /** Log detailed pose info every N frames */
        const val POSE_LOG_INTERVAL = 90
    }

    /**
     * Calibration quality thresholds.
     */
    object Calibration {
        /** Minimum visibility threshold for pose landmarks (50%) */
        const val MIN_LANDMARK_VISIBILITY = 0.5f

        /** Tolerance for total height ratio validation (10%) */
        const val HEIGHT_RATIO_TOLERANCE = 0.1f

        /** Torso height as percentage of total height (~52%) */
        const val TORSO_HEIGHT_RATIO = 0.52f

        /** Leg height as percentage of total height (~48%) */
        const val LEG_HEIGHT_RATIO = 0.48f
    }

    /**
     * Squat depth validation thresholds.
     */
    object DepthValidation {
        /** Weight for hip crease validation method (50%) */
        const val HIP_CREASE_WEIGHT = 0.5f

        /** Weight for femur angle validation method (30%) */
        const val FEMUR_ANGLE_WEIGHT = 0.3f

        /** Weight for simple hip-knee check method (20%) */
        const val SIMPLE_CHECK_WEIGHT = 0.2f

        /** Minimum confidence threshold to pass validation (60%) */
        const val CONFIDENCE_THRESHOLD = 0.6f

        /** Hip crease offset as percentage of hip-to-knee distance (12%) */
        const val HIP_CREASE_OFFSET_RATIO = 0.12f
    }

    /**
     * Velocity calculation thresholds.
     */
    object Velocity {
        /** Size of position history buffer for velocity calculations */
        const val HISTORY_SIZE = 5

        /** Size of velocity smoothing buffer (moving average) */
        const val SMOOTHING_SIZE = 5

        /** Minimum history size needed for accurate velocity calculation */
        const val MIN_HISTORY_FOR_CALCULATION = 3

        /** Log velocity info every N frames */
        const val VELOCITY_LOG_INTERVAL = 30
    }
}
