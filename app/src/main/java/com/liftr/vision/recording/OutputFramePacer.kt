package com.liftr.vision.recording

/**
 * Maps source frames (real-time µs since the first frame) to output
 * presentation timestamps, decimating so the output never exceeds [maxFps].
 * Output PTS is relative to the first frame and strictly increasing, so the
 * encoded duration equals the source's real duration regardless of source
 * frame rate or slow-motion capture.
 */
class OutputFramePacer(maxFps: Int = MAX_OUTPUT_FPS) {
    // 5% slack so a 60 fps source with rounding jitter is not decimated.
    private val minIntervalUs = (1_000_000L / maxFps) * 95 / 100
    private var firstUs = -1L
    private var lastKeptUs = -1L

    /** PTS in µs for this frame, or null if it should be dropped. */
    fun ptsFor(sourceTimestampUs: Long): Long? {
        if (firstUs < 0) {
            firstUs = sourceTimestampUs
            lastKeptUs = 0
            return 0
        }
        val rel = sourceTimestampUs - firstUs
        if (rel - lastKeptUs < minIntervalUs) return null
        lastKeptUs = rel
        return rel
    }

    companion object {
        const val MAX_OUTPUT_FPS = 60

        /** Encoder frame-rate hint consistent with what the pacer outputs. */
        fun outputFps(sourceFps: Float, maxFps: Int = MAX_OUTPUT_FPS): Int =
            sourceFps.toInt().coerceIn(1, maxFps)
    }
}
