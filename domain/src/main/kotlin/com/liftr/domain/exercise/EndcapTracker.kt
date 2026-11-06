package com.liftr.domain.exercise

import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import com.liftr.domain.vision.EndcapDetection
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Follows the near barbell endcap through a video, frame by frame.
 *
 * Each frame picks, among the detections, the endcap closest to the hands
 * (the shoulders when no wrist is visible): side-on, the far endcap sits
 * behind the plates and away from the grip. A detection that would move the
 * bar faster than [Config.maxSpeed] since the last accepted one is rejected.
 * A missed frame holds the last position, up to [Config.maxMissedFrames] in
 * a row; after that [update] returns null and the track starts over, so the
 * caller falls back to the pose proxy for those frames.
 *
 * Distances are in image-height units (normalized y; x scaled by
 * [PoseResult.aspectScaleX]). Not thread-safe: one tracker per video.
 */
class EndcapTracker(private val config: Config = Config()) {

    data class Config(
        /** Detections below this confidence are ignored. */
        val minScore: Float = 0.25f,
        /** Farthest an endcap may be from the hands (or shoulders). */
        val maxAnchorDistance: Float = 0.25f,
        /** Fastest plausible bar movement, image heights per second. */
        val maxSpeed: Float = 3f,
        /** Movement always allowed between frames, for detector jitter. */
        val gateSlack: Float = 0.05f,
        /** Missed frames in a row that still hold the last position. */
        val maxMissedFrames: Int = 3,
        /**
         * Weight of each new measurement in the exponential smoothing; 1 turns
         * smoothing off. The video velocity pass smooths again, so keep it light:
         * at 0.7 the lag is under half a frame.
         */
        val smoothingAlpha: Float = 0.7f,
        /** Real diameter of the bar sleeve, for [pixelsPerMeter]. */
        val sleeveDiameterMeters: Float = 0.05f,
        /** Detections needed before [pixelsPerMeter] gives an estimate. */
        val minScaleSamples: Int = 10
    )

    /** Tracked endcap center, normalized like pose landmarks. */
    data class Position(val x: Float, val y: Float, val held: Boolean = false)

    private var position: Position? = null
    private var lastMeasurementMs = 0L
    private var missedFrames = 0
    private val sizeSamplesPx = mutableListOf<Float>()

    /**
     * Endcap position for the frame at [timestampMs], or null when it is not
     * tracked in this frame. Frames must come in increasing time order.
     */
    fun update(timestampMs: Long, detections: List<EndcapDetection>, pose: PoseResult?): Position? {
        val aspect = pose?.aspectScaleX ?: 1f
        val anchor = pose?.let(::anchor)
        val previous = position
        val gate = previous?.let {
            val dtSeconds = (timestampMs - lastMeasurementMs).coerceAtLeast(0L) / 1000f
            config.gateSlack + config.maxSpeed * dtSeconds
        }

        val chosen = detections
            .filter { it.score >= config.minScore }
            .filter { anchor == null || distance(it, anchor.first, anchor.second, aspect) <= config.maxAnchorDistance }
            .filter { previous == null || distance(it, previous.x, previous.y, aspect) <= gate!! }
            .minByOrNull {
                when {
                    anchor != null -> distance(it, anchor.first, anchor.second, aspect)
                    previous != null -> distance(it, previous.x, previous.y, aspect)
                    else -> 0f
                }
            }
            // With neither hands nor a track there is no telling near from far.
            ?.takeIf { anchor != null || previous != null }

        if (chosen == null) {
            if (previous == null) return null
            missedFrames++
            if (missedFrames > config.maxMissedFrames) {
                position = null
                return null
            }
            return previous.copy(held = true)
        }

        missedFrames = 0
        lastMeasurementMs = timestampMs
        pose?.let { sizeSamplesPx += max(chosen.width * it.imageWidth, chosen.height * it.imageHeight) }
        val a = config.smoothingAlpha
        val next = if (previous == null) {
            Position(chosen.centerX, chosen.centerY)
        } else {
            Position(previous.x + a * (chosen.centerX - previous.x), previous.y + a * (chosen.centerY - previous.y))
        }
        position = next
        return next
    }

    /**
     * Image scale from the endcap size (the sleeve diameter), in pixels per
     * meter, or null until enough endcaps were seen. An estimate only, for
     * comparison with the calibration; nothing uses it in its place.
     */
    fun pixelsPerMeter(): Float? {
        if (sizeSamplesPx.size < config.minScaleSamples) return null
        val sorted = sizeSamplesPx.sorted()
        return sorted[sorted.size / 2] / config.sleeveDiameterMeters
    }

    private fun anchor(pose: PoseResult): Pair<Float, Float>? =
        visibleMidpoint(pose, PoseResult.LEFT_WRIST, PoseResult.RIGHT_WRIST)
            ?: visibleMidpoint(pose, PoseResult.LEFT_SHOULDER, PoseResult.RIGHT_SHOULDER)

    /** Midpoint of the two landmarks, or the one visible, or null. */
    private fun visibleMidpoint(pose: PoseResult, left: Int, right: Int): Pair<Float, Float>? {
        val visible = listOfNotNull(pose.landmarks.getOrNull(left), pose.landmarks.getOrNull(right))
            .filter { it.visibility >= PoseKeypoint.VISIBILITY_THRESHOLD }
        if (visible.isEmpty()) return null
        return Pair(visible.map { it.x }.average().toFloat(), visible.map { it.y }.average().toFloat())
    }

    private fun distance(d: EndcapDetection, x: Float, y: Float, aspect: Float): Float {
        val dx = (d.centerX - x) * aspect
        val dy = d.centerY - y
        return sqrt(dx * dx + dy * dy)
    }
}
