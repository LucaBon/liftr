package com.lifthelper.domain.model

import kotlinx.serialization.Serializable

/**
 * Measurement issues detected during capture
 */
@Serializable
data class MeasurementIssue(
    val measurement: String,      // e.g., "Upper Arm", "Hip Width"
    val issue: String,            // e.g., "Elbow not visible", "Value out of range (3.2x height)"
    val usedFallback: Boolean     // Whether a fallback value was used
)

/**
 * Comprehensive anthropometric measurements for personalized biomechanics calculations
 *
 * All measurements stored as ratios relative to total height for scale independence
 * Actual metric values calculated on-demand using user height
 */
@Serializable
data class AnthropometricData(
    // Body segment lengths (as ratio of total height)
    val headHeightRatio: Float,           // Head top to neck base
    val neckHeightRatio: Float,           // Neck base to shoulders
    val torsoHeightRatio: Float,          // Shoulders to hips
    val upperLegHeightRatio: Float,       // Hips to knees
    val lowerLegHeightRatio: Float,       // Knees to ankles
    val footHeightRatio: Float,           // Ankles to ground

    // Body segment widths (as ratio of total height)
    val shoulderWidthRatio: Float,        // Between shoulder joints
    val hipWidthRatio: Float,             // Between hip joints
    val kneeWidthRatio: Float,            // Between knees (standing width)

    // Limb lengths (as ratio of total height)
    val upperArmLengthRatio: Float,       // Shoulder to elbow
    val forearmLengthRatio: Float,        // Elbow to wrist
    val thighLengthRatio: Float,          // Hip to knee (same as upperLegHeightRatio)
    val shinLengthRatio: Float,           // Knee to ankle (same as lowerLegHeightRatio)

    // Body mass distribution (optional, for advanced biomechanics)
    val userWeight: Float? = null,        // Total body weight in kg

    // Metadata
    val captureTimestamp: Long = System.currentTimeMillis(),
    val captureQuality: CalibrationQuality = CalibrationQuality.OPTIMAL,
    val userHeight: Float,                // Total height in meters (reference for ratios)
    val alignmentMessage: String? = null, // Optional message about camera alignment during capture
    val measurementIssues: List<MeasurementIssue> = emptyList()  // Issues detected during measurement
) {
    companion object {
        /**
         * Standard anthropometric ratios based on biomechanics research
         * Used as fallback when camera measurement fails
         * Based on average adult proportions
         */
        fun getDefaultRatios(userHeight: Float, userWeight: Float? = null): AnthropometricData {
            return AnthropometricData(
                // Segment heights (sum = ~1.0)
                headHeightRatio = 0.13f,      // ~13% head + neck
                neckHeightRatio = 0.05f,      // ~5% neck
                torsoHeightRatio = 0.30f,     // ~30% torso
                upperLegHeightRatio = 0.25f,  // ~25% thigh
                lowerLegHeightRatio = 0.23f,  // ~23% shin
                footHeightRatio = 0.04f,      // ~4% foot height

                // Segment widths
                shoulderWidthRatio = 0.26f,   // ~26% shoulder width
                hipWidthRatio = 0.19f,        // ~19% hip width
                kneeWidthRatio = 0.10f,       // ~10% knee spacing

                // Limb lengths
                upperArmLengthRatio = 0.19f,  // ~19% upper arm
                forearmLengthRatio = 0.16f,   // ~16% forearm
                thighLengthRatio = 0.25f,     // Same as upper leg
                shinLengthRatio = 0.23f,      // Same as lower leg

                userWeight = userWeight,
                userHeight = userHeight,
                captureQuality = CalibrationQuality.FALLBACK
            )
        }

        /**
         * Calculate anthropometric data from pose landmarks (frontal view)
         */
        fun fromPoseResult(
            poseResult: PoseResult,
            userHeight: Float,
            userWeight: Float? = null,
            manualShoulderWidth: Float? = null,  // If provided by user, use this instead of estimating
            isFrontCamera: Boolean = false  // Front camera requires coordinate mirroring (doesn't affect distance calculations)
        ): AnthropometricData? {
            val landmarks = poseResult.landmarks
            val imageWidth = poseResult.imageWidth
            val imageHeight = poseResult.imageHeight

            // Calculate aspect ratio correctly for both portrait and landscape
            // Aspect ratio should always be >= 1.0 (wider dimension / narrower dimension)
            // This makes the distance formula work correctly in both orientations
            val isPortrait = imageHeight > imageWidth
            val aspectRatio = if (isPortrait) {
                imageHeight.toFloat() / imageWidth.toFloat()
            } else {
                imageWidth.toFloat() / imageHeight.toFloat()
            }

            android.util.Log.d("LiftHelper_Calibration",
                "Image dimensions: ${imageWidth}x${imageHeight}, " +
                "orientation: ${if (isPortrait) "portrait" else "landscape"}, " +
                "aspectRatio: $aspectRatio")

            // Check if we have critical landmarks visible
            // CRITICAL (must have all): shoulders, hips, knees
            // HEAD (need at least one): nose OR left_ear OR right_ear
            // OPTIONAL: ankles (for better height estimation)

            val criticalBodyLandmarks = listOf(
                11, 12, // shoulders
                23, 24, // hips
                25, 26  // knees
            )

            val criticalBodyVisible = criticalBodyLandmarks.all { idx ->
                landmarks.getOrNull(idx)?.visibility?.let { it >= 0.5f } ?: false
            }

            if (!criticalBodyVisible) {
                // Log which landmarks are missing for debugging
                val missingLandmarks = criticalBodyLandmarks.filter { idx ->
                    val visibility = landmarks.getOrNull(idx)?.visibility ?: 0f
                    visibility < 0.5f
                }
                android.util.Log.e("LiftHelper_Calibration",
                    "Missing critical body landmarks: ${missingLandmarks.map {
                        when(it) {
                            11 -> "left_shoulder($it)"
                            12 -> "right_shoulder($it)"
                            23 -> "left_hip($it)"
                            24 -> "right_hip($it)"
                            25 -> "left_knee($it)"
                            26 -> "right_knee($it)"
                            else -> "landmark_$it"
                        }
                    }} (visibility: ${missingLandmarks.map { landmarks.getOrNull(it)?.visibility ?: 0f }})")
                return null  // Must have shoulders, hips, and knees
            }

            // Check for at least one head landmark (nose OR ears)
            val headLandmarks = listOf(0, 7, 8)  // nose, left_ear, right_ear
            val headVisible = headLandmarks.any { idx ->
                landmarks.getOrNull(idx)?.visibility?.let { it >= 0.5f } ?: false
            }

            if (!headVisible) {
                android.util.Log.e("LiftHelper_Calibration",
                    "No head landmarks visible: nose=${landmarks.getOrNull(0)?.visibility}, " +
                    "left_ear=${landmarks.getOrNull(7)?.visibility}, " +
                    "right_ear=${landmarks.getOrNull(8)?.visibility}")
                return null  // Need at least one head landmark
            }

            // Check if ankles are visible (optional)
            val anklesVisible = listOf(27, 28).all { idx ->
                landmarks.getOrNull(idx)?.visibility?.let { it >= 0.5f } ?: false
            }

            // Helper to get average Y position
            fun avgY(vararg indices: Int): Float {
                return indices.toList()
                    .mapNotNull { landmarks.getOrNull(it) }
                    .filter { it.visibility >= 0.5f }
                    .map { it.y }
                    .average()
                    .toFloat()
            }

            // Helper to get average X position
            fun avgX(vararg indices: Int): Float {
                return indices.toList()
                    .mapNotNull { landmarks.getOrNull(it) }
                    .filter { it.visibility >= 0.5f }
                    .map { it.x }
                    .average()
                    .toFloat()
            }

            // Helper to calculate Euclidean distance with aspect ratio correction
            // Coordinates need to be scaled to account for non-square pixels in the normalized space
            // In portrait: Y is the longer axis, X needs scaling
            // In landscape: X is the longer axis, Y needs scaling
            fun distance(x1: Float, y1: Float, x2: Float, y2: Float, aspectRatio: Float, isPortrait: Boolean): Float {
                val dx = x2 - x1
                val dy = y2 - y1

                // Scale the coordinate along the shorter axis to make distances isotropic
                return if (isPortrait) {
                    // Portrait: scale X (narrower axis) by aspect ratio
                    val dxScaled = dx * aspectRatio
                    kotlin.math.sqrt(dxScaled * dxScaled + dy * dy)
                } else {
                    // Landscape: scale Y (narrower axis) by aspect ratio
                    val dyScaled = dy * aspectRatio
                    kotlin.math.sqrt(dx * dx + dyScaled * dyScaled)
                }
            }

            // Calculate key positions
            // Note: Y-axis goes from 0 (top) to 1 (bottom) in normalized coordinates

            // For head position, use all visible head landmarks (nose and/or ears)
            val visibleHeadLandmarks = headLandmarks.filter { idx ->
                landmarks.getOrNull(idx)?.visibility?.let { it >= 0.5f } ?: false
            }
            var headFaceY = avgY(*visibleHeadLandmarks.toIntArray())  // Face level (nose/ears)

            var shouldersY = avgY(11, 12)
            var hipsY = avgY(23, 24)
            var kneesY = avgY(25, 26)
            var anklesY = if (anklesVisible) avgY(27, 28) else null

            // Estimate actual head top by adding ~half head height above face landmarks
            // Typical head height is ~13% of body height, and face landmarks are roughly at mid-head
            // So we add ~6.5% of body height above the face level
            val headTopEstimateOffset = 0.065f  // Estimate top of head is ~6.5% of height above face

            android.util.Log.d("LiftHelper_Calibration", "Y coordinates (raw): face=$headFaceY, shoulders=$shouldersY, hips=$hipsY, knees=$kneesY, ankles=$anklesY")

            // Check if Y-axis is inverted (head should be above knees/ankles, meaning smaller Y)
            // If ankles have smaller Y than head, the coordinate system is flipped
            val isYAxisInverted = (anklesY?.let { it < headFaceY } ?: (kneesY < headFaceY))
            if (isYAxisInverted) {
                android.util.Log.w("LiftHelper_Calibration", "⚠️ Y-axis appears inverted - flipping coordinates")
                headFaceY = 1.0f - headFaceY
                shouldersY = 1.0f - shouldersY
                hipsY = 1.0f - hipsY
                kneesY = 1.0f - kneesY
                anklesY = anklesY?.let { 1.0f - it }

                android.util.Log.d("LiftHelper_Calibration", "Y coordinates (flipped): face=$headFaceY, shoulders=$shouldersY, hips=$hipsY, knees=$kneesY, ankles=$anklesY")
            }

            // Now estimate head top position (after potential flip)
            // In normal orientation (Y increases downward), subtract offset to go up
            // The offset is relative to total height in frame
            val headTopY = if (isYAxisInverted) {
                headFaceY + headTopEstimateOffset  // If flipped, add to go "up"
            } else {
                headFaceY - headTopEstimateOffset  // Normal: subtract to go up
            }

            android.util.Log.d("LiftHelper_Calibration", "Estimated head top: $headTopY (face was $headFaceY, offset=$headTopEstimateOffset)")

            val shouldersX = (landmarks[11].x + landmarks[12].x) / 2f
            val hipsX = (landmarks[23].x + landmarks[24].x) / 2f

            // Track measurement issues
            val issues = mutableListOf<MeasurementIssue>()

            // Total height in normalized coordinates
            // If ankles not visible, estimate total height from knees (knees are typically ~60% down from head)
            val totalHeightNorm = if (anklesY != null) {
                anklesY - headTopY  // Full body visible
            } else {
                // Estimate: if knees are at 60% of height, total = (kneesY - headTopY) / 0.6
                android.util.Log.w("LiftHelper_Calibration", "⚠️ Ankles not visible, estimating total height from knees")
                issues.add(MeasurementIssue(
                    measurement = "Total Height / Lower Leg",
                    issue = "Ankles not visible (visibility L:${landmarks.getOrNull(27)?.visibility ?: 0f}, R:${landmarks.getOrNull(28)?.visibility ?: 0f}). Height estimated from knees.",
                    usedFallback = true
                ))
                (kneesY - headTopY) / 0.6f
            }

            if (totalHeightNorm <= 0) {
                // Invalid: head should be above ankles
                android.util.Log.e("LiftHelper_Calibration", "❌ Invalid totalHeightNorm: $totalHeightNorm (head should be above ankles)")
                return null
            }

            // Log body size in frame for debugging
            val bodyHeightPixels = (totalHeightNorm * imageHeight).toInt()
            val bodyHeightPercent = (totalHeightNorm * 100).toInt()

            if (totalHeightNorm < 0.20f) {
                android.util.Log.w("LiftHelper_Calibration", "⚠️ Body occupies only ${bodyHeightPercent}% of frame (${bodyHeightPixels}px out of ${imageHeight}px)")
                android.util.Log.w("LiftHelper_Calibration", "   User should move closer to camera or check for zoom/telephoto lens")
            } else if (totalHeightNorm > 0.50f) {
                android.util.Log.i("LiftHelper_Calibration", "✅ Good body size: ${bodyHeightPercent}% of frame (${bodyHeightPixels}px out of ${imageHeight}px)")
            } else {
                android.util.Log.d("LiftHelper_Calibration", "Body size: ${bodyHeightPercent}% of frame (${bodyHeightPixels}px out of ${imageHeight}px)")
            }

            // Calculate segment heights
            // After potential flip, Y increases from head to feet
            val headNeckHeight = kotlin.math.abs(shouldersY - headTopY)
            val torsoHeight = kotlin.math.abs(hipsY - shouldersY)
            val upperLegHeight = kotlin.math.abs(kneesY - hipsY)
            val lowerLegHeight = if (anklesY != null) {
                kotlin.math.abs(anklesY - kneesY)
            } else {
                // Ankles not visible (solo front camera mode with partial body in frame)
                // Estimate lower leg as ~25% of total height (typical proportion)
                totalHeightNorm * 0.25f
            }

            android.util.Log.d("LiftHelper_Calibration", "Segment heights (normalized): head+neck=$headNeckHeight, torso=$torsoHeight, upperLeg=$upperLegHeight, lowerLeg=$lowerLegHeight, total=$totalHeightNorm")
            android.util.Log.d("LiftHelper_Calibration", "Image aspect ratio: $aspectRatio (${imageWidth}x${imageHeight})")

            // Validate critical segments are positive
            if (headNeckHeight <= 0 || torsoHeight <= 0 || upperLegHeight <= 0) {
                return null  // Invalid pose data
            }

            // Lower leg can be estimated, so just check it's reasonable (not negative or too large)
            if (lowerLegHeight <= 0 || lowerLegHeight > totalHeightNorm * 0.5f) {
                return null  // Invalid estimation
            }

            // Calculate widths as horizontal distances
            // For frontal view, widths should be primarily along X-axis
            // We need to scale X to make it comparable to Y based on orientation
            val shoulderWidthX = kotlin.math.abs(landmarks[12].x - landmarks[11].x)
            val hipWidthX = kotlin.math.abs(landmarks[24].x - landmarks[23].x)
            val kneeWidthX = kotlin.math.abs(landmarks[26].x - landmarks[25].x)

            android.util.Log.d("LiftHelper_Calibration", "Width X values (raw): shoulder=$shoulderWidthX, hip=$hipWidthX, knee=$kneeWidthX")

            // Convert X widths to be comparable with vertical (Y) distances
            // The normalized coordinates are in [0,1] range for both X and Y
            // To get real-world proportions, we need to account for the actual pixel dimensions
            //
            // In portrait (imageHeight > imageWidth):
            //   - aspectRatio = imageHeight / imageWidth (e.g., 1920/1080 = 1.78)
            //   - X-axis covers fewer pixels, so X distances need to be scaled UP
            //   - shoulderWidthX * aspectRatio gives the width relative to image height
            //
            // In landscape (imageWidth > imageHeight):
            //   - aspectRatio = imageWidth / imageHeight (e.g., 1920/1080 = 1.78)
            //   - Y-axis covers fewer pixels, so Y distances would need scaling
            //   - But for X distances (widths), we DON'T scale them
            //   - shoulderWidthX is already in the correct proportion
            val shoulderWidthEstimated = if (isPortrait) {
                shoulderWidthX * aspectRatio
            } else {
                shoulderWidthX  // No scaling needed in landscape for X distances
            }
            val hipWidth = if (isPortrait) {
                hipWidthX * aspectRatio
            } else {
                hipWidthX  // No scaling needed in landscape for X distances
            }
            val kneeWidth = if (isPortrait) {
                kneeWidthX * aspectRatio
            } else {
                kneeWidthX  // No scaling needed in landscape for X distances
            }

            android.util.Log.d("LiftHelper_Calibration", "Width after aspect correction: shoulder=$shoulderWidthEstimated, hip=$hipWidth, knee=$kneeWidth")

            // Sanity check hip and knee widths (should be 5-25% of height for hip, 3-20% for knee)
            val hipWidthRatioCheck = hipWidth / totalHeightNorm
            val validatedHipWidth = if (hipWidthRatioCheck in 0.05f..0.25f) {
                hipWidth
            } else {
                val actualCm = (hipWidthRatioCheck * userHeight * 100).toInt()
                android.util.Log.w("LiftHelper_Calibration", "⚠️ Hip width ratio ($hipWidthRatioCheck) out of range, using default (0.19)")
                issues.add(MeasurementIssue(
                    measurement = "Hip Width",
                    issue = "Detected value unrealistic (${actualCm}cm, expected 15-45cm). Using standard ratio.",
                    usedFallback = true
                ))
                0.19f * totalHeightNorm
            }

            val kneeWidthRatioCheck = kneeWidth / totalHeightNorm
            val validatedKneeWidth = if (kneeWidthRatioCheck in 0.03f..0.20f) {
                kneeWidth
            } else {
                val actualCm = (kneeWidthRatioCheck * userHeight * 100).toInt()
                android.util.Log.w("LiftHelper_Calibration", "⚠️ Knee width ratio ($kneeWidthRatioCheck) out of range, using default (0.10)")
                issues.add(MeasurementIssue(
                    measurement = "Knee Width",
                    issue = "Detected value unrealistic (${actualCm}cm, expected 5-35cm). Using standard ratio.",
                    usedFallback = true
                ))
                0.10f * totalHeightNorm
            }

            // Use manual shoulder width if provided, otherwise use estimated
            val shoulderWidth = if (manualShoulderWidth != null) {
                // Convert manual shoulder width (in meters) to normalized units
                // by dividing by user height and multiplying by total height in normalized coords
                (manualShoulderWidth / userHeight) * totalHeightNorm
            } else {
                shoulderWidthEstimated
            }

            // Calculate arm segments if visible (with aspect ratio correction)
            // Left elbow (13) visibility check - FIXED: added parentheses for correct precedence
            val leftElbowVisible = (landmarks.getOrNull(13)?.visibility ?: 0f) >= 0.5f
            val leftWristVisible = (landmarks.getOrNull(15)?.visibility ?: 0f) >= 0.5f

            val upperArmLength = if (leftElbowVisible) {
                val dist = distance(
                    landmarks[11].x, landmarks[11].y,  // left shoulder
                    landmarks[13].x, landmarks[13].y,  // left elbow
                    aspectRatio,
                    isPortrait
                )
                val ratio = dist / totalHeightNorm
                android.util.Log.d("LiftHelper_Calibration", "Upper arm distance: $dist (normalized), totalHeight: $totalHeightNorm, ratio: $ratio")

                // Sanity check: upper arm should be 10-35% of height
                if (ratio in 0.10f..0.35f) {
                    dist
                } else {
                    val actualCm = (ratio * userHeight * 100).toInt()
                    android.util.Log.w("LiftHelper_Calibration", "⚠️ Upper arm ratio ($ratio) out of range, using default")
                    issues.add(MeasurementIssue(
                        measurement = "Upper Arm",
                        issue = "Detected value unrealistic (${actualCm}cm, expected 17-60cm). Using standard ratio.",
                        usedFallback = true
                    ))
                    0.19f * totalHeightNorm // fallback to standard ratio
                }
            } else {
                android.util.Log.d("LiftHelper_Calibration", "Left elbow not visible, using default upper arm ratio")
                issues.add(MeasurementIssue(
                    measurement = "Upper Arm",
                    issue = "Elbow not visible (visibility: ${landmarks.getOrNull(13)?.visibility ?: 0f}). Using standard ratio.",
                    usedFallback = true
                ))
                0.19f * totalHeightNorm // fallback to standard ratio
            }

            val forearmLength = if (leftElbowVisible && leftWristVisible) {
                val dist = distance(
                    landmarks[13].x, landmarks[13].y,  // left elbow
                    landmarks[15].x, landmarks[15].y,  // left wrist
                    aspectRatio,
                    isPortrait
                )
                val ratio = dist / totalHeightNorm
                android.util.Log.d("LiftHelper_Calibration", "Forearm distance: $dist (normalized), totalHeight: $totalHeightNorm, ratio: $ratio")

                // Sanity check: forearm should be 10-30% of height
                if (ratio in 0.10f..0.30f) {
                    dist
                } else {
                    val actualCm = (ratio * userHeight * 100).toInt()
                    android.util.Log.w("LiftHelper_Calibration", "⚠️ Forearm ratio ($ratio) out of range, using default")
                    issues.add(MeasurementIssue(
                        measurement = "Forearm",
                        issue = "Detected value unrealistic (${actualCm}cm, expected 17-52cm). Using standard ratio.",
                        usedFallback = true
                    ))
                    0.16f * totalHeightNorm // fallback to standard ratio
                }
            } else {
                val reason = when {
                    !leftElbowVisible && !leftWristVisible -> "Elbow and wrist not visible"
                    !leftElbowVisible -> "Elbow not visible"
                    else -> "Wrist not visible (visibility: ${landmarks.getOrNull(15)?.visibility ?: 0f})"
                }
                android.util.Log.d("LiftHelper_Calibration", "$reason, using default forearm ratio")
                issues.add(MeasurementIssue(
                    measurement = "Forearm",
                    issue = "$reason. Using standard ratio.",
                    usedFallback = true
                ))
                0.16f * totalHeightNorm // fallback to standard ratio
            }

            // Check camera alignment
            val alignmentChecker = com.lifthelper.vision.calculator.CameraAlignmentChecker()
            val alignmentResult = alignmentChecker.checkAlignment(poseResult, aspectRatio, isPortrait)

            // Determine capture quality based on body visibility and alignment
            val quality = when {
                // Poor alignment overrides other quality considerations
                alignmentResult.issue == com.lifthelper.vision.calculator.AlignmentIssue.TILTED_AND_SIDE ->
                    CalibrationQuality.FALLBACK
                alignmentResult.issue == com.lifthelper.vision.calculator.AlignmentIssue.SIDE_VIEW ->
                    CalibrationQuality.FALLBACK
                alignmentResult.issue == com.lifthelper.vision.calculator.AlignmentIssue.TILTED ->
                    CalibrationQuality.ESTIMATED  // Tilt can be tolerated better than side view
                // Good alignment - assess based on body visibility
                anklesVisible && totalHeightNorm > 0.5f -> CalibrationQuality.OPTIMAL  // Full body visible
                anklesVisible -> CalibrationQuality.ESTIMATED  // Full body but too close/far
                else -> CalibrationQuality.ESTIMATED  // Ankles estimated from knees
            }

            // Calculate ratios
            val shoulderWidthRatio = shoulderWidth / totalHeightNorm
            val torsoHeightRatio = torsoHeight / totalHeightNorm

            // Sanity check: shoulder width should be reasonable (10-40% of height)
            // Skip this check if using manual shoulder width (user input is trusted)
            if (manualShoulderWidth == null && (shoulderWidthRatio < 0.10f || shoulderWidthRatio > 0.40f)) {
                return null  // Likely bad pose detection or extreme angle
            }

            // Sanity check: torso should be 20-40% of height
            if (torsoHeightRatio < 0.15f || torsoHeightRatio > 0.45f) {
                return null  // Unrealistic proportions
            }

            return AnthropometricData(
                // Heights as ratios
                headHeightRatio = (headNeckHeight * 0.7f) / totalHeightNorm, // 70% for head, 30% for neck
                neckHeightRatio = (headNeckHeight * 0.3f) / totalHeightNorm,
                torsoHeightRatio = torsoHeight / totalHeightNorm,
                upperLegHeightRatio = upperLegHeight / totalHeightNorm,
                lowerLegHeightRatio = lowerLegHeight / totalHeightNorm,
                footHeightRatio = 0.04f, // Estimated (feet not reliably detected)

                // Widths as ratios (using validated values)
                shoulderWidthRatio = shoulderWidth / totalHeightNorm,
                hipWidthRatio = validatedHipWidth / totalHeightNorm,
                kneeWidthRatio = validatedKneeWidth / totalHeightNorm,

                // Limb lengths as ratios (already validated within calculation)
                upperArmLengthRatio = upperArmLength / totalHeightNorm,
                forearmLengthRatio = forearmLength / totalHeightNorm,
                thighLengthRatio = upperLegHeight / totalHeightNorm,
                shinLengthRatio = lowerLegHeight / totalHeightNorm,

                userWeight = userWeight,
                userHeight = userHeight,
                captureTimestamp = System.currentTimeMillis(),
                captureQuality = quality,
                alignmentMessage = if (alignmentResult.issue != com.lifthelper.vision.calculator.AlignmentIssue.NONE) {
                    alignmentResult.message
                } else null,
                measurementIssues = issues
            )
        }
    }

    // Convenience methods to get actual measurements in meters
    fun getHeadHeight(): Float = headHeightRatio * userHeight
    fun getNeckHeight(): Float = neckHeightRatio * userHeight
    fun getTorsoHeight(): Float = torsoHeightRatio * userHeight
    fun getUpperLegHeight(): Float = upperLegHeightRatio * userHeight
    fun getLowerLegHeight(): Float = lowerLegHeightRatio * userHeight
    fun getFootHeight(): Float = footHeightRatio * userHeight

    fun getShoulderWidth(): Float = shoulderWidthRatio * userHeight
    fun getHipWidth(): Float = hipWidthRatio * userHeight
    fun getKneeWidth(): Float = kneeWidthRatio * userHeight

    fun getUpperArmLength(): Float = upperArmLengthRatio * userHeight
    fun getForearmLength(): Float = forearmLengthRatio * userHeight
    fun getThighLength(): Float = thighLengthRatio * userHeight
    fun getShinLength(): Float = shinLengthRatio * userHeight

    /**
     * Get segment mass as percentage of total body weight
     * Based on Dempster's regression equations and Clauser et al. (1969)
     */
    fun getSegmentMass(segment: BodySegment): Float? {
        return userWeight?.let { weight ->
            weight * when (segment) {
                BodySegment.HEAD -> 0.081f           // 8.1%
                BodySegment.TRUNK -> 0.497f          // 49.7%
                BodySegment.UPPER_ARM -> 0.028f      // 2.8% per arm
                BodySegment.FOREARM -> 0.016f        // 1.6% per arm
                BodySegment.HAND -> 0.006f           // 0.6% per hand
                BodySegment.THIGH -> 0.100f          // 10.0% per thigh
                BodySegment.SHANK -> 0.0465f         // 4.65% per shank
                BodySegment.FOOT -> 0.0145f          // 1.45% per foot
            }
        }
    }

    /**
     * Get center of mass location for a segment (as ratio from proximal end)
     * Based on Winter (2009) biomechanics data
     */
    fun getSegmentCOMRatio(segment: BodySegment): Float {
        return when (segment) {
            BodySegment.HEAD -> 0.50f
            BodySegment.TRUNK -> 0.43f
            BodySegment.UPPER_ARM -> 0.436f
            BodySegment.FOREARM -> 0.430f
            BodySegment.HAND -> 0.506f
            BodySegment.THIGH -> 0.433f
            BodySegment.SHANK -> 0.433f
            BodySegment.FOOT -> 0.50f
        }
    }

    /**
     * Validate that ratios sum to approximately 1.0 (within tolerance)
     */
    fun isValid(): Boolean {
        val totalHeightRatio = headHeightRatio + neckHeightRatio + torsoHeightRatio +
                              upperLegHeightRatio + lowerLegHeightRatio + footHeightRatio
        return totalHeightRatio in 0.9f..1.1f // 10% tolerance
    }
}

/**
 * Body segments for biomechanical calculations
 */
@Serializable
enum class BodySegment {
    HEAD,
    TRUNK,      // Torso
    UPPER_ARM,  // Shoulder to elbow
    FOREARM,    // Elbow to wrist
    HAND,
    THIGH,      // Hip to knee
    SHANK,      // Knee to ankle (shin)
    FOOT
}
