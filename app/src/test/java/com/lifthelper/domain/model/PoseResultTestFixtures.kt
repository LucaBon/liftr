package com.lifthelper.domain.model

/**
 * Test fixtures for creating PoseResult instances with various configurations
 * Used for testing anthropometric measurements across different orientations and scenarios
 */
object PoseResultTestFixtures {

    /**
     * Create a PoseResult with a person standing in ideal frontal pose
     *
     * @param imageWidth Image width in pixels
     * @param imageHeight Image height in pixels
     * @param userHeightMeters Actual user height in meters (default 1.75m = 175cm)
     * @param isFrontCamera If true, mirrors the pose horizontally
     */
    fun createIdealFrontalPose(
        imageWidth: Int,
        imageHeight: Int,
        userHeightMeters: Float = 1.75f,
        isFrontCamera: Boolean = false
    ): PoseResult {
        val isPortrait = imageHeight > imageWidth

        // Define body proportions based on typical human anthropometry
        // All Y coordinates are normalized [0,1], with 0 at top
        val headTop = 0.05f  // Top of head near top of frame
        val nose = 0.10f
        val shoulders = 0.20f
        val hips = 0.50f
        val knees = 0.70f
        val ankles = 0.90f

        // X coordinates for body center (normalized [0,1])
        val centerX = 0.5f

        // Calculate total body height in normalized Y space
        val totalBodyHeightNorm = ankles - headTop  // 0.90 - 0.05 = 0.85

        // Shoulder width: typically 40-50cm, ~23% of height
        val shoulderWidthRatio = 0.23f
        // Hip width: typically 30-40cm, ~20% of height (adjusted to pass alignment check)
        // Note: Real ratio is ~18%, but alignment checker requires shoulder/hip ratio < 1.25
        val hipWidthRatio = 0.20f
        // Knee spacing: ~10% of height
        val kneeSpacingRatio = 0.10f

        // Calculate pixel dimensions for width calculations
        // In normalized space, we need to account for aspect ratio
        val aspectRatio = if (isPortrait) {
            imageHeight.toFloat() / imageWidth.toFloat()
        } else {
            imageWidth.toFloat() / imageHeight.toFloat()
        }

        // Width in normalized X coordinates
        // The width ratios are relative to body height, so we multiply by totalBodyHeightNorm
        // Then divide by aspect ratio in portrait to account for narrower X-axis
        val shoulderWidthX = if (isPortrait) {
            (shoulderWidthRatio * totalBodyHeightNorm) / aspectRatio
        } else {
            shoulderWidthRatio * totalBodyHeightNorm
        }
        val hipWidthX = if (isPortrait) {
            (hipWidthRatio * totalBodyHeightNorm) / aspectRatio
        } else {
            hipWidthRatio * totalBodyHeightNorm
        }
        val kneeSpacingX = if (isPortrait) {
            (kneeSpacingRatio * totalBodyHeightNorm) / aspectRatio
        } else {
            kneeSpacingRatio * totalBodyHeightNorm
        }

        // Create landmarks with high visibility
        val landmarks = mutableListOf<PoseKeypoint>()

        // Helper to mirror X coordinate for front camera
        fun mirrorX(x: Float) = if (isFrontCamera) 1f - x else x

        // 0: Nose
        landmarks.add(PoseKeypoint(mirrorX(centerX), nose, 0f, 0.99f))

        // 1-6: Face landmarks (eyes, ears) - optional for body measurements
        landmarks.add(PoseKeypoint(mirrorX(centerX - 0.02f), nose - 0.01f, 0f, 0.95f)) // left eye inner
        landmarks.add(PoseKeypoint(mirrorX(centerX - 0.03f), nose - 0.01f, 0f, 0.95f)) // left eye
        landmarks.add(PoseKeypoint(mirrorX(centerX - 0.04f), nose - 0.01f, 0f, 0.95f)) // left eye outer
        landmarks.add(PoseKeypoint(mirrorX(centerX + 0.02f), nose - 0.01f, 0f, 0.95f)) // right eye inner
        landmarks.add(PoseKeypoint(mirrorX(centerX + 0.03f), nose - 0.01f, 0f, 0.95f)) // right eye
        landmarks.add(PoseKeypoint(mirrorX(centerX + 0.04f), nose - 0.01f, 0f, 0.95f)) // right eye outer

        // 7-8: Ears
        landmarks.add(PoseKeypoint(mirrorX(centerX - 0.06f), nose, 0f, 0.90f)) // left ear
        landmarks.add(PoseKeypoint(mirrorX(centerX + 0.06f), nose, 0f, 0.90f)) // right ear

        // 9-10: Mouth
        landmarks.add(PoseKeypoint(mirrorX(centerX - 0.01f), nose + 0.03f, 0f, 0.85f)) // mouth left
        landmarks.add(PoseKeypoint(mirrorX(centerX + 0.01f), nose + 0.03f, 0f, 0.85f)) // mouth right

        // 11-12: Shoulders (CRITICAL)
        landmarks.add(PoseKeypoint(mirrorX(centerX - shoulderWidthX / 2), shoulders, 0f, 0.99f)) // left shoulder
        landmarks.add(PoseKeypoint(mirrorX(centerX + shoulderWidthX / 2), shoulders, 0f, 0.99f)) // right shoulder

        // 13-14: Elbows
        landmarks.add(PoseKeypoint(mirrorX(centerX - shoulderWidthX / 2), shoulders + 0.12f, 0f, 0.95f)) // left elbow
        landmarks.add(PoseKeypoint(mirrorX(centerX + shoulderWidthX / 2), shoulders + 0.12f, 0f, 0.95f)) // right elbow

        // 15-16: Wrists
        landmarks.add(PoseKeypoint(mirrorX(centerX - shoulderWidthX / 2), shoulders + 0.24f, 0f, 0.90f)) // left wrist
        landmarks.add(PoseKeypoint(mirrorX(centerX + shoulderWidthX / 2), shoulders + 0.24f, 0f, 0.90f)) // right wrist

        // 17-22: Hands (pinky, index, thumb) - not used for measurements
        for (i in 17..22) {
            landmarks.add(PoseKeypoint(mirrorX(centerX), shoulders + 0.26f, 0f, 0.70f))
        }

        // 23-24: Hips (CRITICAL)
        landmarks.add(PoseKeypoint(mirrorX(centerX - hipWidthX / 2), hips, 0f, 0.99f)) // left hip
        landmarks.add(PoseKeypoint(mirrorX(centerX + hipWidthX / 2), hips, 0f, 0.99f)) // right hip

        // 25-26: Knees (CRITICAL)
        landmarks.add(PoseKeypoint(mirrorX(centerX - kneeSpacingX / 2), knees, 0f, 0.99f)) // left knee
        landmarks.add(PoseKeypoint(mirrorX(centerX + kneeSpacingX / 2), knees, 0f, 0.99f)) // right knee

        // 27-28: Ankles (CRITICAL)
        landmarks.add(PoseKeypoint(mirrorX(centerX - kneeSpacingX / 2), ankles, 0f, 0.95f)) // left ankle
        landmarks.add(PoseKeypoint(mirrorX(centerX + kneeSpacingX / 2), ankles, 0f, 0.95f)) // right ankle

        // 29-32: Feet (heel, toe) - not used for measurements
        landmarks.add(PoseKeypoint(mirrorX(centerX - kneeSpacingX / 2), ankles + 0.03f, 0f, 0.85f)) // left heel
        landmarks.add(PoseKeypoint(mirrorX(centerX + kneeSpacingX / 2), ankles + 0.03f, 0f, 0.85f)) // right heel
        landmarks.add(PoseKeypoint(mirrorX(centerX - kneeSpacingX / 2), ankles + 0.05f, 0f, 0.80f)) // left toe
        landmarks.add(PoseKeypoint(mirrorX(centerX + kneeSpacingX / 2), ankles + 0.05f, 0f, 0.80f)) // right toe

        return PoseResult(
            landmarks = landmarks,
            timestamp = System.currentTimeMillis(),
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )
    }

    /**
     * Create a PoseResult with tilted camera (person appears tilted)
     */
    fun createTiltedPose(
        imageWidth: Int,
        imageHeight: Int,
        tiltAngleDegrees: Float = 15f
    ): PoseResult {
        val idealPose = createIdealFrontalPose(imageWidth, imageHeight)
        val tiltAngleRad = Math.toRadians(tiltAngleDegrees.toDouble()).toFloat()

        // Rotate landmarks around center point
        val centerX = 0.5f
        val centerY = 0.5f

        val rotatedLandmarks = idealPose.landmarks.map { landmark ->
            val dx = landmark.x - centerX
            val dy = landmark.y - centerY
            val cos = kotlin.math.cos(tiltAngleRad)
            val sin = kotlin.math.sin(tiltAngleRad)
            val newX = dx * cos - dy * sin + centerX
            val newY = dx * sin + dy * cos + centerY

            landmark.copy(x = newX, y = newY)
        }

        return idealPose.copy(landmarks = rotatedLandmarks)
    }

    /**
     * Create a PoseResult with side view (person turned sideways)
     */
    fun createSideViewPose(
        imageWidth: Int,
        imageHeight: Int
    ): PoseResult {
        val frontalPose = createIdealFrontalPose(imageWidth, imageHeight)

        // In side view, shoulders and hips appear much narrower
        val narrowedLandmarks = frontalPose.landmarks.mapIndexed { index, landmark ->
            when (index) {
                // Shoulders: reduce width to ~5% (from ~23%)
                11, 12 -> landmark.copy(x = 0.5f + (landmark.x - 0.5f) * 0.2f)
                // Hips: reduce width to ~3% (from ~18%)
                23, 24 -> landmark.copy(x = 0.5f + (landmark.x - 0.5f) * 0.15f)
                // Knees: reduce spacing
                25, 26 -> landmark.copy(x = 0.5f + (landmark.x - 0.5f) * 0.3f)
                else -> landmark
            }
        }

        return frontalPose.copy(landmarks = narrowedLandmarks)
    }

    /**
     * Create a PoseResult with missing lower body (only torso visible)
     */
    fun createPartialBodyPose(
        imageWidth: Int,
        imageHeight: Int
    ): PoseResult {
        val fullPose = createIdealFrontalPose(imageWidth, imageHeight)

        // Set low visibility for lower body landmarks
        val partialLandmarks = fullPose.landmarks.mapIndexed { index, landmark ->
            when (index) {
                // Knees, ankles, feet: not visible
                25, 26, 27, 28, 29, 30, 31, 32 -> landmark.copy(visibility = 0.1f)
                else -> landmark
            }
        }

        return fullPose.copy(landmarks = partialLandmarks)
    }

    // Common test dimensions

    /** Portrait mode: 1080x1920 (9:16 aspect ratio) */
    fun createPortraitPose(isFrontCamera: Boolean = false): PoseResult {
        return createIdealFrontalPose(1080, 1920, isFrontCamera = isFrontCamera)
    }

    /** Landscape mode: 1920x1080 (16:9 aspect ratio) */
    fun createLandscapePose(isFrontCamera: Boolean = false): PoseResult {
        return createIdealFrontalPose(1920, 1080, isFrontCamera = isFrontCamera)
    }

    /** Square image: 1080x1080 (1:1 aspect ratio) */
    fun createSquarePose(isFrontCamera: Boolean = false): PoseResult {
        return createIdealFrontalPose(1080, 1080, isFrontCamera = isFrontCamera)
    }

    /** Low resolution portrait: 480x640 */
    fun createLowResPortraitPose(isFrontCamera: Boolean = false): PoseResult {
        return createIdealFrontalPose(480, 640, isFrontCamera = isFrontCamera)
    }

    /** Low resolution landscape: 640x480 */
    fun createLowResLandscapePose(isFrontCamera: Boolean = false): PoseResult {
        return createIdealFrontalPose(640, 480, isFrontCamera = isFrontCamera)
    }
}
