package com.liftr.domain.exercise

import kotlin.math.abs

/**
 * Finds reps in a whole set's bar track (squat, bench press, deadlift) and
 * splits each into its phases (video path). Pure: positions in, reps out.
 *
 * Squat and bench press are [Direction.DOWN_FIRST]: the bar starts at the top,
 * is lowered (eccentric), may rest at the bottom (the squat's pause, the
 * bench's pause on the chest) and is driven up (concentric). A deadlift is
 * [Direction.UP_FIRST]: the bar starts on the floor, is pulled up (concentric),
 * held at lockout and lowered back (eccentric); see [Direction.UP_FIRST].
 *
 * The standing height is not the first frame: it is the level of the last
 * plateau (|v| under [PLATEAU_MAX_SPEED] for at least [PLATEAU_MIN_US]) before
 * each descent, so walking in, unracking and stepping back never shift a rep's
 * top, and the top is re-anchored for every rep.
 *
 * A rep leaves the top, drops at least the depth type's bar descent below it
 * and comes back within [RETURN_TOLERANCE]. The re-rack, which goes down and
 * stays, never completes one. A rep that crosses an unmeasured stretch (a long
 * tracking gap held flat, see [Sample.measured]) is dropped rather than timed
 * across the gap.
 */
object RepSegmenter {

    /**
     * One bar track sample: time, [height] in meters (up positive),
     * smoothed signed [velocity] (up positive), and whether the height is
     * measured or interpolated over a short gap (false for a held value).
     */
    data class Sample(val timestampUs: Long, val height: Float, val velocity: Float, val measured: Boolean = true)

    /**
     * One rep, as sample indices and its metrics.
     *
     * [pauseStart]..[concentricStart] are the samples within [PAUSE_BAND] of
     * the lowest point around [bottom] (all equal to it without a pause).
     * The ascent runs from the end of the pause to lockout at [concentricEnd].
     * The descent runs from [eccentricStart] to the start of a real pause (one
     * of at least [PLATEAU_MIN_US], the bar parked at the bottom), so descent,
     * pause and ascent tile the rep; a shorter one is the turnaround, and the
     * descent runs to the lowest point, [bottom], as velocity trackers time it.
     * Velocities are magnitudes in m/s. The means are the bar's travel over
     * the phase time: down to the lowest point, and up from it to lockout. That is [rangeOfMotion] over the
     * time when lockout is at the standing height; a bar that rises past it
     * (onto the toes, bar whip) is credited the extra travel it really made.
     */
    data class Rep(
        val eccentricStart: Int,
        val bottom: Int,
        val pauseStart: Int,
        val concentricStart: Int,
        val concentricEnd: Int,
        val standingHeight: Float,
        val rangeOfMotion: Float,
        val eccentricDurationS: Float,
        val pauseMs: Long,
        val concentricDurationS: Float,
        val meanEccentricVelocity: Float,
        val meanConcentricVelocity: Float,
        val peakConcentricVelocity: Float
    )

    /** Why a candidate that left the top was not counted as a rep. */
    enum class RejectionReason {
        /** Dipped below the top but stayed above the minimum descent, then came back. */
        TOO_SHALLOW,

        /** Dropped far enough but did not return to within [RETURN_TOLERANCE] of the top before the track ended. */
        NEVER_RETURNED,

        /** Went down and back, but crossed an unmeasured (held) stretch, so it cannot be timed. */
        CROSSED_GAP
    }

    /**
     * A candidate that left the top and was discarded: samples [startIndex]..
     * [endIndex] ([startUs]..[endUs]), the deepest travel it made below the
     * top, [depth] m, and for [RejectionReason.CROSSED_GAP] the longest
     * unmeasured stretch, [gapUs].
     */
    data class Rejection(
        val reason: RejectionReason,
        val startIndex: Int,
        val endIndex: Int,
        val startUs: Long,
        val endUs: Long,
        val depth: Float,
        val gapUs: Long = 0L
    )

    /** The reps of a track and the candidates that were left out. */
    data class Segmentation(val reps: List<Rep>, val rejections: List<Rejection>)

    /** Which way a rep's first movement goes. */
    enum class Direction {
        /** Down, then up: squat and bench press. [Rep.pauseMs] is the rest at the bottom. */
        DOWN_FIRST,

        /**
         * Up, then down: the deadlift, resting on the floor. The [Rep] is read
         * in time order: [Rep.eccentricStart] is where the bar leaves the floor,
         * [Rep.bottom] its highest point, [Rep.pauseStart]..[Rep.concentricStart]
         * the hold at lockout and [Rep.concentricEnd] where the lowering ends.
         * [Rep.meanConcentricVelocity], [Rep.peakConcentricVelocity] and
         * [Rep.concentricDurationS] describe the pull, the eccentric ones the
         * lowering, [Rep.rangeOfMotion] the travel from floor to lockout and
         * [Rep.pauseMs] the rest on the floor before the pull (a dead stop; 0
         * for the first rep, which has none to measure, and for touch-and-go).
         */
        UP_FIRST
    }

    /** Fastest a standing bar moves, m/s. */
    const val PLATEAU_MAX_SPEED = 0.05f

    /** Shortest standing plateau, µs. */
    const val PLATEAU_MIN_US = 300_000L

    /** How close to the standing height the bar must come back for a rep, m. */
    const val RETURN_TOLERANCE = 0.03f

    /** Band below the standing height that still counts as the top, m. */
    const val TOP_TOLERANCE = 0.02f

    /** Band above the lowest point that counts as the bottom pause, m. */
    const val PAUSE_BAND = 0.01f

    /** A dip below the top is reported as a shallow rejection from this share of the minimum descent. */
    private const val MIN_DIP_SHARE = 0.5f

    private class Plateau(val start: Int, val end: Int, val level: Float)

    /**
     * @param minDescent bar travel away from the resting height a rep needs
     *   (the squat depth type's `minBarDescent`, the bench's descent, the
     *   deadlift's pull), m
     */
    fun segment(samples: List<Sample>, minDescent: Float, direction: Direction = Direction.DOWN_FIRST): List<Rep> =
        segmentDetailed(samples, minDescent, direction).reps

    /** [segment], plus the candidates that left the top and were discarded, and why. */
    fun segmentDetailed(
        samples: List<Sample>,
        minDescent: Float,
        direction: Direction = Direction.DOWN_FIRST
    ): Segmentation {
        val rejections = mutableListOf<Rejection>()
        val reps = if (direction == Direction.UP_FIRST) {
            segmentUpFirst(samples, minDescent, rejections)
        } else segmentDownFirst(samples, minDescent, rejections)
        return Segmentation(reps, rejections)
    }

    /**
     * A pull is a descent of the negated track: its floor rest is the top
     * plateau, its pull the eccentric and its lowering the concentric. Mapped
     * back to physical phases, with the floor rest before each pull as pause.
     */
    private fun segmentUpFirst(samples: List<Sample>, minDescent: Float, rejections: MutableList<Rejection>): List<Rep> {
        val negated = samples.map { it.copy(height = -it.height, velocity = -it.velocity) }
        var previousEnd = -1
        return segmentDownFirst(negated, minDescent, rejections).map { r ->
            val restUs = if (previousEnd < 0) 0L else samples[r.eccentricStart].timestampUs - samples[previousEnd].timestampUs
            previousEnd = r.concentricEnd
            Rep(
                eccentricStart = r.eccentricStart,
                bottom = r.bottom,
                pauseStart = r.pauseStart,
                concentricStart = r.concentricStart,
                concentricEnd = r.concentricEnd,
                standingHeight = -r.standingHeight,
                rangeOfMotion = r.rangeOfMotion,
                eccentricDurationS = r.concentricDurationS,
                pauseMs = if (restUs >= PLATEAU_MIN_US) restUs / 1_000 else 0L,
                concentricDurationS = r.eccentricDurationS,
                meanEccentricVelocity = r.meanConcentricVelocity,
                meanConcentricVelocity = r.meanEccentricVelocity,
                peakConcentricVelocity = (r.eccentricStart..r.bottom).maxOf { samples[it].velocity }.coerceAtLeast(0f)
            )
        }
    }

    private fun segmentDownFirst(samples: List<Sample>, minDescent: Float, rejections: MutableList<Rejection>): List<Rep> {
        val plateaus = plateaus(samples)
        val reps = mutableListOf<Rep>()
        var from = 0
        // Plateaus starting before this index belong to an earlier rep (a long pause at its bottom).
        var plateausFrom = 0
        var standing: Plateau? = null
        // Diagnostics only: a dip below the top that never reached minDescent.
        var dipStart = -1
        var dipLow = 0f
        var dipUnmeasured = false
        fun closeDip(end: Int, top: Float) {
            if (dipStart >= 0 && top - dipLow >= minDescent * MIN_DIP_SHARE) {
                // A dip that crossed unmeasured samples never gets to be a candidate: it is no shallow one.
                rejections += Rejection(
                    if (dipUnmeasured) RejectionReason.CROSSED_GAP else RejectionReason.TOO_SHALLOW,
                    dipStart, end, samples[dipStart].timestampUs, samples[end].timestampUs, top - dipLow,
                    if (dipUnmeasured) longestGapUs(samples, dipStart, end) else 0L
                )
            }
            dipStart = -1
            dipUnmeasured = false
        }
        var i = 0
        while (i < samples.size) {
            plateaus.lastOrNull { it.end < i && it.start >= plateausFrom }?.let { standing = it }
            val top = standing?.level
            if (top != null && i >= from) {
                val h = samples[i].height
                if (h < top - TOP_TOLERANCE) {
                    if (dipStart < 0) { dipStart = i; dipLow = h } else dipLow = minOf(dipLow, h)
                    if (!samples[i].measured) dipUnmeasured = true
                } else closeDip(i, top)
            }
            if (top == null || i < from || !samples[i].measured || samples[i].height >= top - minDescent) {
                i++
                continue
            }
            dipStart = -1
            dipUnmeasured = false
            val descent = i
            val back = (descent until samples.size).firstOrNull { samples[it].height >= top - RETURN_TOLERANCE }
            if (back == null) {
                rejections += Rejection(
                    RejectionReason.NEVER_RETURNED, descent, samples.lastIndex, samples[descent].timestampUs,
                    samples.last().timestampUs, top - (descent until samples.size).minOf { samples[it].height }
                )
                break
            }
            val rep = measure(samples, top, (from - 1).coerceAtLeast(0), descent, back) { start, end ->
                rejections += Rejection(
                    RejectionReason.CROSSED_GAP, start, end, samples[start].timestampUs, samples[end].timestampUs,
                    top - (descent..back).minOf { samples[it].height }, longestGapUs(samples, start, end)
                )
            }
            if (rep != null) {
                reps += rep
                plateausFrom = rep.concentricStart
                from = rep.concentricEnd + 1
            } else {
                from = back + 1
            }
            i = from
        }
        return reps
    }

    /**
     * The rep through [descent] that is back near [top] at [back], starting no
     * earlier than [floor], or null when it crosses unmeasured samples (then
     * [onCrossedGap] gets the first and last sample index of the span).
     */
    private fun measure(
        samples: List<Sample>,
        top: Float,
        floor: Int,
        descent: Int,
        back: Int,
        onCrossedGap: (Int, Int) -> Unit
    ): Rep? {
        var eccentricStart = descent
        while (eccentricStart > floor && samples[eccentricStart].height < top - TOP_TOLERANCE) eccentricStart--
        val bottom = (descent..back).minBy { samples[it].height }
        val low = samples[bottom].height
        var pauseStart = bottom
        while (pauseStart > eccentricStart && samples[pauseStart - 1].height <= low + PAUSE_BAND) pauseStart--
        var concentricStart = bottom
        while (concentricStart < back && samples[concentricStart + 1].height <= low + PAUSE_BAND) concentricStart++
        // Lockout: back near the top, the bar stops rising. It may stop just
        // short of the top, or overshoot it (onto the toes, bar whip) first.
        val concentricEnd = (back until samples.size)
            .firstOrNull { samples[it].velocity < PLATEAU_MAX_SPEED || !samples[it].measured } ?: samples.lastIndex
        if ((eccentricStart..concentricEnd).any { !samples[it].measured }) {
            onCrossedGap(eccentricStart, concentricEnd)
            return null
        }

        fun t(index: Int) = samples[index].timestampUs
        // The bar is in the band from about halfway between the last sample
        // outside it and the first inside, to halfway past the last inside.
        val pauseFromUs = if (pauseStart == bottom) t(bottom) else (t(pauseStart - 1) + t(pauseStart)) / 2
        val pauseToUs = if (concentricStart == bottom) t(bottom) else (t(concentricStart) + t(concentricStart + 1)) / 2
        val pauseMs = if (pauseStart == concentricStart) 0L else (pauseToUs - pauseFromUs) / 1_000
        val eccentricToUs = if (pauseMs * 1_000 >= PLATEAU_MIN_US) pauseFromUs else t(bottom)
        val concentricFromUs = if (pauseMs == 0L) t(bottom) else pauseToUs

        val rangeOfMotion = top - low
        val eccentricS = (eccentricToUs - t(eccentricStart)) / 1_000_000f
        val concentricS = (t(concentricEnd) - concentricFromUs) / 1_000_000f
        return Rep(
            eccentricStart = eccentricStart,
            bottom = bottom,
            pauseStart = pauseStart,
            concentricStart = concentricStart,
            concentricEnd = concentricEnd,
            standingHeight = top,
            rangeOfMotion = rangeOfMotion,
            eccentricDurationS = eccentricS,
            pauseMs = pauseMs,
            concentricDurationS = concentricS,
            meanEccentricVelocity = if (eccentricS > 0f) (samples[eccentricStart].height - low) / eccentricS else 0f,
            meanConcentricVelocity = if (concentricS > 0f) (samples[concentricEnd].height - low) / concentricS else 0f,
            peakConcentricVelocity = (concentricStart..concentricEnd).maxOf { samples[it].velocity }.coerceAtLeast(0f)
        )
    }

    /** Longest run of unmeasured samples in [from]..[to], as the time between the measured samples around it, µs. */
    private fun longestGapUs(samples: List<Sample>, from: Int, to: Int): Long {
        var longest = 0L
        var i = from
        while (i <= to) {
            if (samples[i].measured) { i++; continue }
            val start = i
            while (i <= to && !samples[i].measured) i++
            val before = (start - 1).coerceAtLeast(0)
            val after = i.coerceAtMost(samples.lastIndex)
            longest = maxOf(longest, samples[after].timestampUs - samples[before].timestampUs)
        }
        return longest
    }

    /** Runs of measured samples slower than [PLATEAU_MAX_SPEED] lasting [PLATEAU_MIN_US], with their median height. */
    private fun plateaus(samples: List<Sample>): List<Plateau> {
        val found = mutableListOf<Plateau>()
        var start = -1
        for (i in 0..samples.size) {
            val still = i < samples.size && samples[i].measured && abs(samples[i].velocity) < PLATEAU_MAX_SPEED
            if (still) {
                if (start < 0) start = i
                continue
            }
            if (start >= 0 && samples[i - 1].timestampUs - samples[start].timestampUs >= PLATEAU_MIN_US) {
                val heights = (start until i).map { samples[it].height }.sorted()
                found += Plateau(start, i - 1, heights[heights.size / 2])
            }
            start = -1
        }
        return found
    }
}
