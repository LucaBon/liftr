package com.liftr.domain.vision

/**
 * Finds one pixels-per-meter scale for a whole recorded set, from where the
 * lifter stands still. A sliding "tallest recent span" follows the walk from
 * the phone to the rack, and when that memory expires mid-set every height
 * shifts at once, so a rep seems never to come back to the top.
 *
 * Only frames whose scale holds steady for [STILL_WINDOW_US] count, which
 * leaves out walking. Among them, standing is the tallest level the lifter
 * stays at for a good part of the set: a long pause at the bottom can last
 * longer, but is shorter in the image.
 */
object StandingScale {

    data class Sample(val timestampUs: Long, val pixelsPerMeter: Float)

    data class Estimate(val pixelsPerMeter: Float, val frames: Int)

    const val STILL_WINDOW_US = 400_000L
    const val STILL_MAX_SPREAD = 1.03f
    const val BAND_SPREAD = 1.04f

    /** Fewest frames of one level that make it a place the lifter really stood. */
    const val MIN_FRAMES = 10

    /** A level needs this share of the most common level's frames to be the standing one. */
    const val MIN_SHARE_OF_COMMONEST = 0.25f

    /** @param samples in time order. @return null when the lifter never stayed still long enough. */
    fun estimate(samples: List<Sample>): Estimate? {
        val still = stillScales(samples).sorted()
        if (still.size < MIN_FRAMES) return null

        // For each start, how many sorted values fit in a band above it.
        var end = 0
        val counts = IntArray(still.size) { start ->
            if (end < start) end = start
            while (end + 1 < still.size && still[end + 1] <= still[start] * BAND_SPREAD) end++
            end - start + 1
        }
        val commonest = counts.max()
        if (commonest < MIN_FRAMES) return null
        val needed = maxOf(MIN_FRAMES, kotlin.math.ceil(commonest * MIN_SHARE_OF_COMMONEST).toInt())

        // Tallest qualifying level: its band reaches furthest up, so scan from the top.
        val start = still.indices.last { counts[it] >= needed }
        val band = still.subList(start, start + counts[start])
        return Estimate(band[band.size / 2], band.size)
    }

    private fun stillScales(samples: List<Sample>): List<Float> {
        val still = BooleanArray(samples.size)
        var from = 0
        for (to in samples.indices) {
            while (samples[to].timestampUs - samples[from].timestampUs > STILL_WINDOW_US) from++
            // A window needs to span nearly its full length to prove stillness.
            if (samples[to].timestampUs - samples[from].timestampUs < STILL_WINDOW_US * 3 / 4) continue
            val window = samples.subList(from, to + 1).map { it.pixelsPerMeter }
            if (window.max() <= window.min() * STILL_MAX_SPREAD) (from..to).forEach { still[it] = true }
        }
        return samples.indices.filter { still[it] }.map { samples[it].pixelsPerMeter }
    }
}
