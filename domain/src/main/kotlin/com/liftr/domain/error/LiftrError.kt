package com.liftr.domain.error

/**
 * Base sealed class for all domain errors in the Liftr application.
 *
 * This provides a type-safe way to handle errors across different layers
 * of the application, ensuring comprehensive error handling and reporting.
 */
sealed class LiftrError : Exception() {

    /**
     * Camera-related errors.
     *
     * @property message Human-readable error message
     * @property errorCode Specific camera error code
     */
    data class CameraError(
        override val message: String,
        val errorCode: CameraErrorCode
    ) : LiftrError()

    /**
     * Pose detection errors.
     *
     * @property message Human-readable error message
     * @property errorCode Specific pose detection error code
     */
    data class PoseDetectionError(
        override val message: String,
        val errorCode: PoseErrorCode
    ) : LiftrError()

    /**
     * Calibration-related errors.
     *
     * @property message Human-readable error message
     * @property errorCode Specific calibration error code
     */
    data class CalibrationError(
        override val message: String,
        val errorCode: CalibrationErrorCode
    ) : LiftrError()

    /**
     * Database operation errors.
     *
     * @property message Human-readable error message
     * @property cause Original exception that caused this error
     */
    data class DatabaseError(
        override val message: String,
        override val cause: Throwable? = null
    ) : LiftrError()

    /**
     * Data validation errors.
     *
     * @property field The field that failed validation
     * @property message Human-readable error message
     */
    data class ValidationError(
        val field: String,
        override val message: String
    ) : LiftrError()

    /**
     * Permission-related errors.
     *
     * @property permission The permission that was denied
     * @property message Human-readable error message
     */
    data class PermissionError(
        val permission: String,
        override val message: String
    ) : LiftrError()

    /**
     * Unknown or unexpected errors.
     *
     * @property message Human-readable error message
     * @property cause Original exception that caused this error
     */
    data class UnknownError(
        override val message: String,
        override val cause: Throwable? = null
    ) : LiftrError()
}

/**
 * Error codes for camera-related errors.
 */
enum class CameraErrorCode {
    /** Camera permission was denied by the user */
    PERMISSION_DENIED,

    /** Camera hardware is not available on the device */
    CAMERA_NOT_AVAILABLE,

    /** Failed to initialize the camera */
    INITIALIZATION_FAILED,

    /** Camera focus operation failed */
    FOCUS_FAILED
}

/**
 * Error codes for pose detection errors.
 */
enum class PoseErrorCode {
    /** Pose detection model failed to load */
    MODEL_NOT_LOADED,

    /** Pose detection processing failed */
    PROCESSING_FAILED,

    /** No pose was detected in the frame */
    NO_POSE_DETECTED,

    /** Detected pose has low confidence */
    LOW_CONFIDENCE
}

/**
 * Error codes for calibration errors.
 */
enum class CalibrationErrorCode {
    /** Detected pose is invalid for calibration */
    INVALID_POSE,

    /** Required body parts are not visible */
    BODY_NOT_VISIBLE,

    /** Camera alignment is poor */
    POOR_ALIGNMENT,

    /** Calibration validation failed */
    VALIDATION_FAILED
}
