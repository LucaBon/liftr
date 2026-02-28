package com.lifthelper.domain.calculator

import com.lifthelper.domain.model.PoseKeypoint
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Handles coordinate calculations including aspect ratio correction,
 * Y-axis normalization, and distance calculations.
 *
 * This class provides essential functionality for converting normalized
 * pose coordinates (0-1 range) into real-world proportional measurements.
 */
class CoordinateCalculator {

    /**
     * Calculates the aspect ratio and determines device orientation.
     *
     * Aspect ratio is always >= 1.0 (longer dimension / shorter dimension)
     * to make distance calculations work correctly in both orientations.
     *
     * @param imageWidth Width of the image in pixels
     * @param imageHeight Height of the image in pixels
     * @return AspectRatioResult containing ratio and orientation
     */
    fun calculateAspectRatio(imageWidth: Int, imageHeight: Int): AspectRatioResult {
        val isPortrait = imageHeight > imageWidth
        val aspectRatio = if (isPortrait) {
            imageHeight.toFloat() / imageWidth.toFloat()
        } else {
            imageWidth.toFloat() / imageHeight.toFloat()
        }

        return AspectRatioResult(
            ratio = aspectRatio,
            isPortrait = isPortrait
        )
    }

    /**
     * Detects if the Y-axis appears to be inverted in the landmark data.
     *
     * In normal orientation, Y increases from top (0) to bottom (1),
     * so head should have smaller Y than knees/ankles.
     * If ankles have smaller Y than head, the coordinate system is flipped.
     *
     * @param landmarks List of detected pose keypoints
     * @return true if Y-axis needs to be inverted
     */
    fun detectYAxisInversion(landmarks: List<PoseKeypoint>): Boolean {
        // Get head position (from visible head landmarks)
        val headLandmarks = listOf(0, 7, 8)  // nose, left_ear, right_ear
        val visibleHeadLandmarks = headLandmarks.filter { idx ->
            landmarks.getOrNull(idx)?.visibility?.let { it >= 0.5f } ?: false
        }

        if (visibleHeadLandmarks.isEmpty()) {
            return false  // Can't determine without head landmarks
        }

        val headY = visibleHeadLandmarks
            .mapNotNull { landmarks.getOrNull(it) }
            .filter { it.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD }
            .map { it.y }
            .average()
            .toFloat()

        // Check ankles if visible, otherwise use knees
        val ankleVisible = listOf(27, 28).all { idx ->
            landmarks.getOrNull(idx)?.visibility?.let { it >= 0.5f } ?: false
        }

        val referenceY = if (ankleVisible) {
            val ankles = listOf(27, 28)
                .mapNotNull { landmarks.getOrNull(it) }
                .filter { it.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD }
                .map { it.y }
                .average()
                .toFloat()
            ankles
        } else {
            val knees = listOf(25, 26)
                .mapNotNull { landmarks.getOrNull(it) }
                .filter { it.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD }
                .map { it.y }
                .average()
                .toFloat()
            knees
        }

        // If ankles/knees have smaller Y than head, coordinate system is flipped
        return referenceY < headY
    }

    /**
     * Normalizes Y-axis coordinates by flipping them if necessary.
     *
     * This ensures that Y always increases from head to feet,
     * making subsequent calculations consistent.
     *
     * @param landmarks List of detected pose keypoints
     * @return New list of landmarks with potentially flipped Y coordinates
     */
    fun normalizeYAxis(landmarks: List<PoseKeypoint>): List<PoseKeypoint> {
        val isInverted = detectYAxisInversion(landmarks)

        return if (isInverted) {
            landmarks.map { keypoint ->
                keypoint.copy(y = 1.0f - keypoint.y)
            }
        } else {
            landmarks
        }
    }

    /**
     * Calculates Euclidean distance between two points with aspect ratio correction.
     *
     * Since normalized coordinates are in [0,1] range for both X and Y,
     * but the actual pixel dimensions differ, we need to scale coordinates
     * to get accurate real-world proportions.
     *
     * @param p1 First point
     * @param p2 Second point
     * @param aspectRatio Image aspect ratio (>= 1.0)
     * @param isPortrait true if image is in portrait orientation
     * @return Distance in normalized units with aspect correction
     */
    fun calculateDistance(
        p1: PoseKeypoint,
        p2: PoseKeypoint,
        aspectRatio: Float,
        isPortrait: Boolean
    ): Float {
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y

        // Scale the coordinate along the shorter axis to make distances isotropic
        return if (isPortrait) {
            // Portrait: scale X (narrower axis) by aspect ratio
            val dxScaled = dx * aspectRatio
            sqrt(dxScaled * dxScaled + dy * dy)
        } else {
            // Landscape: scale Y (narrower axis) by aspect ratio
            val dyScaled = dy * aspectRatio
            sqrt(dx * dx + dyScaled * dyScaled)
        }
    }

    /**
     * Calculates distance between two coordinate pairs.
     *
     * @param x1 X coordinate of first point
     * @param y1 Y coordinate of first point
     * @param x2 X coordinate of second point
     * @param y2 Y coordinate of second point
     * @param aspectRatio Image aspect ratio (>= 1.0)
     * @param isPortrait true if image is in portrait orientation
     * @return Distance in normalized units with aspect correction
     */
    fun calculateDistance(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        aspectRatio: Float,
        isPortrait: Boolean
    ): Float {
        val dx = x2 - x1
        val dy = y2 - y1

        return if (isPortrait) {
            val dxScaled = dx * aspectRatio
            sqrt(dxScaled * dxScaled + dy * dy)
        } else {
            val dyScaled = dy * aspectRatio
            sqrt(dx * dx + dyScaled * dyScaled)
        }
    }

    /**
     * Scales a distance measurement to account for aspect ratio.
     *
     * Used for horizontal distances (widths) that need to be made
     * comparable with vertical distances (heights).
     *
     * @param distance Raw distance in normalized units
     * @param aspectRatio Image aspect ratio (>= 1.0)
     * @param isPortrait true if image is in portrait orientation
     * @param isHorizontal true if this is a horizontal distance (X-axis)
     * @return Scaled distance
     */
    fun scaleForAspectRatio(
        distance: Float,
        aspectRatio: Float,
        isPortrait: Boolean,
        isHorizontal: Boolean
    ): Float {
        // In portrait: X-axis (horizontal) distances need scaling
        // In landscape: Y-axis (vertical) distances need scaling
        // But we typically measure widths (horizontal), so:
        return if (isPortrait && isHorizontal) {
            distance * aspectRatio
        } else if (!isPortrait && !isHorizontal) {
            distance * aspectRatio
        } else {
            distance  // No scaling needed
        }
    }

    /**
     * Calculates average Y position from multiple landmark indices.
     *
     * Only considers landmarks with visibility >= 0.5
     *
     * @param landmarks List of all pose keypoints
     * @param indices Landmark indices to average
     * @return Average Y position
     */
    fun averageY(landmarks: List<PoseKeypoint>, vararg indices: Int): Float {
        return indices.toList()
            .mapNotNull { landmarks.getOrNull(it) }
            .filter { it.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD }
            .map { it.y }
            .average()
            .toFloat()
    }

    /**
     * Calculates average X position from multiple landmark indices.
     *
     * Only considers landmarks with visibility >= 0.5
     *
     * @param landmarks List of all pose keypoints
     * @param indices Landmark indices to average
     * @return Average X position
     */
    fun averageX(landmarks: List<PoseKeypoint>, vararg indices: Int): Float {
        return indices.toList()
            .mapNotNull { landmarks.getOrNull(it) }
            .filter { it.visibility >= com.lifthelper.util.Constants.LANDMARK_VISIBILITY_THRESHOLD }
            .map { it.x }
            .average()
            .toFloat()
    }

    /**
     * Calculates the angle at vertex B formed by rays BA and BC.
     *
     * Uses the dot product formula: cos(angle) = (BA · BC) / (|BA| * |BC|)
     * Returns the angle in degrees in the range [0, 180].
     * Returns 0 if either segment has zero length.
     *
     * @param ax X coordinate of point A
     * @param ay Y coordinate of point A
     * @param bx X coordinate of point B (vertex)
     * @param by Y coordinate of point B (vertex)
     * @param cx X coordinate of point C
     * @param cy Y coordinate of point C
     * @return Angle at B in degrees [0, 180]
     */
    fun angleBetweenPoints(
        ax: Float, ay: Float,
        bx: Float, by: Float,
        cx: Float, cy: Float
    ): Float {
        val bax = ax - bx
        val bay = ay - by
        val bcx = cx - bx
        val bcy = cy - by

        val dot = bax * bcx + bay * bcy
        val magBA = sqrt(bax * bax + bay * bay)
        val magBC = sqrt(bcx * bcx + bcy * bcy)

        if (magBA == 0f || magBC == 0f) return 0f

        val cosAngle = (dot / (magBA * magBC)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosAngle).toDouble()).toFloat()
    }

    /**
     * Calculates the torso angle from vertical.
     *
     * 0 degrees means the torso is perfectly upright (shoulder directly above hip).
     * 90 degrees means the torso is horizontal.
     *
     * @param shoulderX X coordinate of shoulder midpoint
     * @param shoulderY Y coordinate of shoulder midpoint
     * @param hipX X coordinate of hip midpoint
     * @param hipY Y coordinate of hip midpoint
     * @return Torso angle from vertical in degrees [0, 90]
     */
    fun torsoAngle(
        shoulderX: Float, shoulderY: Float,
        hipX: Float, hipY: Float
    ): Float {
        return Math.toDegrees(
            atan2(abs(shoulderX - hipX), abs(shoulderY - hipY)).toDouble()
        ).toFloat()
    }

    /**
     * Estimates head top position from face landmarks.
     *
     * Face landmarks (nose/ears) are roughly at mid-head.
     * Head height is typically ~13% of body height.
     * So head top is ~6.5% of body height above face level.
     *
     * @param headFaceY Y position of face landmarks
     * @param isYAxisInverted true if Y-axis was inverted during normalization
     * @return Estimated Y position of head top
     */
    fun estimateHeadTop(headFaceY: Float, isYAxisInverted: Boolean): Float {
        val headTopEstimateOffset = 0.065f  // ~6.5% of body height

        return if (isYAxisInverted) {
            headFaceY + headTopEstimateOffset  // If flipped, add to go "up"
        } else {
            headFaceY - headTopEstimateOffset  // Normal: subtract to go up
        }
    }
}

/**
 * Result of aspect ratio calculation.
 *
 * @property ratio Aspect ratio (>= 1.0, longer dimension / shorter dimension)
 * @property isPortrait true if image is taller than it is wide
 */
data class AspectRatioResult(
    val ratio: Float,
    val isPortrait: Boolean
)
