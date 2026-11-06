package com.liftr.domain.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndcapModelCodecTest {

    private val anchors = 8400

    /** Model output for [boxes] given as cx, cy, w, h in input pixels and a score. */
    private fun output(vararg boxes: FloatArray): FloatArray {
        val out = FloatArray(5 * anchors)
        boxes.forEachIndexed { i, box ->
            for (row in 0 until 4) out[row * anchors + i] = box[row] / EndcapModelCodec.INPUT_SIZE
            out[4 * anchors + i] = box[4]
        }
        return out
    }

    @Test
    fun `letterbox of a portrait frame pads left and right`() {
        val lb = EndcapModelCodec.Letterbox(1080, 1920)
        assertEquals(360, lb.scaledWidth)
        assertEquals(640, lb.scaledHeight)
        assertEquals(140, lb.padLeft)
        assertEquals(0, lb.padTop)
    }

    @Test
    fun `decode maps a box back to the original frame`() {
        val lb = EndcapModelCodec.Letterbox(1080, 1920)
        // Center of the scaled image: x = 140 + 180, y = 320.
        val detections = EndcapModelCodec.decode(output(floatArrayOf(320f, 320f, 36f, 64f, 0.6f)), lb)

        assertEquals(1, detections.size)
        val d = detections.single()
        assertEquals(0.5f, d.centerX, 1e-4f)
        assertEquals(0.5f, d.centerY, 1e-4f)
        assertEquals(0.1f, d.width, 1e-4f)
        assertEquals(0.1f, d.height, 1e-4f)
        assertEquals(0.6f, d.score, 1e-6f)
    }

    @Test
    fun `decode drops low scores and overlapping duplicates`() {
        val lb = EndcapModelCodec.Letterbox(640, 640)
        val detections = EndcapModelCodec.decode(
            output(
                floatArrayOf(100f, 100f, 20f, 20f, 0.7f),
                floatArrayOf(102f, 100f, 20f, 20f, 0.5f), // same endcap
                floatArrayOf(400f, 300f, 20f, 20f, 0.4f), // the other endcap
                floatArrayOf(500f, 500f, 20f, 20f, 0.1f)  // below threshold
            ),
            lb
        )

        assertEquals(listOf(0.7f, 0.4f), detections.map { it.score })
    }

    @Test
    fun `fillInput writes RGB planes and gray padding`() {
        // 2x4 portrait frame: pure red, so every sampled pixel is red.
        val red = 0xFFFF0000.toInt()
        val lb = EndcapModelCodec.Letterbox(2, 4, size = 8)
        val out = FloatArray(3 * 64)
        EndcapModelCodec.fillInput(IntArray(8) { red }, lb, out)

        assertEquals(4, lb.scaledWidth)
        assertEquals(2, lb.padLeft)
        val plane = 64
        val inside = 3 * 8 + 3 // row 3, column 3
        assertEquals(1f, out[inside], 1e-6f)
        assertEquals(0f, out[plane + inside], 1e-6f)
        assertEquals(0f, out[2 * plane + inside], 1e-6f)
        // Column 0 is padding in every channel.
        val pad = 114f / 255f
        assertTrue((0 until 3).all { c -> kotlin.math.abs(out[c * plane + 3 * 8] - pad) < 1e-6f })
    }
}
