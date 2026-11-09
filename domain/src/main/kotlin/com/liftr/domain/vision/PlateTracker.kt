package com.liftr.domain.vision

import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The barbell plate in one frame: center and radius, normalized like pose
 * landmarks ([cx] and [r] by the frame width, [cy] by the frame height).
 * [confidence] is the share of the circle's rim backed by an image edge (0–1).
 */
data class PlateObservation(val cx: Float, val cy: Float, val r: Float, val confidence: Float)

/**
 * Follows the barbell plate in a side-on video, frame by frame.
 *
 * Side-on, the plate hides the lifter's shoulders, so landmarks can't follow the
 * bar. The plate is a circle and stays one: a gradient circle Hough finds it.
 * Edge pixels vote for centers along their gradient, the strongest centers are
 * scored by how much of their rim is backed by a radial edge, and the best wins.
 *
 * - **Seeding**: while unlocked, only circles centered near the shoulder hint
 *   (a standing pose; side-on, the bar end sits at the shoulders) are taken.
 *   Once [FRAMES_TO_LOCK] consecutive seeds agree on position and radius, the
 *   radius locks to their median.
 * - **Tracking**: once locked, only circles within ±10% of that radius whose
 *   center moved no faster than a physical cap from the last one count. A
 *   miss leaves a gap; after [RELOCK_AFTER_MS] without the plate, seeding
 *   starts over.
 *
 * Stateful: use one instance per analysis pass, with increasing timestamps,
 * or call [reset]. Not thread-safe. Frames are ~360 px wide grayscale.
 */
class PlateTracker {

    private class Circle(val x: Float, val y: Float, val r: Float, val support: Float)

    private var lockedRadiusPx: Float? = null
    private var last: Circle? = null
    private var lastMs = 0L
    private val seeds = ArrayList<Circle>()
    private var seedMs = 0L

    // Per-frame buffers, reused while the frame size stays the same.
    private var bw = 0
    private var bh = 0
    private var blurTmp = FloatArray(0)
    private var blur = FloatArray(0)
    private var gx = FloatArray(0)
    private var gy = FloatArray(0)
    private var mag = FloatArray(0)
    private var edge = BooleanArray(0)

    // Box the edges were computed in for the current frame.
    private var ex0 = 0
    private var ey0 = 0
    private var ex1 = 0
    private var ey1 = 0

    /** Locked plate radius as a fraction of the frame width, or null while seeding. */
    val lockedRadius: Float? get() = lockedRadiusPx?.let { it / bw }

    /** Forgets the plate: the next frames seed again. */
    fun reset() {
        lockedRadiusPx = null
        last = null
        seeds.clear()
    }

    /**
     * Finds the plate in one frame.
     *
     * @param gray luma, row-major, [width] × [height]
     * @param shoulderHint normalized (x, y) of the shoulder on a standing pose,
     *   or null; only used while seeding, which needs it
     */
    fun track(
        gray: ByteArray,
        width: Int,
        height: Int,
        timestampMs: Long,
        shoulderHint: Pair<Float, Float>? = null
    ): PlateObservation? {
        require(gray.size >= width * height) { "gray has ${gray.size} bytes for $width x $height" }
        if (width != bw || height != bh) {
            reset()
            allocate(width, height)
        }
        val locked = lockedRadiusPx
        val prev = last
        if (locked != null && prev != null && timestampMs - lastMs > RELOCK_AFTER_MS) {
            reset()
            return track(gray, width, height, timestampMs, shoulderHint)
        }
        val found = if (locked != null && prev != null) {
            trackLocked(gray, locked, prev, timestampMs)
        } else {
            seed(gray, shoulderHint ?: return null, timestampMs)
        } ?: return null
        last = found
        lastMs = timestampMs
        return PlateObservation(found.x / bw, found.y / bh, found.r / bw, found.support)
    }

    private fun trackLocked(gray: ByteArray, radius: Float, prev: Circle, timestampMs: Long): Circle? {
        val maxMove = maxMovePx(radius, timestampMs - lastMs)
        val rMin = radius * (1 - RADIUS_TOLERANCE)
        val rMax = radius * (1 + RADIUS_TOLERANCE)
        val best = search(gray, prev.x, prev.y, maxMove, rMin, rMax) ?: return null
        return best.takeIf { it.support >= MIN_TRACK_SUPPORT }
    }

    private fun seed(gray: ByteArray, hint: Pair<Float, Float>, timestampMs: Long): Circle? {
        val hx = hint.first * bw
        val hy = hint.second * bh
        val rMin = MIN_RADIUS * bw
        val rMax = MAX_RADIUS * bw
        // The seed area spans from SEED_BELOW_SHOULDER radii below the shoulder
        // to SEED_ABOVE_SHOULDER radii above it; search around its middle.
        val midRadii = (SEED_ABOVE_SHOULDER - SEED_BELOW_SHOULDER) / 2
        val reach = rMax * hypot(SEED_BESIDE_SHOULDER, (SEED_ABOVE_SHOULDER + SEED_BELOW_SHOULDER) / 2)
        val found = search(gray, hx, hy - midRadii * rMax, reach, rMin, rMax)
            ?.takeIf {
                it.support >= MIN_SEED_SUPPORT &&
                    abs(it.x - hx) <= SEED_BESIDE_SHOULDER * it.r &&
                    it.y - hy in -SEED_ABOVE_SHOULDER * it.r..SEED_BELOW_SHOULDER * it.r
            }
            ?: return null
        val previous = seeds.lastOrNull()
        val consistent = previous != null &&
            hypot(found.x - previous.x, found.y - previous.y) <= maxMovePx(previous.r, timestampMs - seedMs) &&
            abs(found.r - medianRadius()) <= RADIUS_TOLERANCE * medianRadius()
        if (!consistent) seeds.clear()
        seeds.add(found)
        seedMs = timestampMs
        if (seeds.size >= FRAMES_TO_LOCK) {
            lockedRadiusPx = medianRadius()
            seeds.clear()
        }
        return found
    }

    private fun medianRadius(): Float = seeds.map { it.r }.sorted()[seeds.size / 2]

    /** Largest center shift allowed after [elapsedMs]: the physical speed cap plus detection noise. */
    private fun maxMovePx(radius: Float, elapsedMs: Long): Float {
        val radii = MAX_SPEED_RADII_PER_S * elapsedMs.coerceAtLeast(0) / 1000f + JUMP_SLACK_RADII
        return radius * min(radii, MAX_SEARCH_RADII)
    }

    /**
     * Best circle centered within [reach] of ([cx], [cy]) with a radius in
     * [rMin]..[rMax], or null. Candidates farther from that point score a
     * little lower, so a near circle wins a tie.
     */
    private fun search(gray: ByteArray, cx: Float, cy: Float, reach: Float, rMin: Float, rMax: Float): Circle? {
        val cx0 = max(0, (cx - reach).toInt())
        val cy0 = max(0, (cy - reach).toInt())
        val cx1 = min(bw, (cx + reach).toInt() + 1)
        val cy1 = min(bh, (cy + reach).toInt() + 1)
        if (cx0 >= cx1 || cy0 >= cy1) return null
        val pad = rMax.toInt() + 3
        computeEdges(gray, max(1, cx0 - pad), max(1, cy0 - pad), min(bw - 1, cx1 + pad), min(bh - 1, cy1 + pad))

        // Votes: each edge pixel votes along its gradient, both ways, at every radius.
        val aw = cx1 - cx0
        val ah = cy1 - cy0
        val acc = IntArray(aw * ah)
        val r0 = rMin.toInt()
        val r1 = rMax.roundToInt()
        for (y in ey0 until ey1) {
            for (x in ex0 until ex1) {
                val i = y * bw + x
                if (!edge[i]) continue
                val ux = gx[i] / mag[i]
                val uy = gy[i] / mag[i]
                for (r in r0..r1) {
                    val dx = ux * r
                    val dy = uy * r
                    var ax = (x + dx).roundToInt() - cx0
                    var ay = (y + dy).roundToInt() - cy0
                    if (ax in 0 until aw && ay in 0 until ah) acc[ay * aw + ax]++
                    ax = (x - dx).roundToInt() - cx0
                    ay = (y - dy).roundToInt() - cy0
                    if (ax in 0 until aw && ay in 0 until ah) acc[ay * aw + ax]++
                }
            }
        }

        // Peaks of the 3×3-summed votes, strongest first, kept apart.
        val smooth = IntArray(aw * ah)
        for (y in 1 until ah - 1) for (x in 1 until aw - 1) {
            var s = 0
            for (oy in -1..1) for (ox in -1..1) s += acc[(y + oy) * aw + x + ox]
            smooth[y * aw + x] = s
        }
        val order = (0 until aw * ah).filter { i ->
            val v = smooth[i]
            val x = i % aw
            val y = i / aw
            v > 0 && x in 1 until aw - 1 && y in 1 until ah - 1 &&
                v >= smooth[i - 1] && v >= smooth[i + 1] && v >= smooth[i - aw] && v >= smooth[i + aw]
        }.sortedByDescending { smooth[it] }
        val minApart = max(3f, rMin / 4)
        val peaks = ArrayList<Int>()
        for (i in order) {
            if (peaks.size >= MAX_CANDIDATES) break
            val px = i % aw
            val py = i / aw
            if (peaks.none { hypot((it % aw - px).toFloat(), (it / aw - py).toFloat()) < minApart }) peaks.add(i)
        }

        var best: Circle? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for (p in peaks) {
            val circle = fitRadius((p % aw + cx0).toFloat(), (p / aw + cy0).toFloat(), r0, r1)
            val dist = hypot(circle.x - cx, circle.y - cy)
            if (dist > reach) continue
            val score = circle.support - DISTANCE_PENALTY * dist / reach
            if (score > bestScore) {
                bestScore = score
                best = circle
            }
        }
        return best
    }

    /** The radius and the center (within 1 px) whose rim has the most edge support. */
    private fun fitRadius(x: Float, y: Float, r0: Int, r1: Int): Circle {
        var best = Circle(x, y, r0.toFloat(), 0f)
        for (oy in -1..1) for (ox in -1..1) for (r in r0..r1) {
            val s = support(x + ox, y + oy, r.toFloat())
            if (s > best.support || (s == best.support && r > best.r)) best = Circle(x + ox, y + oy, r.toFloat(), s)
        }
        return best
    }

    /** Share of rim samples with an edge, within 1 px radially, whose gradient points along the radius. */
    private fun support(cx: Float, cy: Float, r: Float): Float {
        var hits = 0
        for (k in 0 until RIM_SAMPLES) {
            val ux = COS[k]
            val uy = SIN[k]
            for (d in -1..1) {
                val px = (cx + (r + d) * ux).roundToInt()
                val py = (cy + (r + d) * uy).roundToInt()
                if (px < ex0 || px >= ex1 || py < ey0 || py >= ey1) continue
                val i = py * bw + px
                if (edge[i] && abs(gx[i] * ux + gy[i] * uy) >= RADIAL_COS * mag[i]) {
                    hits++
                    break
                }
            }
        }
        return hits.toFloat() / RIM_SAMPLES
    }

    private fun allocate(width: Int, height: Int) {
        bw = width
        bh = height
        val n = width * height
        blurTmp = FloatArray(n)
        blur = FloatArray(n)
        gx = FloatArray(n)
        gy = FloatArray(n)
        mag = FloatArray(n)
        edge = BooleanArray(n)
    }

    /**
     * Thin edges in [x0]..[x1) × [y0]..[y1) (inside the 1 px border): 5×5 box
     * blur, Sobel gradients, then non-maximum suppression across the edge.
     */
    private fun computeEdges(gray: ByteArray, x0: Int, y0: Int, x1: Int, y1: Int) {
        ex0 = x0; ey0 = y0; ex1 = x1; ey1 = y1
        // Blur rows/columns one wider than the box on each side for Sobel and NMS.
        val bx0 = max(0, x0 - 2)
        val bx1 = min(bw, x1 + 2)
        val by0 = max(0, y0 - 2)
        val by1 = min(bh, y1 + 2)
        for (y in max(0, by0 - BLUR_R) until min(bh, by1 + BLUR_R)) {
            val row = y * bw
            for (x in bx0 until bx1) {
                var s = 0
                for (k in -BLUR_R..BLUR_R) s += gray[row + (x + k).coerceIn(0, bw - 1)].toInt() and 0xFF
                blurTmp[row + x] = s.toFloat()
            }
        }
        val norm = 1f / ((2 * BLUR_R + 1) * (2 * BLUR_R + 1))
        for (y in by0 until by1) for (x in bx0 until bx1) {
            var s = 0f
            for (k in -BLUR_R..BLUR_R) s += blurTmp[(y + k).coerceIn(0, bh - 1) * bw + x]
            blur[y * bw + x] = s * norm
        }
        val gx0 = max(1, x0 - 1)
        val gx1 = min(bw - 1, x1 + 1)
        val gy0 = max(1, y0 - 1)
        val gy1 = min(bh - 1, y1 + 1)
        for (y in gy0 until gy1) for (x in gx0 until gx1) {
            val i = y * bw + x
            val a = blur[i - bw - 1]
            val b = blur[i - bw]
            val c = blur[i - bw + 1]
            val d = blur[i - 1]
            val f = blur[i + 1]
            val g = blur[i + bw - 1]
            val h = blur[i + bw]
            val k = blur[i + bw + 1]
            val sx = (c + 2 * f + k) - (a + 2 * d + g)
            val sy = (g + 2 * h + k) - (a + 2 * b + c)
            gx[i] = sx
            gy[i] = sy
            mag[i] = sqrt(sx * sx + sy * sy)
        }
        for (y in y0 until y1) for (x in x0 until x1) {
            val i = y * bw + x
            val m = mag[i]
            if (m < EDGE_THRESHOLD || x == gx0 || x == gx1 - 1 || y == gy0 || y == gy1 - 1) {
                edge[i] = false
                continue
            }
            // Neighbors across the edge, along the gradient quantized to 45°.
            val ax = abs(gx[i])
            val ay = abs(gy[i])
            val step = when {
                ay <= ax * TAN_22_5 -> 1
                ax <= ay * TAN_22_5 -> bw
                (gx[i] > 0) == (gy[i] > 0) -> bw + 1
                else -> bw - 1
            }
            edge[i] = m >= mag[i - step] && m > mag[i + step]
        }
    }

    companion object {
        /** Plate radius range while seeding, as a fraction of the frame width. */
        const val MIN_RADIUS = 0.12f
        const val MAX_RADIUS = 0.30f

        /**
         * Seeds must be centered near the shoulder hint, in plate radii: the bar
         * sits on or above the shoulders, and side-on the plate hides them, so
         * the pose model tends to place them low.
         */
        const val SEED_BESIDE_SHOULDER = 0.8f
        const val SEED_ABOVE_SHOULDER = 1.8f
        const val SEED_BELOW_SHOULDER = 0.6f

        /** Consecutive consistent seeds that lock the radius. */
        const val FRAMES_TO_LOCK = 10

        /** Allowed radius change around the locked radius (and between seeds). */
        const val RADIUS_TOLERANCE = 0.10f

        /** Speed cap, in plate radii per second: 3 m/s for a 45 cm plate. */
        const val MAX_SPEED_RADII_PER_S = 3f / 0.225f

        /** Center shift always allowed, in radii, for detection noise. */
        const val JUMP_SLACK_RADII = 0.25f

        /** Search reach never exceeds this many radii, however long the gap. */
        const val MAX_SEARCH_RADII = 2f

        /** A plate missing this long is searched for from scratch. */
        const val RELOCK_AFTER_MS = 1500L

        private const val MIN_SEED_SUPPORT = 0.35f
        private const val MIN_TRACK_SUPPORT = 0.25f
        private const val DISTANCE_PENALTY = 0.15f
        private const val MAX_CANDIDATES = 6
        private const val RIM_SAMPLES = 96
        private const val RADIAL_COS = 0.85f
        private const val BLUR_R = 2

        /** Sobel magnitude (on a 0–255 image) of a ~10-level step. */
        private const val EDGE_THRESHOLD = 40f
        private const val TAN_22_5 = 0.41421357f

        private val COS = FloatArray(RIM_SAMPLES) { cos(2 * PI * it / RIM_SAMPLES).toFloat() }
        private val SIN = FloatArray(RIM_SAMPLES) { sin(2 * PI * it / RIM_SAMPLES).toFloat() }

        /**
         * Seeding hint from a pose: the shoulder midpoint, normalized, when the
         * pose stands upright (shoulders above hips above knees above ankles)
         * and a shoulder is visible; null otherwise.
         */
        fun shoulderHint(pose: PoseResult): Pair<Float, Float>? {
            val lm = pose.landmarks
            fun mid(left: Int, right: Int): PoseKeypoint? {
                val l = lm.getOrNull(left) ?: return null
                val r = lm.getOrNull(right) ?: return null
                return PoseKeypoint((l.x + r.x) / 2, (l.y + r.y) / 2, 0f, max(l.visibility, r.visibility))
            }
            val shoulder = mid(PoseResult.LEFT_SHOULDER, PoseResult.RIGHT_SHOULDER) ?: return null
            val hip = mid(PoseResult.LEFT_HIP, PoseResult.RIGHT_HIP) ?: return null
            val knee = mid(PoseResult.LEFT_KNEE, PoseResult.RIGHT_KNEE) ?: return null
            val ankle = mid(PoseResult.LEFT_ANKLE, PoseResult.RIGHT_ANKLE) ?: return null
            if (shoulder.visibility < PoseKeypoint.VISIBILITY_THRESHOLD) return null
            if (!(shoulder.y < hip.y && hip.y < knee.y && knee.y < ankle.y)) return null
            return shoulder.x to shoulder.y
        }
    }
}
