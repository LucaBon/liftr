package com.liftr.domain.vision

import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import kotlin.math.abs

/**
 * Runs [PlateTracker] over real frames; skipped unless LIFTR_PLATE_FRAMES=<prefix>
 * points at `<prefix>.gray` (concatenated grayscale frames) and `<prefix>.txt`
 * (first line `w h fps`, then one timestamp in µs per frame); frames are 640 px on
 * the long side (640×360 landscape, 360×640 portrait), as VideoFilePoseSource makes them.
 *
 * Optional: LIFTR_PLATE_POSES (a pose dump with `poses[{i, lm}]`) for shoulder
 * hints, LIFTR_PLATE_REF (a reference track `{track[{t, c:[x,y,r]|null}]}`) to
 * compare with over 17–27 s, LIFTR_PLATE_OUT to write the track in that format.
 */
class PlateTrackerRealVideoTest {
    @Test
    fun trackRealVideo() {
        val prefix = System.getenv("LIFTR_PLATE_FRAMES")
        assumeTrue(prefix != null)
        val header = File("$prefix.txt").readLines()
        val (w, h) = header[0].split(" ").let { it[0].toInt() to it[1].toInt() }
        val fps = header[0].split(" ")[2].toDouble()
        val timesUs = header.drop(1).filter { it.isNotBlank() }.map { it.toLong() }

        val hints = System.getenv("LIFTR_PLATE_POSES")?.let { loadHints(File(it)) } ?: emptyMap()

        val tracker = PlateTracker()
        val frame = ByteArray(w * h)
        val track = ArrayList<Pair<Double, PlateObservation?>>()
        var lastAnalyzed = Long.MIN_VALUE / 2
        var nanos = 0L
        DataInputStream(File("$prefix.gray").inputStream().buffered(1 shl 20)).use { input ->
            for ((i, tUs) in timesUs.withIndex()) {
                input.readFully(frame)
                // Same cadence as AnalyzeVideoUseCase's lift pass: frames ≥ 30 ms apart.
                val tMs = tUs / 1000
                if (tMs - lastAnalyzed < 30) continue
                lastAnalyzed = tMs
                val start = System.nanoTime()
                val obs = tracker.track(frame, w, h, tMs, hints[i])
                nanos += System.nanoTime() - start
                track += tUs / 1e6 to obs
            }
        }
        val found = track.count { it.second != null }
        println("PlateTracker: ${track.size} frames, $found with plate, locked r=${tracker.lockedRadius}, " +
            "%.2f ms/frame".format(nanos / 1e6 / track.size))

        System.getenv("LIFTR_PLATE_OUT")?.let { out ->
            val rows = track.joinToString(",") { (t, o) ->
                val c = o?.let { "[${it.cx},${it.cy},${it.r}]" } ?: "null"
                "{\"t\":$t,\"c\":$c}"
            }
            File(out).writeText("{\"fps\":$fps,\"w\":$w,\"h\":$h,\"track\":[$rows]}")
        }

        val refPath = System.getenv("LIFTR_PLATE_REF") ?: return
        val ref = Json.parseToJsonElement(File(refPath).readText()).jsonObject["track"]!!.jsonArray.associate { row ->
            val o = row.jsonObject
            val c = o["c"]!!
            Math.round(o["t"]!!.jsonPrimitive.float * 1000.0) to
                if (c is JsonNull) null else c.jsonArray.map { it.jsonPrimitive.float }
        }
        val window = track.filter { it.first in 17.0..27.0 }
        var compared = 0
        var agree = 0
        var maxDev = 0f
        var maxDevT = 0.0
        val lost = window.count { it.second == null }
        for ((t, o) in window) {
            assertTrue("plate at y=${o?.cy} at $t s: the foreground stack", o == null || o.cy < 0.72f)
            val r = ref[Math.round(t * 1000)] ?: continue
            o ?: continue
            compared++
            val dev = abs(o.cy - r[1])
            if (dev <= 0.02f) agree++
            if (dev > maxDev) { maxDev = dev; maxDevT = t }
        }
        println("17-27 s: ${window.size} frames, lost ${"%.1f".format(100.0 * lost / window.size)}%, " +
            "compared $compared, |dy|<=2%: ${"%.1f".format(100.0 * agree / compared)}%, " +
            "max |dy| ${"%.4f".format(maxDev)} at ${"%.2f".format(maxDevT)} s")
        assertTrue(agree >= 0.9 * compared)
        assertTrue(lost <= 0.2 * window.size)
    }

    private fun loadHints(file: File): Map<Int, Pair<Float, Float>> {
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val width = root["width"]!!.jsonPrimitive.int
        val height = root["height"]!!.jsonPrimitive.int
        return root["poses"]!!.jsonArray.mapNotNull { row ->
            val o = row.jsonObject
            val landmarks = o["lm"]!!.jsonArray.map { p ->
                val a = p.jsonArray
                PoseKeypoint(a[0].jsonPrimitive.float, a[1].jsonPrimitive.float, a[2].jsonPrimitive.float, a[3].jsonPrimitive.float)
            }
            val pose = PoseResult(landmarks, o["tUs"]!!.jsonPrimitive.long / 1000, width, height, true)
            PlateTracker.shoulderHint(pose)?.let { o["i"]!!.jsonPrimitive.int to it }
        }.toMap()
    }
}
