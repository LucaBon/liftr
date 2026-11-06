package com.liftr.domain.vision

import java.io.Closeable

/**
 * One barbell endcap (the sleeve tip seen side-on) found in a frame.
 *
 * Coordinates are normalized to the original frame like pose landmarks:
 * x and [width] by the image width, y and [height] by the image height.
 */
data class EndcapDetection(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    /** Model confidence, 0..1. */
    val score: Float
)

/**
 * Finds barbell endcaps in decoded video frames.
 *
 * Not thread-safe and holds a native model: create one per analysis pass and
 * [close] it afterwards.
 */
interface EndcapDetector : Closeable {

    /** False when the model or its runtime could not be loaded; [detect] then returns nothing. */
    fun isAvailable(): Boolean

    /**
     * Endcaps in one frame.
     *
     * @param argb Packed ARGB pixels of the frame, row-major, [width] x [height]
     */
    fun detect(argb: IntArray, width: Int, height: Int): List<EndcapDetection>
}
