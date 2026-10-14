package com.liftr.domain.exercise

import com.liftr.domain.model.BarPathPoint
import com.liftr.domain.model.MovementPhase

/**
 * Collects the center-of-mass path of each jump: from the start of the
 * countermovement through the flight to the landing.
 *
 * Lifts collect their path only during the descent, bottom and drive phases,
 * but a jump's flight is its TOP phase, and a jump with no detectable dip goes
 * straight from IDLE to TOP. So every frame's point is kept for a few seconds,
 * and the window is cut out when the landing completes the rep.
 *
 * Point timestamps and the `nowMs` passed in must share one clock.
 */
class JumpPathWindow {

    private val recent = ArrayDeque<BarPathPoint>()
    private var phase = MovementPhase.IDLE
    private var dipStartMs: Long? = null

    /** Record one frame; [point] is null when the center of mass was not measurable. */
    fun onFrame(point: BarPathPoint?, newPhase: MovementPhase, nowMs: Long) {
        if (newPhase == MovementPhase.ECCENTRIC && phase != MovementPhase.ECCENTRIC) dipStartMs = nowMs
        phase = newPhase
        point?.let { recent.addLast(it) }
        while (recent.isNotEmpty() && nowMs - recent.first().timestamp > KEEP_MS) recent.removeFirst()
    }

    /**
     * The path of the jump that just landed at [nowMs] after [flightMs] in the
     * air: from the countermovement if one started shortly before takeoff,
     * otherwise from [PRE_TAKEOFF_MS] before takeoff.
     */
    fun pathForLanding(nowMs: Long, flightMs: Float): List<BarPathPoint> {
        val takeoffMs = nowMs - flightMs.toLong()
        val startMs = dipStartMs?.takeIf { it >= takeoffMs - MAX_DIP_MS } ?: (takeoffMs - PRE_TAKEOFF_MS)
        dipStartMs = null
        return recent.filter { it.timestamp in startMs..nowMs }
    }

    private companion object {
        /** History kept: longer than any dip plus flight. */
        const val KEEP_MS = 3_000L

        /** A dip that started longer than this before takeoff belongs to something else. */
        const val MAX_DIP_MS = 1_500L

        /** Path start before takeoff when no countermovement was detected. */
        const val PRE_TAKEOFF_MS = 500L
    }
}
