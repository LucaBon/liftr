package com.liftr.domain.calculator

import com.liftr.domain.model.PoseKeypoint
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
            .filter { it.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD }
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
                .filter { it.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD }
                .map { it.y }
                .average()
                .toFloat()
            ankles
        } else {
            val knees = listOf(25, 26)
                .mapNotNull { landmarks.getOrNull(it) }
                .filter { it.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD }
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
     * Calculates Euclidean distance between two points.
     *
     * MediaPipe's Pose Landmarker returns landmark coordinates in an isotropic
     * normalized space (empirically, both axes share the same unit — the model
     * letterboxes its input to a square before inference and the returned x/y
     * are effectively normalized by the longer edge, not per-axis). This means
     * dx and dy can be combined directly with a plain Euclidean formula and NO
     * aspect-ratio correction — applying one makes horizontal distances wrong
     * by a factor of `aspectRatio` (either over- or underestimating widths
     * depending on the direction of the scaling).
     *
     * The `aspectRatio` and `isPortrait` parameters are retained for API
     * compatibility and are intentionally unused.
     *
     * @param p1 First point
     * @param p2 Second point
     * @param aspectRatio Image aspect ratio (unused — landmarks are isotropic)
     * @param isPortrait true if image is in portrait orientation (unused)
     * @return Euclidean distance in normalized landmark units
     */
    @Suppress("UNUSED_PARAMETER")
    fun calculateDistance(
        p1: PoseKeypoint,
        p2: PoseKeypoint,
        aspectRatio: Float,
        isPortrait: Boolean
    ): Float {
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        return sqrt(dx * dx + dy * dy)
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
    @Suppress("UNUSED_PARAMETER")
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
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Returns the distance unchanged.
     *
     * MediaPipe's Pose Landmarker returns landmark coordinates in an isotropic
     * normalized space: both x and y share the same unit regardless of image
     * orientation (empirically the model letterboxes its input to a square and
     * the exposed NormalizedLandmark.x/y are effectively normalized by the
     * longer edge). Horizontal distances therefore do NOT need aspect-ratio
     * correction — applying any scaling (either multiplying or dividing by
     * aspectRatio) results in widths being off by that factor.
     *
     * The parameters are kept for API compatibility and are unused.
     *
     * @param distance Raw distance in normalized landmark units
     * @param aspectRatio Image aspect ratio (unused)
     * @param isPortrait true if image is in portrait orientation (unused)
     * @param isHorizontal true if this is a horizontal distance (unused)
     * @return The distance unchanged
     */
    @Suppress("UNUSED_PARAMETER")
    fun scaleForAspectRatio(
        distance: Float,
        aspectRatio: Float,
        isPortrait: Boolean,
        isHorizontal: Boolean
    ): Float = distance

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
            .filter { it.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD }
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
            .filter { it.visibility >= com.liftr.util.Constants.LANDMARK_VISIBILITY_THRESHOLD }
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
