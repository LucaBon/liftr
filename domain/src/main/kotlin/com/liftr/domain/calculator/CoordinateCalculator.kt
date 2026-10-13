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

    companion object {
        /** Face landmarks (nose/ears) to head top, as a fraction of stature. */
        const val FACE_TO_HEAD_TOP_RATIO = 0.065f

        /** Ankle joint height above the floor, as a fraction of stature. */
        const val ANKLE_HEIGHT_RATIO = 0.04f

        /** Knee joint height above the floor, as a fraction of stature. */
        const val KNEE_HEIGHT_RATIO = 0.285f

        /**
         * Factor that converts a normalized x distance into image-height units, the
         * unit of normalized y.
         *
         * MediaPipe normalizes x by the image width and y by the image height. On a
         * portrait frame (height = width * aspectRatio) a normalized dx therefore
         * spans fewer pixels per unit than dy and must be divided by the aspect
         * ratio; on a landscape frame it must be multiplied by it.
         *
         * @param aspectRatio Image aspect ratio (longer side / shorter side, >= 1.0)
         * @param isPortrait true if the image is taller than it is wide
         * @return imageWidth / imageHeight
         */
        fun xScale(aspectRatio: Float, isPortrait: Boolean): Float {
            if (aspectRatio <= 0f || !aspectRatio.isFinite()) return 1f
            return if (isPortrait) 1f / aspectRatio else aspectRatio
        }
    }

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
            .filter { it.visibility >= PoseKeypoint.VISIBILITY_THRESHOLD }
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
                .filter { it.visibility >= PoseKeypoint.VISIBILITY_THRESHOLD }
                .map { it.y }
                .average()
                .toFloat()
            ankles
        } else {
            val knees = listOf(25, 26)
                .mapNotNull { landmarks.getOrNull(it) }
                .filter { it.visibility >= PoseKeypoint.VISIBILITY_THRESHOLD }
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
     * Calculates the Euclidean distance between two points, in image-height
     * units (the unit of normalized y).
     *
     * @param p1 First point
     * @param p2 Second point
     * @param aspectRatio Image aspect ratio (longer side / shorter side, >= 1.0)
     * @param isPortrait true if image is in portrait orientation
     * @return Distance in image-height units
     */
    fun calculateDistance(
        p1: PoseKeypoint,
        p2: PoseKeypoint,
        aspectRatio: Float,
        isPortrait: Boolean
    ): Float = calculateDistance(p1.x, p1.y, p2.x, p2.y, aspectRatio, isPortrait)

    /**
     * Calculates the distance between two coordinate pairs, in image-height
     * units (the unit of normalized y).
     *
     * @param x1 X coordinate of first point
     * @param y1 Y coordinate of first point
     * @param x2 X coordinate of second point
     * @param y2 Y coordinate of second point
     * @param aspectRatio Image aspect ratio (longer side / shorter side, >= 1.0)
     * @param isPortrait true if image is in portrait orientation
     * @return Distance in image-height units
     */
    fun calculateDistance(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        aspectRatio: Float,
        isPortrait: Boolean
    ): Float {
        val dx = (x2 - x1) * xScale(aspectRatio, isPortrait)
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Expresses a distance measured along one image axis in image-height units.
     *
     * Horizontal distances are converted with [xScale]; vertical distances are
     * already in image-height units and are returned unchanged.
     *
     * @param distance Distance in normalized landmark units along one axis
     * @param aspectRatio Image aspect ratio (longer side / shorter side, >= 1.0)
     * @param isPortrait true if image is in portrait orientation
     * @param isHorizontal true if this is a horizontal (x) distance
     * @return The distance in image-height units
     */
    fun scaleForAspectRatio(
        distance: Float,
        aspectRatio: Float,
        isPortrait: Boolean,
        isHorizontal: Boolean
    ): Float = if (isHorizontal) distance * xScale(aspectRatio, isPortrait) else distance

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
            .filter { it.visibility >= PoseKeypoint.VISIBILITY_THRESHOLD }
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
            .filter { it.visibility >= PoseKeypoint.VISIBILITY_THRESHOLD }
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
     * X and Y must share a unit: pass x already multiplied by
     * [com.liftr.domain.model.PoseResult.aspectScaleX].
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
     * X and Y must share a unit: pass x already multiplied by
     * [com.liftr.domain.model.PoseResult.aspectScaleX].
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
     * Estimates the head top from the face level and a lower reference landmark.
     *
     * Face landmarks (nose/ears) sit [FACE_TO_HEAD_TOP_RATIO] of stature below
     * the head top, and the reference landmark sits [lowerHeightRatio] of
     * stature above the floor, so the face-to-reference distance is a known
     * fraction of stature. That gives stature in image units, and the head-top
     * offset scales with the body instead of the frame.
     *
     * @param headFaceY Y position of face landmarks
     * @param lowerY Y position of the reference landmark (ankles or knees)
     * @param lowerHeightRatio Height of the reference landmark above the floor,
     *   as a fraction of stature ([ANKLE_HEIGHT_RATIO] or [KNEE_HEIGHT_RATIO])
     * @return Estimated Y position of head top
     */
    fun estimateHeadTop(headFaceY: Float, lowerY: Float, lowerHeightRatio: Float): Float {
        val stature = (lowerY - headFaceY) / (1f - FACE_TO_HEAD_TOP_RATIO - lowerHeightRatio)
        return headFaceY - FACE_TO_HEAD_TOP_RATIO * stature
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
