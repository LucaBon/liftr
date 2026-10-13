package com.liftr.ui.common

import com.liftr.domain.error.CalibrationErrorCode
import com.liftr.domain.error.CameraErrorCode
import com.liftr.domain.error.LiftrError
import com.liftr.domain.error.PoseErrorCode

/**
 * UI-level error representation.
 *
 * Translates domain errors into user-friendly messages and actions.
 */
sealed class UiError {
    /**
     * Simple error message to display to the user.
     *
     * @property text The error message text
     */
    data class Message(val text: String) : UiError()

    /**
     * Recoverable error with an action the user can take.
     *
     * @property text The error message text
     * @property action The action the user can take to recover
     */
    data class Recoverable(
        val text: String,
        val action: ErrorAction
    ) : UiError()
}

/**
 * An action the user can take to recover from an error.
 *
 * @property label The label for the action button
 * @property onClick The callback to execute when the action is clicked
 */
data class ErrorAction(
    val label: String,
    val onClick: () -> Unit
)

/**
 * Converts a domain error to a user-friendly UI error.
 */
fun Throwable.toUiError(
    onRetry: (() -> Unit)? = null,
    onNavigateToSettings: (() -> Unit)? = null
): UiError {
    return when (this) {
        is LiftrError.DatabaseError -> UiError.Message(
            "Failed to save data. Please try again."
        )

        is LiftrError.ValidationError -> UiError.Message(
            "Invalid data: $message"
        )

        is LiftrError.CameraError -> when (errorCode) {
            CameraErrorCode.PERMISSION_DENIED -> UiError.Recoverable(
                "Camera permission is required to use this feature",
                ErrorAction("Grant Permission") {
                    onNavigateToSettings?.invoke() ?: run { }
                }
            )
            CameraErrorCode.CAMERA_NOT_AVAILABLE -> UiError.Message(
                "Camera is not available on this device"
            )
            CameraErrorCode.INITIALIZATION_FAILED -> UiError.Recoverable(
                "Failed to initialize camera",
                ErrorAction("Retry") {
                    onRetry?.invoke() ?: run { }
                }
            )
            CameraErrorCode.FOCUS_FAILED -> UiError.Message(
                "Camera focus failed. Please try again."
            )
        }

        is LiftrError.PoseDetectionError -> when (errorCode) {
            PoseErrorCode.MODEL_NOT_LOADED -> UiError.Recoverable(
                "Pose detection model not loaded",
                ErrorAction("Retry") {
                    onRetry?.invoke() ?: run { }
                }
            )
            PoseErrorCode.PROCESSING_FAILED -> UiError.Message(
                "Failed to detect pose. Please ensure you're visible in frame."
            )
            PoseErrorCode.NO_POSE_DETECTED -> UiError.Message(
                "No pose detected. Please ensure you're visible in frame."
            )
            PoseErrorCode.LOW_CONFIDENCE -> UiError.Message(
                "Pose detection confidence is low. Please improve lighting or camera angle."
            )
        }

        is LiftrError.CalibrationError -> when (errorCode) {
            CalibrationErrorCode.INVALID_POSE -> UiError.Message(
                "Invalid pose detected. Please stand straight facing the camera."
            )
            CalibrationErrorCode.BODY_NOT_VISIBLE -> UiError.Message(
                "Body not fully visible. Please step back from the camera."
            )
            CalibrationErrorCode.POOR_ALIGNMENT -> UiError.Message(
                "Poor camera alignment. Please ensure camera is at hip level."
            )
            CalibrationErrorCode.VALIDATION_FAILED -> UiError.Message(
                "Calibration validation failed. Please try again."
            )
        }

        is LiftrError.PermissionError -> UiError.Recoverable(
            "$permission permission is required",
            ErrorAction("Grant Permission") {
                onNavigateToSettings?.invoke() ?: run { }
            }
        )

        is LiftrError.UnknownError -> UiError.Message(
            "An unexpected error occurred: ${message ?: "Unknown error"}"
        )

        else -> UiError.Message(
            "An unexpected error occurred"
        )
    }
}
