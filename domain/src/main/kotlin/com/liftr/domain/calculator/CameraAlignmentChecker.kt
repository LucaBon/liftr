package com.liftr.domain.calculator

import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Camera alignment issues that can affect measurement accuracy
 */
enum class AlignmentIssue {
    NONE,           // Camera is properly aligned
    TILTED,         // Camera is tilted (rotated)
    SIDE_VIEW,      // Viewing from side angle instead of front
    TILTED_AND_SIDE,// Both issues present
    OFF_CENTER      // Body not centered in frame (lens distortion risk)
}

/**
 * Result of camera alignment check
 */
data class AlignmentCheckResult(
    val issue: AlignmentIssue,
    val tiltAngleDegrees: Float = 0f,
    val shoulderWidthToHeightRatio: Float = 0f,  // Shoulder width as percentage of height
    val bodyCenterX: Float = 0.5f,  // Horizontal center position of body (0-1 range)
    val message: String = ""
)

/**
 * Checks camera alignment using pose landmarks to ensure accurate measurements
 */
class CameraAlignmentChecker {

    companion object {
        // Thresholds
        private const val MAX_TILT_ANGLE_DEGREES = 8f  // Maximum acceptable camera tilt
        private const val MIN_SHOULDER_WIDTH_RATIO = 0.15f  // Minimum shoulder width / height ratio for frontal view

        // Center alignment thresholds (to minimize lens distortion)
        private const val MIN_CENTER_X = 0.35f  // Minimum X position for centered body
        private const val MAX_CENTER_X = 0.65f  // Maximum X position for centered body
    }

    /**
     * Check camera alignment using pose landmarks
     *
     * @param poseResult The detected pose
     * @param aspectRatio Image aspect ratio for correct distance calculation (always >= 1.0)
     * @param isPortrait Whether the image is in portrait orientation
     * @return Alignment check result with detected issues
     */
    fun checkAlignment(poseResult: PoseResult, aspectRatio: Float, isPortrait: Boolean): AlignmentCheckResult {
        val landmarks = poseResult.landmarks

        // Get key landmarks
        val leftShoulder = landmarks.getOrNull(11)
        val rightShoulder = landmarks.getOrNull(12)
        val leftHip = landmarks.getOrNull(23)
        val rightHip = landmarks.getOrNull(24)

        // Check if we have the necessary landmarks
        if (leftShoulder == null || rightShoulder == null ||
            leftHip == null || rightHip == null ||
            leftShoulder.visibility < 0.5f || rightShoulder.visibility < 0.5f ||
            leftHip.visibility < 0.5f || rightHip.visibility < 0.5f) {
            return AlignmentCheckResult(
                issue = AlignmentIssue.NONE,
                message = "Insufficient landmarks visible for alignment check"
            )
        }

        // MediaPipe normalizes x by the image width and y by the height, so x
        // distances are converted to image-height units before being compared
        // with y (in tilt angles and in the width-to-height ratio).
        val xScale = CoordinateCalculator.xScale(aspectRatio, isPortrait)

        // 1. Check camera tilt by analyzing shoulder and hip angles
        val shoulderTilt = calculateTiltAngle(leftShoulder, rightShoulder, xScale)
        val hipTilt = calculateTiltAngle(leftHip, rightHip, xScale)
        val avgTilt = (shoulderTilt + hipTilt) / 2f
        val isTilted = abs(avgTilt) > MAX_TILT_ANGLE_DEGREES

        // 2. Check if viewing from side: turning away narrows the shoulders
        // relative to height. Shoulder and hip widths are not compared — the
        // hip landmarks are the hip joints, ~0.6x the shoulder width when
        // facing the camera, and turning narrows both by the same factor.
        val shoulderWidthNorm = abs(rightShoulder.x - leftShoulder.x) * xScale

        // Calculate height for ratio
        val headY = landmarks.getOrNull(0)?.y ?: leftShoulder.y
        val ankleY = landmarks.getOrNull(27)?.y ?: landmarks.getOrNull(28)?.y ?: (leftHip.y + 0.5f)
        val heightNorm = abs(ankleY - headY)

        val shoulderWidthToHeightRatio = shoulderWidthNorm / heightNorm
        val isSideView = shoulderWidthToHeightRatio < MIN_SHOULDER_WIDTH_RATIO

        // 3. Check if body is centered in frame (to minimize lens distortion)
        // Calculate body center as average of shoulders and hips
        val bodyCenterX = (leftShoulder.x + rightShoulder.x + leftHip.x + rightHip.x) / 4f
        val isOffCenter = bodyCenterX < MIN_CENTER_X || bodyCenterX > MAX_CENTER_X

        // Determine issue and create message
        val issue = when {
            isTilted && isSideView -> AlignmentIssue.TILTED_AND_SIDE
            isTilted -> AlignmentIssue.TILTED
            isSideView -> AlignmentIssue.SIDE_VIEW
            isOffCenter -> AlignmentIssue.OFF_CENTER
            else -> AlignmentIssue.NONE
        }

        val message = when (issue) {
            AlignmentIssue.TILTED ->
                "Camera is tilted ${abs(avgTilt).toInt()}°. Hold phone upright."
            AlignmentIssue.SIDE_VIEW ->
                "Please face the camera directly (front view). Current shoulder width: ${(shoulderWidthToHeightRatio * 100).toInt()}% of height."
            AlignmentIssue.TILTED_AND_SIDE ->
                "Camera is tilted ${abs(avgTilt).toInt()}° and viewing from side. Hold phone upright and face camera directly."
            AlignmentIssue.OFF_CENTER ->
                if (bodyCenterX < 0.5f) {
                    "Move to the right - keep your body centered in the frame for best accuracy."
                } else {
                    "Move to the left - keep your body centered in the frame for best accuracy."
                }
            AlignmentIssue.NONE ->
                "Camera alignment is good"
        }

        return AlignmentCheckResult(
            issue = issue,
            tiltAngleDegrees = avgTilt,
            shoulderWidthToHeightRatio = shoulderWidthToHeightRatio,
            bodyCenterX = bodyCenterX,
            message = message
        )
    }

    /**
     * Calculate tilt angle between two landmarks (e.g., shoulders or hips)
     * Returns angle in degrees. 0° = horizontal, positive = tilted clockwise
     */
    private fun calculateTiltAngle(left: PoseKeypoint, right: PoseKeypoint, xScale: Float): Float {
        val dx = (right.x - left.x) * xScale
        val dy = right.y - left.y
        val angleRadians = atan2(dy, dx)
        return Math.toDegrees(angleRadians.toDouble()).toFloat()
    }
}
