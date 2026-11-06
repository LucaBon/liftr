package com.liftr.domain.exercise.strategy

/**
 * Each value replaced by the median of the values within [windowMs] / 2 of
 * it in time, so the smoothing is the same at any frame rate. A landmark
 * outlier on fewer than half the frames of a window is dropped, while steps
 * and steady trends pass through unchanged. [timesUs] must be ascending.
 */
internal fun rollingMedian(timesUs: List<Long>, values: List<Float>, windowMs: Long): List<Float> {
    require(timesUs.size == values.size)
    val halfUs = windowMs * 500
    var lo = 0
    var hi = 0
    return values.indices.map { i ->
        while (timesUs[i] - timesUs[lo] > halfUs) lo++
        if (hi < i) hi = i
        while (hi + 1 < values.size && timesUs[hi + 1] - timesUs[i] <= halfUs) hi++
        median(values.subList(lo, hi + 1))
    }
}

/** [rollingMedian] over the frames that have a value; the others stay null. */
internal fun rollingMedianOrNull(timesUs: List<Long>, values: List<Float?>, windowMs: Long): List<Float?> {
    val present = values.indices.filter { values[it] != null }
    val smoothed = rollingMedian(present.map { timesUs[it] }, present.map { values[it]!! }, windowMs)
    val result = MutableList<Float?>(values.size) { null }
    present.forEachIndexed { k, i -> result[i] = smoothed[k] }
    return result
}

/** Median of a non-empty list; the mean of the middle two for an even count. */
internal fun median(values: List<Float>): Float {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
}
