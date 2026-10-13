package com.liftr.domain.model

data class CalibrationData(
    val pixelsPerMeter: Float,
    val userHeight: Float, // in meters - PRIMARY calibration metric (view-angle independent)
    val shoulderWidth: Float, // in meters - kept for backward compatibility, not used for calibration
    val anthropometricData: AnthropometricData? = null, // Detailed body measurements
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        // Default shoulder width (kept for backward compatibility)
        const val DEFAULT_SHOULDER_WIDTH = 0.45f // 45cm

        fun fromUserInput(heightCm: Float, shoulderWidthCm: Float? = null): CalibrationData {
            val heightMeters = heightCm / 100f
            val shoulderWidthMeters = (shoulderWidthCm ?: (DEFAULT_SHOULDER_WIDTH * 100f)) / 100f

            // Estimate pixels per meter based on typical camera setup
            // This will be refined during actual camera calibration
            val estimatedPixelsPerMeter = 1000f // Placeholder, will be calculated from pose detection

            return CalibrationData(
                pixelsPerMeter = estimatedPixelsPerMeter,
                userHeight = heightMeters,
                shoulderWidth = shoulderWidthMeters
            )
        }
    }
}
