package com.liftr.domain.vision

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Input and output format of the endcap detector (a YOLO model exported by
 * Ultralytics), kept free of any ML runtime so it can be unit-tested.
 *
 * - Input `[1, 3, size, size]` float, channels first (NCHW), RGB in 0..1, the
 *   frame letterboxed: scaled to fit keeping its aspect ratio, centered, and
 *   padded with gray 114/255.
 * - Output `[1, 5, anchors]` float: rows cx, cy, w, h normalized to the
 *   square input (fractions of [INPUT_SIZE], as Ultralytics exports TFLite;
 *   checked against the bundled model) and a 0..1 score. No NMS in the model.
 */
object EndcapModelCodec {

    const val INPUT_SIZE = 640
    const val DEFAULT_SCORE_THRESHOLD = 0.25f
    const val DEFAULT_IOU_THRESHOLD = 0.5f
    private const val MAX_DETECTIONS = 10
    private const val PAD_VALUE = 114f / 255f
    private const val OUTPUT_ROWS = 5

    /** How a [sourceWidth] x [sourceHeight] frame maps into the square model input. */
    class Letterbox(val sourceWidth: Int, val sourceHeight: Int, val size: Int = INPUT_SIZE) {
        val scale: Float = min(size.toFloat() / sourceWidth, size.toFloat() / sourceHeight)
        val scaledWidth: Int = (sourceWidth * scale).roundToInt()
        val scaledHeight: Int = (sourceHeight * scale).roundToInt()

        // Same rounding as Ultralytics LetterBox, so boxes map back exactly.
        val padLeft: Int = ((size - scaledWidth) / 2f - 0.1f).roundToInt()
        val padTop: Int = ((size - scaledHeight) / 2f - 0.1f).roundToInt()
    }

    /**
     * Writes the letterboxed frame into [out] (`3 * size * size` floats, NCHW),
     * resampling bilinearly with half-pixel centers like OpenCV's INTER_LINEAR.
     */
    fun fillInput(argb: IntArray, letterbox: Letterbox, out: FloatArray) {
        val size = letterbox.size
        val plane = size * size
        require(out.size >= 3 * plane) { "Input buffer too small: ${out.size}" }
        val srcW = letterbox.sourceWidth
        val srcH = letterbox.sourceHeight
        require(argb.size >= srcW * srcH) { "Pixel buffer too small: ${argb.size}" }

        out.fill(PAD_VALUE, 0, 3 * plane)
        val fx = srcW.toFloat() / letterbox.scaledWidth
        val fy = srcH.toFloat() / letterbox.scaledHeight
        for (dy in 0 until letterbox.scaledHeight) {
            val sy = ((dy + 0.5f) * fy - 0.5f).coerceIn(0f, (srcH - 1).toFloat())
            val y0 = sy.toInt()
            val y1 = min(y0 + 1, srcH - 1)
            val wy = sy - y0
            val outRow = (dy + letterbox.padTop) * size + letterbox.padLeft
            for (dx in 0 until letterbox.scaledWidth) {
                val sx = ((dx + 0.5f) * fx - 0.5f).coerceIn(0f, (srcW - 1).toFloat())
                val x0 = sx.toInt()
                val x1 = min(x0 + 1, srcW - 1)
                val wx = sx - x0
                val p00 = argb[y0 * srcW + x0]
                val p01 = argb[y0 * srcW + x1]
                val p10 = argb[y1 * srcW + x0]
                val p11 = argb[y1 * srcW + x1]
                val o = outRow + dx
                out[o] = bilinear(p00, p01, p10, p11, 16, wx, wy)
                out[plane + o] = bilinear(p00, p01, p10, p11, 8, wx, wy)
                out[2 * plane + o] = bilinear(p00, p01, p10, p11, 0, wx, wy)
            }
        }
    }

    private fun bilinear(p00: Int, p01: Int, p10: Int, p11: Int, shift: Int, wx: Float, wy: Float): Float {
        val c00 = (p00 shr shift) and 0xFF
        val c01 = (p01 shr shift) and 0xFF
        val c10 = (p10 shr shift) and 0xFF
        val c11 = (p11 shr shift) and 0xFF
        val top = c00 + (c01 - c00) * wx
        val bottom = c10 + (c11 - c10) * wx
        return (top + (bottom - top) * wy) / 255f
    }

    /**
     * Turns the raw model [output] (`[5][anchors]` flattened row by row) into
     * detections normalized to the original frame: score threshold, greedy
     * NMS, then the letterbox undone.
     */
    fun decode(
        output: FloatArray,
        letterbox: Letterbox,
        scoreThreshold: Float = DEFAULT_SCORE_THRESHOLD,
        iouThreshold: Float = DEFAULT_IOU_THRESHOLD
    ): List<EndcapDetection> {
        val anchors = output.size / OUTPUT_ROWS
        val s = letterbox.size.toFloat()
        val candidates = mutableListOf<Box>()
        for (i in 0 until anchors) {
            val score = output[4 * anchors + i]
            if (score < scoreThreshold) continue
            candidates += Box(
                output[i] * s, output[anchors + i] * s, output[2 * anchors + i] * s, output[3 * anchors + i] * s, score
            )
        }
        candidates.sortByDescending { it.score }

        val kept = mutableListOf<Box>()
        for (box in candidates) {
            if (kept.none { iou(it, box) > iouThreshold }) kept += box
            if (kept.size == MAX_DETECTIONS) break
        }

        val w = letterbox.sourceWidth.toFloat()
        val h = letterbox.sourceHeight.toFloat()
        return kept.map { box ->
            EndcapDetection(
                centerX = ((box.cx - letterbox.padLeft) / letterbox.scale / w).coerceIn(0f, 1f),
                centerY = ((box.cy - letterbox.padTop) / letterbox.scale / h).coerceIn(0f, 1f),
                width = box.w / letterbox.scale / w,
                height = box.h / letterbox.scale / h,
                score = box.score
            )
        }
    }

    private class Box(val cx: Float, val cy: Float, val w: Float, val h: Float, val score: Float)

    private fun iou(a: Box, b: Box): Float {
        val ix = min(a.cx + a.w / 2, b.cx + b.w / 2) - max(a.cx - a.w / 2, b.cx - b.w / 2)
        val iy = min(a.cy + a.h / 2, b.cy + b.h / 2) - max(a.cy - a.h / 2, b.cy - b.h / 2)
        if (ix <= 0f || iy <= 0f) return 0f
        val inter = ix * iy
        return inter / (a.w * a.h + b.w * b.h - inter)
    }
}
