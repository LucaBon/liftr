package com.lifthelper.domain.model

/**
 * Tempo prescription in standard E-P1-C-P2 notation.
 *
 * @property eccentricSeconds Prescribed eccentric (lowering) duration
 * @property pauseBottomSeconds Prescribed pause at bottom position
 * @property concentricSeconds Prescribed concentric (lifting) duration. 0 = explosive (no target)
 * @property pauseTopSeconds Prescribed pause at top position
 */
data class TempoPrescription(
    val eccentricSeconds: Float,
    val pauseBottomSeconds: Float,
    val concentricSeconds: Float,
    val pauseTopSeconds: Float
) {
    val isExplosiveConcentric: Boolean get() = concentricSeconds == 0f
    val totalDuration: Float get() = eccentricSeconds + pauseBottomSeconds + concentricSeconds + pauseTopSeconds

    fun toDisplayString(): String {
        val c = if (isExplosiveConcentric) "X" else "%.0f".format(concentricSeconds)
        return "%.0f-%.0f-$c-%.0f".format(eccentricSeconds, pauseBottomSeconds, pauseTopSeconds)
    }

    companion object {
        val HYPERTROPHY = TempoPrescription(3f, 1f, 2f, 0f)
        val STRENGTH = TempoPrescription(2f, 0f, 1f, 0f)
        val SLOW = TempoPrescription(4f, 2f, 4f, 0f)
        val EXPLOSIVE = TempoPrescription(1f, 0f, 0f, 0f)
        val ISOMETRIC = TempoPrescription(2f, 5f, 2f, 0f)

        fun fromString(notation: String): TempoPrescription? {
            val parts = notation.split("-")
            if (parts.size != 4) return null
            return try {
                TempoPrescription(
                    eccentricSeconds = parts[0].toFloat(),
                    pauseBottomSeconds = parts[1].toFloat(),
                    concentricSeconds = if (parts[2].uppercase() == "X") 0f else parts[2].toFloat(),
                    pauseTopSeconds = parts[3].toFloat()
                )
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}
