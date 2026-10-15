package com.liftr.domain.exercise

import com.liftr.domain.calculator.CoordinateCalculator
import com.liftr.domain.model.BarPathPoint
import com.liftr.domain.model.JumpExcursion
import com.liftr.domain.model.JumpFitCheck
import com.liftr.domain.model.MovementPhase
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import com.liftr.domain.validation.JumpFlightValidator
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
     *
     * [takeoffMs] and [landingMs] are the measured toe-off and touchdown, on
     * this window's clock. Landing is only recognized a frame or two after
     * touchdown, so without them takeoff is placed that much too late.
     */
    fun pathForLanding(nowMs: Long, flightMs: Float, takeoffMs: Long? = null, landingMs: Long? = null): JumpPath {
        // Only measured takeoff and touchdown can be checked against each other.
        val timed = takeoffMs != null && landingMs != null
        val landedMs = landingMs ?: nowMs
        val takeoffMs = takeoffMs ?: (landedMs - flightMs.toLong())
        val startMs = dipStartMs?.takeIf { it >= takeoffMs - MAX_DIP_MS } ?: (takeoffMs - PRE_TAKEOFF_MS)
        dipStartMs = null
        return JumpPath(
            points = recent.filter { it.timestamp in startMs..nowMs },
            excursion = excursion(takeoffMs, landedMs, timed)
        )
    }

    /**
     * Dip and peak from standing: the lowest center of mass between standing
     * and takeoff, and the highest one in the air. Image y grows downward.
     */
    private fun excursion(takeoffMs: Long, landingMs: Long, timed: Boolean): JumpExcursion? {
        val ref = standing?.takeIf { it.atMs < takeoffMs } ?: return null
        val flight = recent.filter { it.timestamp in takeoffMs..landingMs }
        if (flight.isEmpty()) return null
        val dipFrom = max(ref.atMs, takeoffMs - MAX_DIP_MS)
        // Extremes of a 3-frame median, so one mistracked frame can't set them.
        val lowest = medianOf3(recent.filter { it.timestamp in dipFrom..takeoffMs }).maxOrNull() ?: ref.y
        val atTakeoff = yAt(takeoffMs)
        val trimmed = flight.filter { it.timestamp in takeoffMs + TAKEOFF_TRIM_MS..landingMs - LANDING_TRIM_MS }
        val (fitCheck, fitFrames) = fitCheck(trimmed, takeoffMs, ref.cmPerUnitY)
        // Frames left out of the fit as mistracked don't set the peak either.
        val dropped = trimmed.toSet() - fitFrames.toSet()
        val highest = medianOf3(flight.filterNot { it in dropped }).min()
        val takeoffVelocity = takeoffVelocity(fitFrames, takeoffMs, ref.cmPerUnitY)
        val timedFitCheck = fitCheck?.let { check ->
            takeoffVelocity?.takeIf { timed }?.let { timingCheck(check, it, takeoffMs, landingMs, ref.cmPerUnitY) } ?: check
        }
        return JumpExcursion(
            countermovementCm = ((lowest - ref.y) * ref.cmPerUnitY).coerceAtLeast(0f),
            peakRiseCm = ((ref.y - highest) * ref.cmPerUnitY).coerceAtLeast(0f),
            standingYCm = ref.y * ref.realWorldCmPerUnitY,
            flightRiseCm = atTakeoff?.let { ((it - highest) * ref.cmPerUnitY).coerceAtLeast(0f) },
            takeoffVelocity = takeoffVelocity,
            velocityHeightCm = takeoffVelocity?.let { (it * it / (2 * G) * 100).toFloat() },
            fitCheck = timedFitCheck
        )
    }

    /**
     * Checks takeoff and landing against the center of mass. Its parabola is
     * symmetric, so the fall from the top lasts as long as the rise unless it
     * lands lower than it took off (on bent legs): the extra fall time says
     * how much lower, which must match where the center of mass was.
     *
     * The rise is v0 / g with v0 fitted from [takeoffMs], so it is the time
     * from that takeoff to the top of the fitted flight.
     */
    private fun timingCheck(
        check: JumpFitCheck,
        takeoffVelocity: Float,
        takeoffMs: Long,
        landingMs: Long,
        cmPerUnitY: Float
    ): JumpFitCheck {
        val atTakeoff = yAt(takeoffMs) ?: return check
        val atLanding = yAt(landingMs) ?: return check
        val riseS = takeoffVelocity / G
        val fallS = (landingMs - takeoffMs) / 1000.0 - riseS
        if (fallS <= 0) return check
        val fromTimesCm = (G / 2 * (fallS * fallS - riseS * riseS) * 100).toFloat()
        // Image y grows downward: landing lower is a larger y.
        val measuredCm = (atLanding - atTakeoff) * cmPerUnitY
        return check.copy(
            landingDropFromTimesCm = fromTimesCm,
            landingDropCm = measuredCm,
            timingOk = abs(fromTimesCm - measuredCm) <= ExerciseThresholds.Jump.TIMING_TOLERANCE_CM
        )
    }

    /**
     * The flight checked against free fall (see [JumpFlightValidator]), and
     * its frames without those left out as mistracked; all of them when it
     * can't be checked.
     */
    private fun fitCheck(
        flight: List<BarPathPoint>,
        takeoffMs: Long,
        cmPerUnitY: Float
    ): Pair<JumpFitCheck?, List<BarPathPoint>> {
        if (flight.isEmpty()) return null to flight
        val taus = flight.map { (it.timestamp - takeoffMs) / 1000.0 }
        val heights = flight.map { (flight.first().normalizedY - it.normalizedY) * cmPerUnitY / 100.0 }
        val (check, kept) = JumpFlightValidator.checkWithoutOutliers(taus, heights) ?: return null to flight
        return check to kept.map { flight[it] }
    }

    /**
     * Upward velocity at takeoff, in m/s, from the whole flight rather than the
     * frames around takeoff, whose difference is mostly landmark jitter.
     *
     * In the air the center of mass follows z = z0 + v0·τ - g·τ²/2 with τ the
     * time since takeoff, so z + g·τ²/2 is a straight line whose slope is v0.
     * Null with too few airborne frames, or when the fit does not rise.
     */
    private fun takeoffVelocity(flight: List<BarPathPoint>, takeoffMs: Long, cmPerUnitY: Float): Float? {
        if (flight.size < ExerciseThresholds.Jump.MIN_FIT_SAMPLES) return null
        val taus = flight.map { (it.timestamp - takeoffMs) / 1000.0 }
        if (taus.last() - taus.first() < MIN_FIT_SPAN_S) return null
        // Height above the first airborne frame, in meters; image y grows downward.
        val lines = flight.zip(taus) { p, tau ->
            (flight.first().normalizedY - p.normalizedY) * cmPerUnitY / 100.0 + G * tau * tau / 2
        }
        val meanTau = taus.average()
        val meanLine = lines.average()
        val sxx = taus.sumOf { (it - meanTau) * (it - meanTau) }
        val sxy = taus.indices.sumOf { (taus[it] - meanTau) * (lines[it] - meanLine) }
        val v0 = sxy / sxx
        return v0.takeIf { it > 0 }?.toFloat()
    }

    /** Each point's y as the median of it and its neighbors; the ends keep their own. */
    private fun medianOf3(points: List<BarPathPoint>): List<Float> = points.indices.map { i ->
        if (i == 0 || i == points.lastIndex) points[i].normalizedY
        else listOf(points[i - 1].normalizedY, points[i].normalizedY, points[i + 1].normalizedY).sorted()[1]
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

        /**
         * Frames this soon after the toes leave the floor are not yet in free
         * fall: the center of mass sits below the flight's parabola there.
         */
        private const val TAKEOFF_TRIM_MS = 30L

        /** Frames this close to touchdown may already be braking on the ground. */
        private const val LANDING_TRIM_MS = 20L

        /** Airborne time the velocity fit needs to tell the rise from the fall. */
        private const val MIN_FIT_SPAN_S = 0.15

        private const val G = 9.81

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
