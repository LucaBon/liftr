package com.liftr.domain.exercise

import com.liftr.domain.calculator.CoordinateCalculator
import com.liftr.domain.model.BarPathPoint
import com.liftr.domain.model.JumpExcursion
import com.liftr.domain.model.MovementPhase
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import kotlin.math.abs
import kotlin.math.max

/**
 * Collects the center-of-mass path of each jump: from the start of the
 * countermovement through the flight to the landing.
 *
 * Lifts collect their path only during the descent, bottom and drive phases,
 * but a jump's flight is its TOP phase, and a jump with no detectable dip goes
 * straight from IDLE to TOP. So every frame's point is kept for a few seconds,
 * and the window is cut out when the landing completes the rep.
 *
 * It also remembers where the center of mass rests while the athlete stands
 * still, so each jump's dip and peak can be measured from standing.
 *
 * Point timestamps and the `nowMs` passed in must share one clock.
 */
class JumpPathWindow {

    /** A jump's path and its travel from standing (null when never seen standing). */
    data class JumpPath(val points: List<BarPathPoint>, val excursion: JumpExcursion?)

    /** Where the center of mass rested, and the frame's scale, when last standing still. */
    private class Standing(val y: Float, val cmPerUnitY: Float, val realWorldCmPerUnitY: Float, val atMs: Long)

    private val recent = ArrayDeque<BarPathPoint>()
    private var phase = MovementPhase.IDLE
    private var dipStartMs: Long? = null

    // The current still stretch: frames within STILL_RANGE of its first one.
    private var stillFrom: BarPathPoint? = null
    private val stillYs = mutableListOf<Float>()
    private val stillScales = mutableListOf<Float>()
    private var standing: Standing? = null

    /**
     * Record one frame; [point] is null when the center of mass was not
     * measurable. [cmPerUnitY] is the body-based scale of this frame (see
     * [cmPerUnitY]); without it the point's calibrated coordinates are used.
     */
    fun onFrame(point: BarPathPoint?, newPhase: MovementPhase, nowMs: Long, cmPerUnitY: Float? = null) {
        if (newPhase == MovementPhase.ECCENTRIC && phase != MovementPhase.ECCENTRIC) dipStartMs = nowMs
        phase = newPhase
        point?.let {
            recent.addLast(it)
            trackStanding(it, newPhase, cmPerUnitY)
        }
        while (recent.isNotEmpty() && nowMs - recent.first().timestamp > KEEP_MS) recent.removeFirst()
    }

    /**
     * The path of the jump that just landed at [nowMs] after [flightMs] in the
     * air: from the countermovement if one started shortly before takeoff,
     * otherwise from [PRE_TAKEOFF_MS] before takeoff.
     */
    fun pathForLanding(nowMs: Long, flightMs: Float): JumpPath {
        val takeoffMs = nowMs - flightMs.toLong()
        val startMs = dipStartMs?.takeIf { it >= takeoffMs - MAX_DIP_MS } ?: (takeoffMs - PRE_TAKEOFF_MS)
        dipStartMs = null
        return JumpPath(
            points = recent.filter { it.timestamp in startMs..nowMs },
            excursion = excursion(takeoffMs, nowMs)
        )
    }

    /**
     * Dip and peak from standing: the lowest center of mass between standing
     * and takeoff, and the highest one in the air. Image y grows downward.
     */
    private fun excursion(takeoffMs: Long, nowMs: Long): JumpExcursion? {
        val ref = standing?.takeIf { it.atMs < takeoffMs } ?: return null
        val flight = recent.filter { it.timestamp in takeoffMs..nowMs }
        if (flight.isEmpty()) return null
        val dipFrom = max(ref.atMs, takeoffMs - MAX_DIP_MS)
        val lowest = recent.filter { it.timestamp in dipFrom..takeoffMs }.maxOfOrNull { it.normalizedY } ?: ref.y
        val highest = flight.minOf { it.normalizedY }
        val atTakeoff = yAt(takeoffMs)
        return JumpExcursion(
            countermovementCm = ((lowest - ref.y) * ref.cmPerUnitY).coerceAtLeast(0f),
            peakRiseCm = ((ref.y - highest) * ref.cmPerUnitY).coerceAtLeast(0f),
            standingYCm = ref.y * ref.realWorldCmPerUnitY,
            flightRiseCm = atTakeoff?.let { ((it - highest) * ref.cmPerUnitY).coerceAtLeast(0f) }
        )
    }

    /** Center-of-mass y at [timeMs], interpolated between the frames around it. */
    private fun yAt(timeMs: Long): Float? {
        val after = recent.firstOrNull { it.timestamp >= timeMs } ?: return null
        val before = recent.lastOrNull { it.timestamp <= timeMs } ?: return after.normalizedY
        if (after.timestamp == before.timestamp) return after.normalizedY
        val f = (timeMs - before.timestamp).toFloat() / (after.timestamp - before.timestamp)
        return before.normalizedY + f * (after.normalizedY - before.normalizedY)
    }

    /** Standing is a stretch of [ExerciseThresholds.Jump.STILL_MS] on the ground with the center of mass at rest. */
    private fun trackStanding(point: BarPathPoint, newPhase: MovementPhase, cmPerUnitY: Float?) {
        if (newPhase != MovementPhase.IDLE) {
            stillFrom = null
            return
        }
        val anchor = stillFrom
        if (anchor == null || abs(point.normalizedY - anchor.normalizedY) > STILL_RANGE) {
            stillFrom = point
            stillYs.clear()
            stillScales.clear()
        }
        stillYs += point.normalizedY
        cmPerUnitY?.let { stillScales += it }

        val since = stillFrom!!.timestamp
        if (point.timestamp - since < ExerciseThresholds.Jump.STILL_MS || point.normalizedY <= 0f) return
        val realWorldScale = point.realWorldYCm / point.normalizedY
        standing = Standing(
            y = stillYs.average().toFloat(),
            cmPerUnitY = stillScales.sorted().getOrNull(stillScales.size / 2) ?: realWorldScale,
            realWorldCmPerUnitY = realWorldScale,
            atMs = point.timestamp
        )
    }

    companion object {
        /** History kept: longer than any dip plus flight. */
        private const val KEEP_MS = 3_000L

        /** A dip that started longer than this before takeoff belongs to something else. */
        private const val MAX_DIP_MS = 1_500L

        /** Path start before takeoff when no countermovement was detected. */
        private const val PRE_TAKEOFF_MS = 500L

        /** Center-of-mass wobble still counted as standing (~2 cm for a body filling most of the frame) */
        private const val STILL_RANGE = 0.006f

        /**
         * Centimeters per unit of normalized y, from the athlete's own height
         * in the frame: nose to ankles spans a known fraction of stature.
         * Only meaningful while standing upright; null when the nose or both
         * ankles are not visible.
         */
        fun cmPerUnitY(pose: PoseResult, userHeightM: Float): Float? {
            if (userHeightM <= 0f) return null
            fun visible(index: Int) =
                pose.landmarks.getOrNull(index)?.takeIf { it.visibility >= PoseKeypoint.VISIBILITY_THRESHOLD }
            val nose = visible(PoseResult.NOSE) ?: return null
            val ankles = listOfNotNull(visible(PoseResult.LEFT_ANKLE), visible(PoseResult.RIGHT_ANKLE))
            if (ankles.isEmpty()) return null
            val span = ankles.map { it.y }.average().toFloat() - nose.y
            if (span <= 0f) return null
            val statureY = span / (ExerciseThresholds.Jump.NOSE_HEIGHT_RATIO - CoordinateCalculator.ANKLE_HEIGHT_RATIO)
            return userHeightM * 100f / statureY
        }
    }
}
