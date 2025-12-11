package com.lifthelper.vision.calculator

import com.lifthelper.domain.model.PoseKeypoint
import com.lifthelper.domain.model.PoseResult
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Camera alignment issues that can affect measurement accuracy
 */
enum class AlignmentIssue {
    NONE,           // Camera is properly aligned
    TILTED,         // Camera is tilted (rotated)
    SIDE_VIEW,      // Viewing from side angle instead of front
    TILTED_AND_SIDE // Both issues present
}

/**
 * Result of camera alignment check
 */
data class AlignmentCheckResult(
    val issue: AlignmentIssue,
    val tiltAngleDegrees: Float = 0f,
    val shoulderHipWidthRatio: Float = 1f,  // Ratio of shoulder width to hip width
    val shoulderWidthToHeightRatio: Float = 0f,  // Shoulder width as percentage of height
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
        private const val MAX_SHOULDER_HIP_WIDTH_DIFFERENCE = 0.25f  // Max acceptable difference between shoulder and hip widths
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

        // 1. Check camera tilt by analyzing shoulder and hip angles
        val shoulderTilt = calculateTiltAngle(leftShoulder, rightShoulder)
        val hipTilt = calculateTiltAngle(leftHip, rightHip)
        val avgTilt = (shoulderTilt + hipTilt) / 2f
        val isTilted = abs(avgTilt) > MAX_TILT_ANGLE_DEGREES

        // 2. Check if viewing from side by comparing shoulder/hip widths
        // Scale horizontal (X) distances to be comparable with vertical (Y) distances
        val shoulderWidthNorm = if (isPortrait) {
            abs(rightShoulder.x - leftShoulder.x) * aspectRatio
        } else {
            abs(rightShoulder.x - leftShoulder.x) / aspectRatio
        }
        val hipWidthNorm = if (isPortrait) {
            abs(rightHip.x - leftHip.x) * aspectRatio
        } else {
            abs(rightHip.x - leftHip.x) / aspectRatio
        }

        // Calculate height for ratio
        val headY = landmarks.getOrNull(0)?.y ?: leftShoulder.y
        val ankleY = landmarks.getOrNull(27)?.y ?: landmarks.getOrNull(28)?.y ?: (leftHip.y + 0.5f)
        val heightNorm = abs(ankleY - headY)

        val shoulderWidthToHeightRatio = shoulderWidthNorm / heightNorm
        val shoulderHipWidthRatio = if (hipWidthNorm > 0.01f) shoulderWidthNorm / hipWidthNorm else 1f

        // Side view indicators:
        // 1. Shoulder width is too small relative to height (< 15% of height)
        // 2. Shoulder and hip widths differ significantly (should be similar in frontal view)
        val isSideView = shoulderWidthToHeightRatio < MIN_SHOULDER_WIDTH_RATIO ||
                        abs(shoulderHipWidthRatio - 1f) > MAX_SHOULDER_HIP_WIDTH_DIFFERENCE

        // Determine issue and create message
        val issue = when {
            isTilted && isSideView -> AlignmentIssue.TILTED_AND_SIDE
            isTilted -> AlignmentIssue.TILTED
            isSideView -> AlignmentIssue.SIDE_VIEW
            else -> AlignmentIssue.NONE
        }

        val message = when (issue) {
            AlignmentIssue.TILTED ->
                "Camera is tilted ${abs(avgTilt).toInt()}°. Hold phone upright."
            AlignmentIssue.SIDE_VIEW ->
                "Please face the camera directly (front view). Current shoulder width: ${(shoulderWidthToHeightRatio * 100).toInt()}% of height."
            AlignmentIssue.TILTED_AND_SIDE ->
                "Camera is tilted ${abs(avgTilt).toInt()}° and viewing from side. Hold phone upright and face camera directly."
            AlignmentIssue.NONE ->
                "Camera alignment is good"
        }

        return AlignmentCheckResult(
            issue = issue,
            tiltAngleDegrees = avgTilt,
            shoulderHipWidthRatio = shoulderHipWidthRatio,
            shoulderWidthToHeightRatio = shoulderWidthToHeightRatio,
            message = message
        )
    }

    /**
     * Calculate tilt angle between two landmarks (e.g., shoulders or hips)
     * Returns angle in degrees. 0° = horizontal, positive = tilted clockwise
     */
    private fun calculateTiltAngle(left: PoseKeypoint, right: PoseKeypoint): Float {
        val dx = right.x - left.x
        val dy = right.y - left.y
        val angleRadians = atan2(dy, dx)
        return Math.toDegrees(angleRadians.toDouble()).toFloat()
    }
}
