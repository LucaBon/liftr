package com.liftr.domain.extractor

import com.liftr.domain.calculator.CoordinateCalculator
import com.liftr.domain.model.PoseKeypoint
import kotlin.math.abs

/**
 * Extracts body segment measurements from pose landmarks.
 *
 * This class calculates heights, widths, and limb lengths
 * from normalized pose coordinates, handling missing landmarks gracefully.
 *
 * @property coordinateCalculator Calculator for distances and coordinates
 */
class BodySegmentExtractor(
    private val coordinateCalculator: CoordinateCalculator
) {

    /**
     * Extracts all body segment heights from landmarks.
     *
     * Heights include:
     * - Head top position
     * - Face level (nose/ears)
     * - Shoulder level
     * - Hip level
     * - Knee level
     * - Ankle level (if visible)
     * - Total body height
     *
     * @param landmarks List of pose keypoints (Y-axis should be normalized)
     * @param aspectRatio Image aspect ratio
     * @param isPortrait true if image is in portrait orientation
     * @param headTopY Pre-calculated head top Y position
     * @param anklesVisible Whether ankle landmarks are visible
     * @return BodyHeights containing all height measurements
     */
    fun extractHeights(
        landmarks: List<PoseKeypoint>,
        aspectRatio: Float,
        isPortrait: Boolean,
        headTopY: Float,
        anklesVisible: Boolean
    ): BodyHeights {
        // Calculate key Y positions
        val headFaceY = coordinateCalculator.averageY(landmarks, 0, 7, 8)  // nose, ears
        val shouldersY = coordinateCalculator.averageY(landmarks, 11, 12)
        val hipsY = coordinateCalculator.averageY(landmarks, 23, 24)
        val kneesY = coordinateCalculator.averageY(landmarks, 25, 26)
        val anklesY = if (anklesVisible) {
            coordinateCalculator.averageY(landmarks, 27, 28)
        } else {
            null
        }

        // Calculate total height
        val totalHeight = if (anklesY != null) {
            anklesY - headTopY  // Full body visible
        } else {
            // Estimate: if knees are at 60% of height, total = (kneesY - headTopY) / 0.6
            (kneesY - headTopY) / 0.6f
        }

        return BodyHeights(
            headTop = headTopY,
            headFace = headFaceY,
            shoulders = shouldersY,
            hips = hipsY,
            knees = kneesY,
            ankles = anklesY,
            totalHeight = totalHeight
        )
    }

    /**
     * Calculates individual segment heights from body heights.
     *
     * @param bodyHeights Pre-calculated body height positions
     * @param anklesVisible Whether ankle landmarks are visible
     * @return SegmentHeights with individual segment measurements
     */
    fun calculateSegmentHeights(
        bodyHeights: BodyHeights,
        anklesVisible: Boolean
    ): SegmentHeights {
        // Calculate segment heights using absolute values
        val headNeckHeight = abs(bodyHeights.shoulders - bodyHeights.headTop)
        val torsoHeight = abs(bodyHeights.hips - bodyHeights.shoulders)
        val upperLegHeight = abs(bodyHeights.knees - bodyHeights.hips)

        val lowerLegHeight = if (anklesVisible && bodyHeights.ankles != null) {
            abs(bodyHeights.ankles - bodyHeights.knees)
        } else {
            // Ankles not visible - estimate lower leg as ~25% of total height
            bodyHeights.totalHeight * 0.25f
        }

        return SegmentHeights(
            headNeck = headNeckHeight,
            torso = torsoHeight,
            upperLeg = upperLegHeight,
            lowerLeg = lowerLegHeight
        )
    }

    /**
     * Extracts body width measurements from landmarks.
     *
     * Widths include:
     * - Shoulder width (between shoulder joints)
     * - Hip width (between hip joints)
     * - Knee width (between knees in standing position)
     *
     * @param landmarks List of pose keypoints
     * @param aspectRatio Image aspect ratio
     * @param isPortrait true if image is in portrait orientation
     * @return BodyWidths containing all width measurements
     */
    fun extractWidths(
        landmarks: List<PoseKeypoint>,
        aspectRatio: Float,
        isPortrait: Boolean
    ): BodyWidths {
        // Calculate raw X-axis widths
        val shoulderWidthX = abs(landmarks[12].x - landmarks[11].x)
        val hipWidthX = abs(landmarks[24].x - landmarks[23].x)
        val kneeWidthX = abs(landmarks[26].x - landmarks[25].x)

        // Scale widths for aspect ratio
        // In portrait: X-axis needs scaling
        // In landscape: X-axis doesn't need scaling
        val shoulderWidth = coordinateCalculator.scaleForAspectRatio(
            shoulderWidthX,
            aspectRatio,
            isPortrait,
            isHorizontal = true
        )

        val hipWidth = coordinateCalculator.scaleForAspectRatio(
            hipWidthX,
            aspectRatio,
            isPortrait,
            isHorizontal = true
        )

        val kneeWidth = coordinateCalculator.scaleForAspectRatio(
            kneeWidthX,
            aspectRatio,
            isPortrait,
            isHorizontal = true
        )

        return BodyWidths(
            shoulders = shoulderWidth,
            hips = hipWidth,
            knees = kneeWidth
        )
    }

    /**
     * Extracts limb length measurements from landmarks.
     *
     * Calculates:
     * - Upper arm length (shoulder to elbow)
     * - Forearm length (elbow to wrist)
     *
     * Returns fallback values if landmarks are not visible.
     *
     * @param landmarks List of pose keypoints
     * @param aspectRatio Image aspect ratio
     * @param isPortrait true if image is in portrait orientation
     * @param totalHeight Total body height for calculating fallback ratios
     * @param leftElbowVisible Whether left elbow is visible
     * @param leftWristVisible Whether left wrist is visible
     * @return LimbLengths containing arm segment measurements
     */
    fun extractLimbLengths(
        landmarks: List<PoseKeypoint>,
        aspectRatio: Float,
        isPortrait: Boolean,
        totalHeight: Float,
        leftElbowVisible: Boolean,
        leftWristVisible: Boolean
    ): LimbLengths {
        // Calculate upper arm length
        val upperArmLength = if (leftElbowVisible) {
            val dist = coordinateCalculator.calculateDistance(
                landmarks[11].x, landmarks[11].y,  // left shoulder
                landmarks[13].x, landmarks[13].y,  // left elbow
                aspectRatio,
                isPortrait
            )
            dist
        } else {
            // Fallback: use standard ratio
            STANDARD_UPPER_ARM_RATIO * totalHeight
        }

        // Calculate forearm length
        val forearmLength = if (leftElbowVisible && leftWristVisible) {
            val dist = coordinateCalculator.calculateDistance(
                landmarks[13].x, landmarks[13].y,  // left elbow
                landmarks[15].x, landmarks[15].y,  // left wrist
                aspectRatio,
                isPortrait
            )
            dist
        } else {
            // Fallback: use standard ratio
            STANDARD_FOREARM_RATIO * totalHeight
        }

        return LimbLengths(
            upperArm = upperArmLength,
            forearm = forearmLength,
            upperArmUsedFallback = !leftElbowVisible,
            forearmUsedFallback = !(leftElbowVisible && leftWristVisible)
        )
    }

    companion object {
        /**
         * Standard upper arm length as ratio of total height.
         * Used when elbow is not visible.
         */
        private const val STANDARD_UPPER_ARM_RATIO = 0.19f

        /**
         * Standard forearm length as ratio of total height.
         * Used when elbow or wrist is not visible.
         */
        private const val STANDARD_FOREARM_RATIO = 0.16f
    }
}

/**
 * Body height measurements (Y-axis positions in normalized coordinates).
 *
 * @property headTop Y position of top of head
 * @property headFace Y position of face (nose/ears)
 * @property shoulders Y position of shoulder line
 * @property hips Y position of hip line
 * @property knees Y position of knee line
 * @property ankles Y position of ankle line (null if not visible)
 * @property totalHeight Total body height in normalized units
 */
data class BodyHeights(
    val headTop: Float,
    val headFace: Float,
    val shoulders: Float,
    val hips: Float,
    val knees: Float,
    val ankles: Float?,
    val totalHeight: Float
)

/**
 * Individual body segment heights in normalized units.
 *
 * @property headNeck Combined head and neck height
 * @property torso Torso height (shoulders to hips)
 * @property upperLeg Upper leg height (hips to knees)
 * @property lowerLeg Lower leg height (knees to ankles)
 */
data class SegmentHeights(
    val headNeck: Float,
    val torso: Float,
    val upperLeg: Float,
    val lowerLeg: Float
)

/**
 * Body width measurements in normalized units.
 *
 * @property shoulders Distance between shoulder joints
 * @property hips Distance between hip joints
 * @property knees Distance between knees
 */
data class BodyWidths(
    val shoulders: Float,
    val hips: Float,
    val knees: Float
)

/**
 * Limb length measurements in normalized units.
 *
 * @property upperArm Upper arm length (shoulder to elbow)
 * @property forearm Forearm length (elbow to wrist)
 * @property upperArmUsedFallback Whether fallback value was used for upper arm
 * @property forearmUsedFallback Whether fallback value was used for forearm
 */
data class LimbLengths(
    val upperArm: Float,
    val forearm: Float,
    val upperArmUsedFallback: Boolean = false,
    val forearmUsedFallback: Boolean = false
)
