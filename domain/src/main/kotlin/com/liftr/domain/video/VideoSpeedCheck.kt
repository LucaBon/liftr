package com.liftr.domain.video

/**
 * Cross-checks the video time base against gravity. A slow-motion clip read as
 * real time stretches every flight by the slow-motion factor, so the gravity
 * fitted to the jumps comes out 1/factor² of 9.81.
 */
object VideoSpeedCheck {
    private const val GRAVITY = 9.81
    private const val TOLERANCE = 0.10
    private val SLOWMO_FACTORS = listOf(2.0, 4.0, 8.0)

    /**
     * True when most of the accepted flights fit a gravity that implies time
     * stretched by 2, 4 or 8 (within 10%) while the time base said real time
     * ([timeScale] 1). A majority vote, so one bad flight can't decide it.
     */
    fun looksSlowMotion(fittedGravities: List<Float>, timeScale: Float): Boolean {
        if (timeScale != 1f || fittedGravities.isEmpty()) return false
        return fittedGravities.count(::impliesSlowMotion) * 2 > fittedGravities.size
    }

    private fun impliesSlowMotion(g: Float): Boolean {
        if (g <= 0f) return false
        val stretch = kotlin.math.sqrt(GRAVITY / g)
        return SLOWMO_FACTORS.any { kotlin.math.abs(stretch / it - 1) <= TOLERANCE }
    }
}
