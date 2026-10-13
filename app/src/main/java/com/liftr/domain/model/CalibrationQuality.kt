package com.liftr.domain.model

import kotlinx.serialization.Serializable

/**
 * Represents the quality of the current calibration based on body visibility
 */
@Serializable
enum class CalibrationQuality {
    /** Full body visible (head to ankles) - optimal calibration */
    OPTIMAL,

    /** Partial body visible (torso or legs only) - estimated calibration */
    ESTIMATED,

    /** No valid body segments detected - using stored fallback */
    FALLBACK,

    /** No calibration data available */
    NONE;

    fun getWarningMessage(): String? = when (this) {
        OPTIMAL -> null
        ESTIMATED -> "⚠️ Move back - only partial body visible. Distance measurements are estimated."
        FALLBACK -> "❌ Full body not detected. Please ensure your entire body is in frame for accurate measurements."
        NONE -> "❌ No calibration data. Please complete calibration in settings."
    }

    fun shouldShowWarning(): Boolean = this != OPTIMAL
}
