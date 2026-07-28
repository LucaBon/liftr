package com.lifthelper.domain.builder

import com.lifthelper.domain.calculator.CoordinateCalculator
import com.lifthelper.domain.extractor.BodySegmentExtractor
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.model.AnthropometricData
import com.lifthelper.domain.model.CalibrationQuality
import com.lifthelper.domain.model.MeasurementIssue
import com.lifthelper.domain.model.PoseResult
import com.lifthelper.domain.validation.MeasurementValidator
import com.lifthelper.domain.validation.PoseValidator
import com.lifthelper.domain.validation.ValidationResult
import com.lifthelper.vision.calculator.AlignmentIssue
import com.lifthelper.vision.calculator.CameraAlignmentChecker
import javax.inject.Inject

/**
 * Orchestrates the extraction of anthropometric data from pose landmarks.
 *
 * This class coordinates validation, extraction, and quality assessment
 * to build comprehensive anthropometric measurements from a single pose frame.
 *
 * @property poseValidator Validates pose landmark visibility
 * @property coordinateCalculator Handles coordinate transformations
 * @property segmentExtractor Extracts body segment measurements
 * @property measurementValidator Validates measurement values
 * @property alignmentChecker Checks camera alignment
 * @property logger Logging interface
 */
class AnthropometricDataBuilder @Inject constructor(
    private val poseValidator: PoseValidator,
    private val coordinateCalculator: CoordinateCalculator,
    private val segmentExtractor: BodySegmentExtractor,
    private val measurementValidator: MeasurementValidator,
    private val alignmentChecker: CameraAlignmentChecker,
    private val logger: Logger
) {

    /**
     * Builds anthropometric data from a pose result.
     *
     * @param poseResult Detected pose with landmarks
     * @param userHeight User's actual height in meters
     * @param userWeight User's weight in kg (optional)
     * @param manualShoulderWidth Manual shoulder width override in meters (optional)
     * @param isFrontCamera Whether front camera is being used (affects mirroring)
     * @return Result containing AnthropometricData or error
     */
    fun build(
        poseResult: PoseResult,
        userHeight: Float,
        userWeight: Float? = null,
        manualShoulderWidth: Float? = null,
        isFrontCamera: Boolean = false
    ): Result<AnthropometricData> = runCatching {
        // Both values are divisors below. A zero or negative height yields an
        // infinite/NaN shoulder-width ratio that is then persisted as
        // calibration and silently corrupts every later pixel→meter conversion.
        require(userHeight > 0f && userHeight.isFinite()) {
            "User height must be positive (was $userHeight)"
        }
        require(manualShoulderWidth == null ||
            (manualShoulderWidth > 0f && manualShoulderWidth.isFinite())) {
            "Manual shoulder width must be positive (was $manualShoulderWidth)"
        }

        val landmarks = poseResult.landmarks
        val imageWidth = poseResult.imageWidth
        val imageHeight = poseResult.imageHeight

        logger.d(TAG, "Starting anthropometric data extraction")

        // Step 1: Calculate aspect ratio
        val aspectRatioResult = coordinateCalculator.calculateAspectRatio(
            imageWidth,
            imageHeight
        )

        logger.d(TAG,
            "Image dimensions: ${imageWidth}x${imageHeight}, " +
                    "orientation: ${if (aspectRatioResult.isPortrait) "portrait" else "landscape"}, " +
                    "aspectRatio: ${aspectRatioResult.ratio}")

        // Step 2: Validate critical landmarks
        val validationResult = poseValidator.validateCriticalLandmarks(landmarks)
        if (validationResult is ValidationResult.Invalid) {
            logger.e(TAG, "Missing critical body landmarks: ${validationResult.landmarkNames} " +
                    "(visibility: ${validationResult.visibilities})")
            throw IllegalArgumentException(
                "Missing critical landmarks: ${validationResult.landmarkNames.joinToString()}"
            )
        }

        // Step 3: Validate head landmarks
        if (!poseValidator.validateHeadLandmarks(landmarks)) {
            logger.e(TAG, "No head landmarks visible")
            throw IllegalArgumentException("No head landmarks visible")
        }

        // Step 4: Check ankles visibility
        val anklesVisible = poseValidator.validateAnklesVisible(landmarks)

        // Step 5: Normalize Y-axis
        val normalizedLandmarks = coordinateCalculator.normalizeYAxis(landmarks)
        val isYAxisInverted = coordinateCalculator.detectYAxisInversion(landmarks)

        if (isYAxisInverted) {
            logger.w(TAG, "⚠️ Y-axis appears inverted - flipping coordinates")
        }

        // Step 6: Calculate head top position
        val visibleHeadLandmarks = poseValidator.getVisibleHeadLandmarks(landmarks)
        val headFaceY = coordinateCalculator.averageY(normalizedLandmarks, *visibleHeadLandmarks.toIntArray())
        val headTopY = coordinateCalculator.estimateHeadTop(headFaceY, isYAxisInverted)

        logger.d(TAG, "Estimated head top: $headTopY (face was $headFaceY)")

        // Step 7: Extract body heights
        val bodyHeights = segmentExtractor.extractHeights(
            normalizedLandmarks,
            aspectRatioResult.ratio,
            aspectRatioResult.isPortrait,
            headTopY,
            anklesVisible
        )

        // Validate total height
        if (bodyHeights.totalHeight <= 0) {
            logger.e(TAG, "❌ Invalid totalHeight: ${bodyHeights.totalHeight} (head should be above ankles)")
            throw IllegalArgumentException("Invalid total height: head should be above ankles")
        }

        // Log body size in frame
        val frameSizeResult = measurementValidator.validateBodySizeInFrame(
            bodyHeights.totalHeight,
            imageHeight
        )

        when (frameSizeResult.quality) {
            com.lifthelper.domain.validation.FrameSizeQuality.TOO_SMALL ->
                logger.w(TAG, "⚠️ Body occupies only ${frameSizeResult.percentage}% of frame " +
                        "(${frameSizeResult.pixels}px out of ${imageHeight}px)")
            com.lifthelper.domain.validation.FrameSizeQuality.OPTIMAL ->
                logger.i(TAG, "✅ Good body size: ${frameSizeResult.percentage}% of frame " +
                        "(${frameSizeResult.pixels}px out of ${imageHeight}px)")
            else ->
                logger.d(TAG, "Body size: ${frameSizeResult.percentage}% of frame " +
                        "(${frameSizeResult.pixels}px out of ${imageHeight}px)")
        }

        // Step 8: Calculate segment heights
        val segmentHeights = segmentExtractor.calculateSegmentHeights(
            bodyHeights,
            anklesVisible
        )

        logger.d(TAG, "Segment heights (normalized): " +
                "head+neck=${segmentHeights.headNeck}, " +
                "torso=${segmentHeights.torso}, " +
                "upperLeg=${segmentHeights.upperLeg}, " +
                "lowerLeg=${segmentHeights.lowerLeg}, " +
                "total=${bodyHeights.totalHeight}")

        // Validate segment heights
        if (!measurementValidator.validateSegmentHeights(
                segmentHeights.headNeck,
                segmentHeights.torso,
                segmentHeights.upperLeg,
                segmentHeights.lowerLeg,
                bodyHeights.totalHeight
            )) {
            throw IllegalArgumentException("Invalid pose data: segment heights are unrealistic")
        }

        // Step 9: Extract body widths
        val bodyWidths = segmentExtractor.extractWidths(
            normalizedLandmarks,
            aspectRatioResult.ratio,
            aspectRatioResult.isPortrait
        )

        logger.d(TAG, "Width after aspect correction: " +
                "shoulder=${bodyWidths.shoulders}, " +
                "hip=${bodyWidths.hips}, " +
                "knee=${bodyWidths.knees}")

        // Track measurement issues
        val issues = mutableListOf<MeasurementIssue>()

        // Add ankle issue if not visible
        if (!anklesVisible) {
            issues.add(MeasurementIssue(
                measurement = "Total Height / Lower Leg",
                issue = "Ankles not visible (visibility L:${landmarks.getOrNull(27)?.visibility ?: 0f}, " +
                        "R:${landmarks.getOrNull(28)?.visibility ?: 0f}). Height estimated from knees.",
                usedFallback = true
            ))
        }

        // Step 10: Validate and correct widths
        val hipValidation = measurementValidator.validateHipWidth(
            bodyWidths.hips,
            bodyHeights.totalHeight,
            userHeight
        )
        if (!hipValidation.isValid) {
            logger.w(TAG, "⚠️ ${hipValidation.issue?.issue}")
            hipValidation.issue?.let { issues.add(it) }
        }

        val kneeValidation = measurementValidator.validateKneeWidth(
            bodyWidths.knees,
            bodyHeights.totalHeight,
            userHeight
        )
        if (!kneeValidation.isValid) {
            logger.w(TAG, "⚠️ ${kneeValidation.issue?.issue}")
            kneeValidation.issue?.let { issues.add(it) }
        }

        // Step 11: Extract limb lengths
        val leftElbowVisible = poseValidator.validateLeftElbowVisible(normalizedLandmarks)
        val leftWristVisible = poseValidator.validateLeftWristVisible(normalizedLandmarks)

        val limbLengths = segmentExtractor.extractLimbLengths(
            normalizedLandmarks,
            aspectRatioResult.ratio,
            aspectRatioResult.isPortrait,
            bodyHeights.totalHeight,
            leftElbowVisible,
            leftWristVisible
        )

        // Add limb measurement issues
        if (limbLengths.upperArmUsedFallback) {
            issues.add(MeasurementIssue(
                measurement = "Upper Arm",
                issue = "Elbow not visible (visibility: ${landmarks.getOrNull(13)?.visibility ?: 0f}). " +
                        "Using standard ratio.",
                usedFallback = true
            ))
        }

        if (limbLengths.forearmUsedFallback) {
            val reason = when {
                !leftElbowVisible && !leftWristVisible -> "Elbow and wrist not visible"
                !leftElbowVisible -> "Elbow not visible"
                else -> "Wrist not visible (visibility: ${landmarks.getOrNull(15)?.visibility ?: 0f})"
            }
            issues.add(MeasurementIssue(
                measurement = "Forearm",
                issue = "$reason. Using standard ratio.",
                usedFallback = true
            ))
        }

        // Step 12: Validate limb lengths
        val upperArmValidation = measurementValidator.validateUpperArmLength(
            limbLengths.upperArm,
            bodyHeights.totalHeight,
            userHeight
        )
        if (!upperArmValidation.isValid) {
            logger.w(TAG, "⚠️ ${upperArmValidation.issue?.issue}")
            upperArmValidation.issue?.let { issues.add(it) }
        }

        val forearmValidation = measurementValidator.validateForearmLength(
            limbLengths.forearm,
            bodyHeights.totalHeight,
            userHeight
        )
        if (!forearmValidation.isValid) {
            logger.w(TAG, "⚠️ ${forearmValidation.issue?.issue}")
            forearmValidation.issue?.let { issues.add(it) }
        }

        // Step 13: Handle manual shoulder width
        val finalShoulderWidth = if (manualShoulderWidth != null) {
            // Convert manual shoulder width (in meters) to normalized units
            (manualShoulderWidth / userHeight) * bodyHeights.totalHeight
        } else {
            bodyWidths.shoulders
        }

        // Step 14: Check camera alignment
        val alignmentResult = alignmentChecker.checkAlignment(
            poseResult,
            aspectRatioResult.ratio,
            aspectRatioResult.isPortrait
        )

        // Step 15: Determine capture quality
        val quality = when {
            // Poor alignment overrides other quality considerations
            alignmentResult.issue == AlignmentIssue.TILTED_AND_SIDE ->
                CalibrationQuality.FALLBACK
            alignmentResult.issue == AlignmentIssue.SIDE_VIEW ->
                CalibrationQuality.FALLBACK
            alignmentResult.issue == AlignmentIssue.TILTED ->
                CalibrationQuality.ESTIMATED  // Tilt can be tolerated better than side view
            // Good alignment - assess based on body visibility
            anklesVisible && bodyHeights.totalHeight > 0.5f -> CalibrationQuality.OPTIMAL
            anklesVisible -> CalibrationQuality.ESTIMATED
            else -> CalibrationQuality.ESTIMATED
        }

        // Step 16: Final validations
        val shoulderWidthRatio = finalShoulderWidth / bodyHeights.totalHeight
        val torsoHeightRatio = segmentHeights.torso / bodyHeights.totalHeight

        // Sanity check: shoulder width should be reasonable (10-40% of height).
        // This applies to manual input too — a typo there is exactly as capable
        // of poisoning the calibration as a bad pose estimate, and the value is
        // rescaled by the detected height before use, so "trusting the user"
        // does not make the derived ratio trustworthy.
        if (!measurementValidator.validateShoulderWidthRange(
                finalShoulderWidth,
                bodyHeights.totalHeight
            )) {
            throw IllegalArgumentException(
                "Shoulder width ratio ($shoulderWidthRatio) out of range (0.10-0.40). " +
                        "Likely bad pose detection or extreme angle."
            )
        }

        // Sanity check: torso should be 15-45% of height
        if (!measurementValidator.validateTorsoHeightRange(
                segmentHeights.torso,
                bodyHeights.totalHeight
            )) {
            throw IllegalArgumentException(
                "Torso height ratio ($torsoHeightRatio) out of range (0.15-0.45). Unrealistic proportions."
            )
        }

        // Step 17: Build final AnthropometricData
        val result = AnthropometricData(
            // Heights as ratios
            headHeightRatio = (segmentHeights.headNeck * 0.7f) / bodyHeights.totalHeight, // 70% for head
            neckHeightRatio = (segmentHeights.headNeck * 0.3f) / bodyHeights.totalHeight, // 30% for neck
            torsoHeightRatio = segmentHeights.torso / bodyHeights.totalHeight,
            upperLegHeightRatio = segmentHeights.upperLeg / bodyHeights.totalHeight,
            lowerLegHeightRatio = segmentHeights.lowerLeg / bodyHeights.totalHeight,
            footHeightRatio = 0.04f, // Estimated (feet not reliably detected)

            // Widths as ratios (using validated values)
            shoulderWidthRatio = finalShoulderWidth / bodyHeights.totalHeight,
            hipWidthRatio = hipValidation.validatedValue / bodyHeights.totalHeight,
            kneeWidthRatio = kneeValidation.validatedValue / bodyHeights.totalHeight,

            // Limb lengths as ratios (using validated values)
            upperArmLengthRatio = upperArmValidation.validatedValue / bodyHeights.totalHeight,
            forearmLengthRatio = forearmValidation.validatedValue / bodyHeights.totalHeight,
            thighLengthRatio = segmentHeights.upperLeg / bodyHeights.totalHeight,
            shinLengthRatio = segmentHeights.lowerLeg / bodyHeights.totalHeight,

            userWeight = userWeight,
            userHeight = userHeight,
            captureTimestamp = System.currentTimeMillis(),
            captureQuality = quality,
            alignmentMessage = if (alignmentResult.issue != AlignmentIssue.NONE) {
                alignmentResult.message
            } else null,
            measurementIssues = issues
        )

        logger.i(TAG, "✅ Successfully extracted anthropometric data with quality: $quality")

        result
    }

    companion object {
        private const val TAG = "AnthropometricDataBuilder"
    }
}
