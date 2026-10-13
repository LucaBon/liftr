package com.liftr.domain.model

data class PoseKeypoint(
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float
) {
    companion object {
        /**
         * Minimum [visibility] for a landmark to count as valid. MediaPipe returns
         * 0.0-1.0; 0.5 balances detection sensitivity vs false positives.
         */
        const val VISIBILITY_THRESHOLD = 0.5f
    }
}

data class PoseResult(
    val landmarks: List<PoseKeypoint>,
    val timestamp: Long,
    val imageWidth: Int = 640,
    val imageHeight: Int = 480,
    val isPortrait: Boolean = false  // Track orientation for coordinate normalization
) {
    /**
     * Factor that converts a normalized x distance into image-height units.
     *
     * MediaPipe normalizes x by the image width and y by the image height, so
     * on a non-square frame the two axes have different units: in a 9:16
     * portrait frame a normalized dx is 16/9 times "longer" than the same
     * normalized dy. Multiply x (or dx) by this before combining it with y in
     * a distance, an angle or a conversion to meters.
     */
    val aspectScaleX: Float
        get() = if (imageWidth > 0 && imageHeight > 0) imageWidth.toFloat() / imageHeight else 1f

    companion object {
        // MediaPipe Pose landmark indices (Full set: 0-32)
        const val NOSE = 0
        const val LEFT_EYE_INNER = 1
        const val LEFT_EYE = 2
        const val LEFT_EYE_OUTER = 3
        const val RIGHT_EYE_INNER = 4
        const val RIGHT_EYE = 5
        const val RIGHT_EYE_OUTER = 6
        const val LEFT_EAR = 7
        const val RIGHT_EAR = 8
        const val MOUTH_LEFT = 9
        const val MOUTH_RIGHT = 10
        const val LEFT_SHOULDER = 11
        const val RIGHT_SHOULDER = 12
        const val LEFT_ELBOW = 13
        const val RIGHT_ELBOW = 14
        const val LEFT_WRIST = 15
        const val RIGHT_WRIST = 16
        const val LEFT_PINKY = 17
        const val RIGHT_PINKY = 18
        const val LEFT_INDEX = 19
        const val RIGHT_INDEX = 20
        const val LEFT_THUMB = 21
        const val RIGHT_THUMB = 22
        const val LEFT_HIP = 23
        const val RIGHT_HIP = 24
        const val LEFT_KNEE = 25
        const val RIGHT_KNEE = 26
        const val LEFT_ANKLE = 27
        const val RIGHT_ANKLE = 28
        const val LEFT_HEEL = 29
        const val RIGHT_HEEL = 30
        const val LEFT_FOOT_INDEX = 31
        const val RIGHT_FOOT_INDEX = 32

        /**
         * Standard 17 landmarks used for:
         * - Anthropometric calibration
         * - Pose data recording
         * - 3D skeleton visualization
         *
         * Storage: ~25.5 KB per 10-second set (2 bytes per coordinate, no z)
         * Provides complete skeleton structure for analysis and visualization
         */
        val STANDARD_RECORDING_LANDMARKS = listOf(
            NOSE,                           // 0 - Head reference
            LEFT_EAR, RIGHT_EAR,           // 7, 8 - Head orientation
            LEFT_SHOULDER, RIGHT_SHOULDER, // 11, 12 - Torso top
            LEFT_ELBOW, RIGHT_ELBOW,       // 13, 14 - Arm joints
            LEFT_WRIST, RIGHT_WRIST,       // 15, 16 - Arm endpoints
            LEFT_INDEX, RIGHT_INDEX,       // 19, 20 - Hand reference
            LEFT_HIP, RIGHT_HIP,           // 23, 24 - Torso bottom
            LEFT_KNEE, RIGHT_KNEE,         // 25, 26 - Leg joints
            LEFT_ANKLE, RIGHT_ANKLE        // 27, 28 - Leg endpoints
        )

        /**
         * Minimal 9 landmarks for basic velocity tracking only.
         * Use STANDARD_RECORDING_LANDMARKS instead for full feature support.
         */
        val MINIMAL_VELOCITY_LANDMARKS = listOf(
            NOSE,                           // 0 - Head
            LEFT_SHOULDER, RIGHT_SHOULDER, // 11, 12 - Shoulders (bar tracking)
            LEFT_WRIST, RIGHT_WRIST,       // 15, 16 - Hands (bar tracking)
            LEFT_HIP, RIGHT_HIP,           // 23, 24 - Hips (center of mass)
            LEFT_ANKLE, RIGHT_ANKLE        // 27, 28 - Feet (jump detection)
        )
    }
}
