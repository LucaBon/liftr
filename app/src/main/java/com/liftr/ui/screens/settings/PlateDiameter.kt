package com.liftr.ui.screens.settings

import kotlin.math.roundToInt

/** Conversion and validation of the outer plate diameter setting (stored in meters, shown in cm). */
object PlateDiameter {
    const val MIN_CM = 20
    const val MAX_CM = 50

    /** Preset diameters offered in the selector, in cm. */
    val PRESETS_CM = listOf(45, 40, 35, 30)

    fun metersToCm(meters: Float): Int = (meters * 100f).roundToInt()

    fun cmToMeters(cm: Int): Float = cm / 100f

    fun isPreset(meters: Float): Boolean = metersToCm(meters) in PRESETS_CM

    /** Parses user text in cm; returns the diameter in meters, or null if not an integer in [MIN_CM, MAX_CM]. */
    fun parseCmToMeters(text: String): Float? {
        val cm = text.trim().toIntOrNull() ?: return null
        return if (cm in MIN_CM..MAX_CM) cmToMeters(cm) else null
    }
}
