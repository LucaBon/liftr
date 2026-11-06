package com.liftr.domain.video

/**
 * Resolves how container timestamps map to real time.
 * Slow-motion files stretch PTS to ~30 fps playback: real = container / [timeScale].
 */
object VideoTimeBase {

    data class Result(
        val timeScale: Float,
        val captureFps: Float,
        val isVariableSpeed: Boolean,
        /** The file is (or declares itself) slow motion: its timestamps are stretched. */
        val isSlowMotion: Boolean = timeScale > 1f
    ) {
        /**
         * A slow-motion file whose speed changes can't be mapped to real time
         * with one scale. A real-time file whose frame rate varies (auto
         * exposure, an encoder hiccup) is fine: its timestamps are real time.
         */
        val rejectsSpeedChange: Boolean get() = isVariableSpeed && isSlowMotion

        /** Real time of a container time (both in microseconds from the first frame). */
        fun realTimeUs(containerUs: Long): Long =
            if (timeScale == 1f) containerUs else Math.round(containerUs / timeScale.toDouble())
    }

    private const val MIN_SLOWMO_SCALE = 1.5f
    private const val VARIABLE_RATIO = 1.8
    private const val WINDOW = 5

    fun resolve(
        containerFps: Float,
        knownCaptureFps: Int?,
        metadataCaptureFps: Float?,
        ptsDeltasUs: List<Long>
    ): Result {
        val captureFps = knownCaptureFps?.takeIf { it > 0 }?.toFloat()
            ?: metadataCaptureFps?.takeIf { it > 0f }
            ?: containerFps
        val ratio = if (containerFps > 0f) captureFps / containerFps else 1f
        val scale = if (ratio >= MIN_SLOWMO_SCALE) ratio else 1f
        val metadataSlowMo = containerFps > 0f && metadataCaptureFps != null &&
            metadataCaptureFps / containerFps >= MIN_SLOWMO_SCALE
        return Result(scale, captureFps, isVariableSpeed(ptsDeltasUs), scale > 1f || metadataSlowMo)
    }

    /** Median over sliding windows: a lone dropped frame or jitter never moves a window median. */
    private fun isVariableSpeed(deltasUs: List<Long>): Boolean {
        val d = deltasUs.filter { it > 0 }
        if (d.size < WINDOW * 2) return false
        val medians = (0..d.size - WINDOW).map { i -> d.subList(i, i + WINDOW).sorted()[WINDOW / 2].toDouble() }
        return medians.max() / medians.min() > VARIABLE_RATIO
    }
}
