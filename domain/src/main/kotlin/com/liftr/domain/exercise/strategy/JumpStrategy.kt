package com.liftr.domain.exercise.strategy

import com.liftr.domain.calculator.JumpMechanics
import com.liftr.domain.exercise.ExerciseCriteria
import com.liftr.domain.exercise.ExerciseMetrics
import com.liftr.domain.exercise.ExercisePhaseState
import com.liftr.domain.exercise.ExerciseStrategy
import com.liftr.domain.exercise.ExerciseThresholds.Jump
import com.liftr.domain.exercise.FormIssue
import com.liftr.domain.exercise.FormValidationResult
import com.liftr.domain.exercise.JumpTrackingState
import com.liftr.domain.exercise.PhaseTransitionResult
import com.liftr.domain.exercise.Severity
import com.liftr.domain.exercise.ToePoint
import com.liftr.domain.exercise.ToeTrack
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.JumpData
import com.liftr.domain.model.JumpExcursion
import com.liftr.domain.model.MovementPhase
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import com.liftr.domain.model.VelocityData
import com.liftr.domain.usecase.AnalyzeRepUseCase
import com.liftr.domain.value.Joules
import com.liftr.domain.value.Kilograms
import com.liftr.domain.value.Meters
import com.liftr.domain.value.MetersPerSecond
import com.liftr.domain.value.Watts

/**
 * Strategy for vertical jumps (bodyweight or loaded) and approach jumps.
 *
 * Jump height comes from flight time (see [JumpData]), timed on the toes: they
 * are the last point to leave the ground and the first to touch it, whereas the
 * ankles already rise during the heel raise before takeoff.
 *
 * Flight time comes from fitting a parabola to the toe height of every airborne
 * frame; its roots are takeoff and landing. That uses all frames (averaging out
 * landmark jitter), resolves timing well below one frame, and needs no metric
 * scale. Very short flights with too few frames to fit fall back to the times
 * the toes crossed a small height, interpolated between frames, with the flight
 * clipped by timing above the ground restored in [JumpData.fromFlightTime].
 *
 * Phases follow the shared cycle so per-rep tracking downstream keeps working:
 * IDLE (standing) -> ECCENTRIC (countermovement dip) -> BOTTOM (turnaround) ->
 * CONCENTRIC (push-off) -> TOP (airborne) -> landing counts the rep -> IDLE.
 * A jump without a visible dip goes straight from IDLE to TOP.
 *
 * Approach jumps (run-up, then a one- or two-foot takeoff) have no standing
 * position to measure the ground and body size from, and the floor's height
 * in the image changes along the run (camera roll, a run not quite parallel
 * to the image) and between the feet (different depth). So the floor is the
 * height of the toe that takes off, measured while it is planted
 * ([followToes]): a toe barely moving for [Jump.APPROACH_STANCE_MS] is in
 * stance, and with two the lower one counts. That toe is then followed, matched
 * by position between frames, through takeoff and flight; its height over its
 * stance height is the rise everything else works on. Strides and bounds are
 * kept out by a longer minimum flight ([Jump.APPROACH_MIN_FLIGHT_MS]), and since
 * there is no dip to wait for, a takeoff needs no toe planted and the hips rising.
 * The ground is measured anew from wherever the athlete lands. This needs the
 * foot to stay planted for about 80 ms before takeoff (a gather step does).
 *
 * Detection works on normalized landmark coordinates with thresholds expressed
 * as fractions of the standing body span, so it needs no metric calibration.
 */
class JumpStrategy(
    override val exerciseType: ExerciseType,
    private val logger: Logger
) : ExerciseStrategy {

    init {
        require(exerciseType.isJumpExercise()) { "$exerciseType is not a jump" }
    }

    private val approach = exerciseType == ExerciseType.APPROACH_JUMP
    private val minFlightMs = if (approach) Jump.APPROACH_MIN_FLIGHT_MS else Jump.MIN_FLIGHT_MS

    private val criteria = ExerciseCriteria(
        minDescentDepth = Meters(0f),
        returnTolerance = Meters(0f),
        minVelocity = MetersPerSecond(0f),
        targetBodyParts = listOf(
            PoseResult.NOSE,
            PoseResult.LEFT_HIP,
            PoseResult.RIGHT_HIP,
            PoseResult.LEFT_KNEE,
            PoseResult.RIGHT_KNEE,
            PoseResult.LEFT_ANKLE,
            PoseResult.RIGHT_ANKLE,
            PoseResult.LEFT_FOOT_INDEX,
            PoseResult.RIGHT_FOOT_INDEX
        )
    )

    companion object {
        private const val TAG = "JumpStrategy"
        private const val GRAVITY = 9.81
        /** Frames on the ground kept, by age: enough to see the toes resting before takeoff. */
        private const val GROUND_WINDOW_MS = 300L
        /** The toes start to peel off this long before they cross the timing level. */
        private const val TOE_OFF_LEAD_MS = 60L
    }

    /** Head, hip and toe heights, oriented so that larger = lower; [upsideDown] when the pose had the head below the feet. */
    private data class Frame(
        val headY: Float,
        val hipsY: Float,
        val footY: Float,
        val upsideDown: Boolean = false,
        val toes: List<ToePoint> = emptyList()
    )

    override fun detectPhase(
        poseResult: PoseResult,
        calibrationData: CalibrationData,
        currentState: ExercisePhaseState
    ): PhaseTransitionResult {
        // Milliseconds with the microseconds kept: at 240 fps a whole
        // millisecond is a quarter of a frame.
        val now = poseResult.timestampUs / 1000.0
        val frame = readFrame(poseResult)
            ?: return PhaseTransitionResult(
                // Drop the previous sample so timing never interpolates across a
                // gap, and end any run of upside-down frames: a turned camera
                // shows them back to back.
                newState = currentState.copy(
                    jump = currentState.jump?.copy(
                        previousFootY = null, previousHipsY = null, previousTimestamp = null, flippedSinceMs = null
                    )
                ),
                repCompleted = false,
                debugInfo = "Feet, hips or head not visible"
            )

        val stored = currentState.jump
            ?: return PhaseTransitionResult(
                newState = currentState.copy(
                    currentPhase = MovementPhase.IDLE,
                    jump = baselineFrom(frame, now)
                ),
                repCompleted = false,
                debugInfo = "Standing baseline set"
            )

        // A pose the other way up from the baseline is mistracked (seen on
        // single frames mid-flight when every frame is detected on its own):
        // read as is, it puts the feet back on the floor. Skip it like a frame
        // with nothing visible, unless it lasts: then the camera was turned.
        if (frame.upsideDown != stored.upsideDown) {
            val since = stored.flippedSinceMs ?: now
            if (stored.isAirborne || now - since < Jump.STILL_MS) {
                return PhaseTransitionResult(
                    newState = currentState.copy(
                        jump = stored.copy(previousFootY = null, previousTimestamp = null, flippedSinceMs = since)
                    ),
                    repCompleted = false,
                    debugInfo = "Pose upside down, skipped"
                )
            }
            return PhaseTransitionResult(
                newState = currentState.copy(
                    currentPhase = MovementPhase.IDLE,
                    jump = baselineFrom(frame, now)
                ),
                repCompleted = false,
                debugInfo = "Camera turned, standing baseline set"
            )
        }
        val tracking = if (stored.flippedSinceMs != null) stored.copy(flippedSinceMs = null) else stored

        val (followed, measured) =
            if (approach) followToes(tracking, frame, now) else tracking to frame
        return if (followed.isAirborne) {
            detectLanding(currentState, followed, measured, now, calibrationData)
        } else {
            detectGroundPhase(currentState, followed, measured, now)
        }
    }

    private fun detectLanding(
        state: ExercisePhaseState,
        tracking: JumpTrackingState,
        frame: Frame,
        timestamp: Double,
        calibrationData: CalibrationData
    ): PhaseTransitionResult {
        val takeoffMs = tracking.takeoffMs!!
        val timingLevel = Jump.TIMING_LEVEL * tracking.bodySpan

        if (timestamp - takeoffMs > Jump.MAX_FLIGHT_MS) {
            logger.d(TAG, "Flight exceeded ${Jump.MAX_FLIGHT_MS} ms, discarding")
            val spanPerMeter = tracking.bodySpan / (Jump.NOSE_HEIGHT_RATIO * calibrationData.userHeight)
            return PhaseTransitionResult(
                newState = state.copy(currentPhase = MovementPhase.IDLE, jump = baselineFrom(frame, timestamp)),
                repCompleted = false,
                debugInfo = "Tracking lost in flight",
                flightGravity = fitGravity(tracking.flightSamples, spanPerMeter)
            )
        }

        val rise = tracking.baselineFootY - frame.footY
        val previousRise = tracking.previousFootY?.let { tracking.baselineFootY - it }

        // Feet land where they stop coming down, which is not always the
        // takeoff level: a step farther from the camera shows the floor higher
        // in the image, a step closer shows it lower. Past the peak and no
        // longer descending = landed.
        val peak = tracking.flightSamples.maxOfOrNull { it.second } ?: rise
        val settled = previousRise != null &&
            rise < peak * Jump.SETTLED_FRACTION_OF_PEAK &&
            tracking.previousTimestamp != null && timestamp > tracking.previousTimestamp &&
            (previousRise - rise) * 1000f / (timestamp - tracking.previousTimestamp) < Jump.SETTLED_DESCENT_SPEED * tracking.bodySpan

        // Below the takeoff level, feet still falling are landing closer to
        // the camera: keep sampling until they settle.
        if (!settled) {
            val sampled = tracking.copy(
                previousFootY = frame.footY,
                previousTimestamp = timestamp,
                flightSamples = tracking.flightSamples + (timestamp to rise),
                flightHipSamples = tracking.flightHipSamples + (timestamp to tracking.baselineHipsY - frame.hipsY),
                peakHeadRise = maxOf(tracking.peakHeadRise, headRise(tracking, frame))
            )
            return PhaseTransitionResult(
                newState = state.copy(jump = sampled),
                repCompleted = false,
                debugInfo = "Airborne rise=%.3f span=%.3f".format(rise, tracking.bodySpan)
            )
        }

        // The ground the feet came back to, as a rise above (or drop below)
        // the takeoff level; within the timing level it is the same ground.
        val landingRise = if (kotlin.math.abs(rise) > timingLevel) rise else 0f
        val landingLevel = landingRise + timingLevel
        val points = tracking.flightSamples + (timestamp to rise)
        val landingMs = landingCrossingMs(points, landingLevel) ?: timestamp
        val crossingFlightMs = (landingMs - takeoffMs).toFloat()
        // Landing elsewhere moves the ground: stand from there. Otherwise keep
        // the standing baseline, which the landing crouch would distort. After
        // a run-up the athlete rarely lands where they took off, nor was there
        // a standing baseline to keep.
        val grounded = state.copy(
            currentPhase = MovementPhase.IDLE,
            jump = if (landingRise != 0f || approach) {
                baselineFrom(frame, timestamp)
            } else {
                tracking.copy(
                    lowestHipsY = null, takeoffCandidateMs = null, takeoffMs = null, flightSamples = emptyList(),
                    previousFootY = frame.footY, previousTimestamp = timestamp,
                    // Set again below if this flight counts as a jump.
                    contactStartMs = null
                )
            }
        )

        if (crossingFlightMs < minFlightMs) {
            return PhaseTransitionResult(grounded, repCompleted = false, debugInfo = "Flight too short")
        }

        // The timing level in meters: a fraction of the nose-to-toe span.
        val timingLevelMeters = Jump.TIMING_LEVEL * Jump.NOSE_HEIGHT_RATIO * calibrationData.userHeight
        // The parabola's roots sit where the toes rest on the floor, which may
        // be off the standing baseline by more than the timing level (the
        // baseline only follows the feet while they stay within it); a root at
        // the baseline instead would cut or stretch the flight. The feet may
        // come down elsewhere: compare the landing with the takeoff floor.
        // Approach: the baseline is the takeoff toe's own stance height.
        val takeoffGround = if (approach) 0f else restingToeRise(tracking.groundSamples, takeoffMs)
        val landingGround = if (kotlin.math.abs(rise - takeoffGround) > timingLevel) rise else takeoffGround
        val fittedFlightMs = fitFlightMs(airborneSamples(tracking.flightSamples, landingLevel), takeoffGround, landingGround)
        val fromFlight = if (fittedFlightMs != null && fittedFlightMs in minFlightMs..Jump.MAX_FLIGHT_MS) {
            JumpData.fromFlightTime(fittedFlightMs)
        } else {
            JumpData.fromFlightTime(crossingFlightMs, timingLevelMeters)
        }
        // Second estimate from the rising half alone, which landing posture
        // can't stretch: the time from takeoff to the top of the flight.
        val spanPerMeter = tracking.bodySpan / (Jump.NOSE_HEIGHT_RATIO * calibrationData.userHeight)
        val apexHeight = fitApexMs(tracking.flightHipSamples, Jump.OUTLIER_FLOOR * tracking.bodySpan)
            ?.takeIf { it > takeoffMs && it < landingMs }
            ?.let { apexMs ->
                val toes = (tracking.groundSamples + tracking.flightSamples).filter { it.first < apexMs }
                // The toes leave the floor between the last frame still on it
                // and the next one; right after, the foot flicks up faster than
                // the body, so later frames would project takeoff too early.
                val onFloor = toes.indexOfLast { it.second <= timingLevel }
                val firstOff = toes.getOrNull(onFloor + 1) ?: tracking.flightSamples.first()
                heightFromTakeoffToApex(firstOff, toes.getOrNull(onFloor)?.first, apexMs, spanPerMeter)
            }
        val fittedGravity = fitGravity(airborneSamples(tracking.flightSamples, landingLevel), spanPerMeter)
        // Ground contact (see JumpData): both crossings moved down to the
        // floor by the time the toes take between it and the timing level.
        val clipMs = JumpData.timingLevelClipMs(crossingFlightMs, timingLevelMeters)
        val contactTimeMs = tracking.contactStartMs?.takeUnless { approach }
            ?.let { (takeoffMs - clipMs - it).toFloat() }
            ?.takeIf { it in Jump.MIN_CONTACT_MS..Jump.MAX_CONTACT_MS }
        val jump = fromFlight.copy(
            apexHeight = apexHeight, takeoffAtMs = takeoffMs, landingAtMs = landingMs, contactTimeMs = contactTimeMs
        )
        val flightMs = jump.flightTimeMs
        if (flightMs < minFlightMs) {
            logger.d(TAG, "Flight of %.0f ms is too short for a jump".format(flightMs))
            return PhaseTransitionResult(grounded, repCompleted = false, debugInfo = "Flight too short")
        }

        // A real flight lifts the whole body: toes "rising" on their own (the
        // foot rocking onto the heel, a far foot flickering in and out of view)
        // leave the head where it was.
        val expectedHeadRise = jump.height.value * spanPerMeter
        val headRise = maxOf(tracking.peakHeadRise, headRise(tracking, frame))
        if (headRise < Jump.MIN_HEAD_RISE_FRACTION * expectedHeadRise) {
            logger.d(TAG, "Feet-only flight of %.0f ms, head rose %.0f%% of a jump: not a jump"
                .format(flightMs, 100 * headRise / expectedHeadRise))
            return PhaseTransitionResult(
                grounded, repCompleted = false, debugInfo = "Head did not rise", flightGravity = fittedGravity
            )
        }
        logger.d(TAG, "Jump: flight=%.0f ms height=%.1f cm, takeoff to apex %s, floor %.3f -> %.3f, contact %s".format(
            flightMs, jump.height.value * 100, jump.apexHeight?.let { "%.1f cm".format(it.value * 100) } ?: "n/a",
            takeoffGround, landingGround, contactTimeMs?.let { "%.0f ms".format(it) } ?: "n/a"
        ))
        // A run-up's strides are no rebound contacts: approach jumps time none.
        val landed = if (approach) grounded else grounded.copy(jump = grounded.jump?.copy(contactStartMs = landingMs + clipMs))
        return PhaseTransitionResult(
            newState = landed,
            repCompleted = true,
            debugInfo = "Landed: %.1f cm".format(jump.height.value * 100),
            jump = jump,
            flightGravity = fittedGravity
        )
    }

    /** Flight samples up to the peak, then those still above [landingLevel]. */
    private fun airborneSamples(samples: List<Pair<Double, Float>>, landingLevel: Float): List<Pair<Double, Float>> {
        val peakIndex = samples.indices.maxByOrNull { samples[it].second } ?: return samples
        return samples.filterIndexed { i, (_, rise) -> i <= peakIndex || rise > landingLevel }
    }

    /** Time the toes came down through [level] after the peak, interpolated between frames. */
    private fun landingCrossingMs(points: List<Pair<Double, Float>>, level: Float): Double? {
        val peakIndex = points.indices.maxByOrNull { points[it].second } ?: return null
        for (i in peakIndex until points.lastIndex) {
            val (t0, r0) = points[i]
            val (t1, r1) = points[i + 1]
            if (r0 > level && r1 <= level) return crossingTime(r0, t0, r1, t1, level)
        }
        return null
    }

    private fun detectGroundPhase(
        state: ExercisePhaseState,
        tracking: JumpTrackingState,
        frame: Frame,
        timestamp: Double
    ): PhaseTransitionResult {
        val span = tracking.bodySpan
        val timingLevel = Jump.TIMING_LEVEL * span
        val rise = tracking.baselineFootY - frame.footY
        val previousRise = tracking.previousFootY?.let { tracking.baselineFootY - it }
        val previousTimestamp = tracking.previousTimestamp

        // Takeoff timing: remember when the toes crossed the timing level going up.
        val takeoffCandidate = when {
            rise <= timingLevel -> null
            tracking.takeoffCandidateMs != null -> tracking.takeoffCandidateMs
            previousRise != null && previousTimestamp != null && previousRise <= timingLevel ->
                crossingTime(previousRise, previousTimestamp, rise, timestamp, timingLevel)
            else -> null
        }
        var next = tracking.copy(
            takeoffCandidateMs = takeoffCandidate,
            previousFootY = frame.footY,
            previousHipsY = frame.hipsY,
            previousTimestamp = timestamp,
            groundSamples = (tracking.groundSamples + (timestamp to rise))
                .filter { timestamp - it.first <= GROUND_WINDOW_MS }
        )

        // A takeoff needs the hips driving up: feet "rising" while the hips are
        // still sinking into the dip is a landmark glitch, not a flight. A run-up
        // has no dip and no standing hip height: a foot still planted, or hips not
        // higher than a frame ago, is no takeoff.
        val dipping = if (approach) {
            tracking.planted || (tracking.previousHipsY?.let { frame.hipsY >= it } ?: true)
        } else {
            state.currentPhase == MovementPhase.ECCENTRIC
        }
        if (takeoffCandidate != null && rise > Jump.AIRBORNE_LEVEL * span && !dipping) {
            return PhaseTransitionResult(
                newState = state.copy(
                    currentPhase = MovementPhase.TOP,
                    jump = next.copy(
                        takeoffMs = takeoffCandidate,
                        flightSamples = listOf(timestamp to rise),
                        flightHipSamples = listOf(timestamp to tracking.baselineHipsY - frame.hipsY),
                        peakHeadRise = headRise(tracking, frame)
                    )
                ),
                repCompleted = false,
                debugInfo = "Takeoff"
            )
        }

        // Feet and hips resting in one place for a while are standing there,
        // whatever the old baseline says: re-anchor and start over from IDLE.
        val settled = settleOnStillBody(next, frame, timestamp)
        if (settled.baselineFootY != next.baselineFootY) {
            return PhaseTransitionResult(
                newState = state.copy(currentPhase = MovementPhase.IDLE, jump = settled),
                repCompleted = false,
                debugInfo = "Re-anchored on still body"
            )
        }
        next = settled

        // Resting for STILL_MS since the landing ends the rebound: the next
        // takeoff starts a fresh jump, with no contact time.
        val contactStart = next.contactStartMs
        val stillSince = next.stillSinceMs
        if (contactStart != null && stillSince != null && timestamp - maxOf(stillSince, contactStart) >= Jump.STILL_MS) {
            next = next.copy(contactStartMs = null)
        }

        val hipDrop = frame.hipsY - tracking.baselineHipsY
        val phase = state.currentPhase
        val newPhase = when (phase) {
            MovementPhase.IDLE, MovementPhase.TOP -> {
                if (hipDrop > Jump.DIP_LEVEL * span) {
                    next = next.copy(lowestHipsY = frame.hipsY)
                    MovementPhase.ECCENTRIC
                } else {
                    if (!approach) next = reanchor(next, frame, tracking.previousTimestamp, timestamp)
                    MovementPhase.IDLE
                }
            }
            MovementPhase.ECCENTRIC, MovementPhase.BOTTOM -> {
                val lowest = maxOf(next.lowestHipsY ?: frame.hipsY, frame.hipsY)
                next = next.copy(lowestHipsY = lowest)
                val recovered = lowest - frame.hipsY
                when {
                    phase == MovementPhase.ECCENTRIC && recovered > Jump.TURNAROUND_LEVEL * span -> MovementPhase.BOTTOM
                    phase == MovementPhase.BOTTOM && recovered > 2 * Jump.TURNAROUND_LEVEL * span -> MovementPhase.CONCENTRIC
                    else -> phase
                }
            }
            MovementPhase.CONCENTRIC -> {
                // Stood back up without leaving the ground.
                if (hipDrop < Jump.DIP_LEVEL * span / 2) MovementPhase.IDLE else MovementPhase.CONCENTRIC
            }
        }

        return PhaseTransitionResult(
            newState = state.copy(currentPhase = newPhase, jump = next),
            repCompleted = false,
            debugInfo = "Ground: $newPhase rise=%.3f hipDrop=%.3f span=%.3f".format(rise, hipDrop, span)
        )
    }

    /**
     * Re-anchors the baseline on feet and hips that have rested in one place
     * for [Jump.STILL_MS], however far from the baseline: after landing closer
     * to or farther from the camera, when the first frame caught the athlete
     * still walking into place, or during a held squat. [reanchor] only
     * follows small drifts while standing.
     */
    private fun settleOnStillBody(tracking: JumpTrackingState, frame: Frame, timestamp: Double): JumpTrackingState {
        val tolerance = Jump.TIMING_LEVEL * tracking.bodySpan
        val footAnchor = tracking.stillFootY
        val hipsAnchor = tracking.stillHipsY
        val since = tracking.stillSinceMs
        val moved = footAnchor == null || hipsAnchor == null || since == null ||
            kotlin.math.abs(frame.footY - footAnchor) > tolerance ||
            kotlin.math.abs(frame.hipsY - hipsAnchor) > tolerance
        if (moved) return tracking.copy(stillFootY = frame.footY, stillHipsY = frame.hipsY, stillSinceMs = timestamp)
        if (timestamp - since!! < Jump.STILL_MS) return tracking
        val atBaseline = kotlin.math.abs(frame.footY - tracking.baselineFootY) < tolerance &&
            kotlin.math.abs(frame.hipsY - tracking.baselineHipsY) < tolerance
        return if (atBaseline) tracking else baselineFrom(frame, timestamp)
    }

    /**
     * A smoothing [factor] given per [Jump.SMOOTHING_FRAME_MS] as the factor for the time since
     * [previousTimestamp], so the baseline follows at the same speed at any frame rate.
     */
    private fun smoothing(factor: Float, previousTimestamp: Double?, timestamp: Double): Float {
        val dt = previousTimestamp?.let { timestamp - it }?.takeIf { it > 0 } ?: return factor
        return 1f - Math.pow((1f - factor).toDouble(), dt / Jump.SMOOTHING_FRAME_MS).toFloat()
    }

    /** Slowly follows the standing position so posture shifts don't trip thresholds. */
    private fun reanchor(tracking: JumpTrackingState, frame: Frame, previousTimestamp: Double?, timestamp: Double): JumpTrackingState {
        val a = smoothing(Jump.BASELINE_SMOOTHING, previousTimestamp, timestamp)
        val span = tracking.bodySpan
        val footStill = kotlin.math.abs(frame.footY - tracking.baselineFootY) < Jump.TIMING_LEVEL * span
        if (!footStill) return tracking
        return tracking.copy(
            baselineFootY = tracking.baselineFootY + a * (frame.footY - tracking.baselineFootY),
            baselineHipsY = tracking.baselineHipsY + a * (frame.hipsY - tracking.baselineHipsY),
            bodySpan = span + a * ((frame.footY - frame.headY) - span)
        )
    }

    /**
     * Approach jumps: finds the toes in stance and returns the state and a
     * frame whose [Frame.footY] is the followed toe's height.
     *
     * Toes are matched to last frame's by position; one slower than
     * [Jump.APPROACH_STANCE_SPEED] in x and y for [Jump.APPROACH_STANCE_MS] is
     * in stance. On the ground the lower toe in stance (nearer the camera)
     * becomes the followed one and its median stance height the baseline, with
     * the recent ground samples shifted along. Otherwise (flight, swing) the
     * toe nearest to where the followed one should be is followed; the baseline
     * stays, in flight it is the takeoff floor.
     */
    private fun followToes(tracking: JumpTrackingState, frame: Frame, timestamp: Double): Pair<JumpTrackingState, Frame> {
        if (frame.toes.isEmpty()) return tracking to frame
        val span = tracking.bodySpan
        val dt = tracking.previousTimestamp?.let { ((timestamp - it) / 1000).toFloat() }?.takeIf { it > 0f }
        val maxSpeed = Jump.APPROACH_STANCE_SPEED * span
        val timingLevel = Jump.TIMING_LEVEL * span

        val unmatched = tracking.toeTracks.toMutableList()
        val tracks = frame.toes.map { toe ->
            val before = if (dt == null) null else unmatched.minByOrNull { dist(it.x, it.y, toe.x, toe.y) }
            before?.let { unmatched.remove(it) }
            val slow = before != null &&
                kotlin.math.abs(toe.x - before.x) / dt!! < maxSpeed &&
                kotlin.math.abs(toe.y - before.y) / dt < maxSpeed
            if (before == null || !slow) {
                ToeTrack(toe.x, toe.y)
            } else {
                // A heel rolling off the floor must not pull the floor with it.
                val ys = if (before.stanceYs.size < 2 || kotlin.math.abs(toe.y - median(before.stanceYs)) <= timingLevel) {
                    before.stanceYs + toe.y
                } else {
                    before.stanceYs
                }
                val times = if (ys.size > before.stanceYs.size) before.stanceTimesMs + timestamp else before.stanceTimesMs
                // No fresh sample left: drop them all so a changed floor is picked up again.
                val fresh = times.indexOfFirst { timestamp - it <= Jump.APPROACH_STANCE_WINDOW_MS }.let { if (it < 0) times.size else it }
                ToeTrack(toe.x, toe.y, before.slowSinceMs ?: tracking.previousTimestamp, ys.drop(fresh), times.drop(fresh))
            }
        }
        val stance = tracks.filter {
            it.slowSinceMs != null && it.stanceYs.isNotEmpty() && timestamp - it.slowSinceMs >= Jump.APPROACH_STANCE_MS
        }
        val planted = stance.maxByOrNull { it.y }

        val previous = tracking.followedToe
        val followed = when {
            planted != null -> ToePoint(planted.x, planted.y)
            previous == null -> frame.toes.maxBy { it.y }
            else -> {
                val v = tracking.followedVelocity
                val expectedX = previous.x + if (dt != null && v != null) v.x * dt else 0f
                val expectedY = previous.y + if (dt != null && v != null) v.y * dt else 0f
                frame.toes.minBy { dist(it.x, it.y, expectedX, expectedY) }
            }
        }
        var next = tracking.copy(
            toeTracks = tracks,
            followedToe = followed,
            followedVelocity = if (dt != null && previous != null) {
                ToePoint((followed.x - previous.x) / dt, (followed.y - previous.y) / dt)
            } else {
                null
            },
            planted = stance.isNotEmpty()
        )
        if (!tracking.isAirborne) {
            next = next.copy(
                baselineHipsY = tracking.baselineHipsY + smoothing(Jump.BASELINE_SMOOTHING, tracking.previousTimestamp, timestamp) * (frame.hipsY - tracking.baselineHipsY)
            )
            if (planted != null) {
                val floor = median(planted.stanceYs)
                val shift = floor - tracking.baselineFootY
                next = next.copy(
                    baselineFootY = floor,
                    groundSamples = tracking.groundSamples.map { it.first to it.second + shift },
                    bodySpan = span + smoothing(Jump.APPROACH_SPAN_SMOOTHING, tracking.previousTimestamp, timestamp) * ((planted.y - frame.headY) - span)
                )
            }
        }
        return next to frame.copy(footY = followed.y)
    }

    private fun median(values: List<Float>) = values.sorted()[values.size / 2]

    private fun dist(x0: Float, y0: Float, x1: Float, y1: Float) = kotlin.math.hypot(x1 - x0, y1 - y0)

    private fun baselineFrom(frame: Frame, timestamp: Double) = JumpTrackingState(
        baselineFootY = frame.footY,
        baselineHipsY = frame.hipsY,
        bodySpan = frame.footY - frame.headY,
        previousFootY = frame.footY,
        previousHipsY = frame.hipsY,
        previousTimestamp = timestamp,
        upsideDown = frame.upsideDown
    )

    /**
     * Least-squares fit of rise = a + b·t + c·t² to the airborne samples; the
     * flight time is the distance between its roots. Null when there are too
     * few samples or the fit isn't a downward parabola.
     */
    /**
     * Least-squares parabola a + b·t + c·t² through (timestamp, rise) samples,
     * t in seconds from the first sample; null if too few samples or not
     * curving downward like a flight.
     */
    private fun fitParabola(samples: List<Pair<Double, Float>>): Triple<Double, Double, Double>? {
        if (samples.size < Jump.MIN_FIT_SAMPLES || samples.last().first - samples.first().first < Jump.MIN_FIT_MS) return null
        val t0 = samples.first().first
        // Power sums for the normal equations.
        val s = DoubleArray(5)
        val r = DoubleArray(3)
        for ((timestamp, rise) in samples) {
            val t = (timestamp - t0) / 1000.0
            var p = 1.0
            for (k in 0..4) {
                s[k] += p
                if (k <= 2) r[k] += p * rise
                p *= t
            }
        }
        val (a, b, c) = solve3(
            arrayOf(
                doubleArrayOf(s[0], s[1], s[2]),
                doubleArrayOf(s[1], s[2], s[3]),
                doubleArrayOf(s[2], s[3], s[4])
            ),
            r
        ) ?: return null
        return if (c < 0) Triple(a, b, c) else null
    }

    /**
     * Time of the top of the flight, from a parabola through the hips: the
     * trunk follows the center of mass, while the legs fold before landing.
     */
    private fun fitApexMs(hipSamples: List<Pair<Double, Float>>, outlierFloor: Float): Double? {
        val samples = withoutOutliers(hipSamples, outlierFloor)
        val (_, b, c) = fitParabola(samples) ?: return null
        return samples.first().first + (-b / (2 * c)) * 1000.0
    }

    /**
     * [samples] without up to [Jump.MAX_DROPPED_FRAMES] frames far off their
     * parabola: one mistracked frame bends the whole fit. Far is beyond
     * [Jump.OUTLIER_SIGMAS] robust standard deviations of the residuals, and
     * beyond [floor] so that frames are never dropped for ordinary jitter.
     */
    private fun withoutOutliers(samples: List<Pair<Double, Float>>, floor: Float): List<Pair<Double, Float>> {
        var kept = samples
        repeat(Jump.MAX_DROPPED_FRAMES) {
            val (a, b, c) = fitParabola(kept) ?: return kept
            val t0 = kept.first().first
            val residuals = kept.map { (timestamp, rise) ->
                val t = (timestamp - t0) / 1000.0
                kotlin.math.abs(rise - (a + b * t + c * t * t))
            }
            val sigma = 1.4826 * residuals.sorted()[residuals.size / 2]
            val worst = residuals.indices.maxBy { residuals[it] }
            if (residuals[worst] <= maxOf(Jump.OUTLIER_SIGMAS * sigma, floor.toDouble())) return kept
            kept = kept.filterIndexed { i, _ -> i != worst }
        }
        return kept
    }

    /**
     * Toe rise while resting on the floor before a takeoff at [takeoffMs]: the
     * median of the ground frames before the toes started to peel off; 0 (the
     * baseline) when there are none.
     */
    private fun restingToeRise(groundSamples: List<Pair<Double, Float>>, takeoffMs: Double): Float {
        val resting = groundSamples.filter { it.first <= takeoffMs - TOE_OFF_LEAD_MS }.map { it.second }.sorted()
        return if (resting.isEmpty()) 0f else resting[resting.size / 2]
    }

    /**
     * Gravity (m/s²) of the flight parabola: -2c in toe-rise units per s²,
     * over [spanPerMeter]. Null unless there are enough samples and the
     * parabola explains them (RMS residual small against the flight's rise).
     */
    private fun fitGravity(samples: List<Pair<Double, Float>>, spanPerMeter: Float): Float? {
        if (samples.size < Jump.MIN_GRAVITY_FIT_SAMPLES || spanPerMeter <= 0f) return null
        val (a, b, c) = fitParabola(samples) ?: return null
        val t0 = samples.first().first
        val rms = kotlin.math.sqrt(samples.sumOf { (timestamp, rise) ->
            val t = (timestamp - t0) / 1000.0
            val e = rise - (a + b * t + c * t * t)
            e * e
        } / samples.size)
        val range = samples.maxOf { it.second } - samples.minOf { it.second }
        if (range <= 0f || rms > Jump.MAX_GRAVITY_FIT_RESIDUAL * range) return null
        return (-2 * c / spanPerMeter).toFloat()
    }

    private fun fitFlightMs(samples: List<Pair<Double, Float>>, takeoffRise: Float, landingRise: Float): Float? {
        val (a, b, c) = fitParabola(samples) ?: return null
        // Takeoff is where the parabola rises through takeoffRise, landing
        // where it comes down through landingRise: the floor at each end.
        val takeoffDiscriminant = b * b - 4 * (a - takeoffRise) * c
        val landingDiscriminant = b * b - 4 * (a - landingRise) * c
        if (takeoffDiscriminant <= 0 || landingDiscriminant <= 0) return null
        val flightS = (kotlin.math.sqrt(takeoffDiscriminant) + kotlin.math.sqrt(landingDiscriminant)) / (-2 * c)
        return (flightS * 1000.0).toFloat()
    }

    /** Solves a 3x3 linear system by Cramer's rule; null if singular. */
    private fun solve3(m: Array<DoubleArray>, v: DoubleArray): Triple<Double, Double, Double>? {
        fun det(x: Array<DoubleArray>) =
            x[0][0] * (x[1][1] * x[2][2] - x[1][2] * x[2][1]) -
                x[0][1] * (x[1][0] * x[2][2] - x[1][2] * x[2][0]) +
                x[0][2] * (x[1][0] * x[2][1] - x[1][1] * x[2][0])
        val d = det(m)
        if (kotlin.math.abs(d) < 1e-12) return null
        fun replaced(col: Int) = Array(3) { i -> DoubleArray(3) { j -> if (j == col) v[i] else m[i][j] } }
        return Triple(det(replaced(0)) / d, det(replaced(1)) / d, det(replaced(2)) / d)
    }

    /** Time at which the toe rise crossed [level], linearly interpolated between two frames. */
    private fun crossingTime(rise0: Float, t0: Double, rise1: Float, t1: Double, level: Float): Double {
        if (rise1 == rise0) return t1
        val fraction = ((level - rise0) / (rise1 - rise0)).coerceIn(0f, 1f)
        return t0 + fraction.toDouble() * (t1 - t0)
    }

    /**
     * Jump height from the time between takeoff and [apexMs]: h = g·t² / 2.
     *
     * Takeoff isn't interpolated between frames (the frame before it shows
     * the toes still on the ground, which biases a linear crossing early).
     * Instead, the first airborne frame says how high the toes already are,
     * the time left to the apex how fast they rise (v = g·Δt), and projectile
     * motion how long ago they left the ground.
     */
    private fun heightFromTakeoffToApex(
        firstOff: Pair<Double, Float>,
        lastOnFloorMs: Double?,
        apexMs: Double,
        spanPerMeter: Float
    ): Meters? {
        val (frameMs, rise) = firstOff
        val toApexS = (apexMs - frameMs) / 1000.0
        if (toApexS <= 0) return null
        val heightM = rise / spanPerMeter
        val velocity = GRAVITY * toApexS
        val sinceTakeoffS = (kotlin.math.sqrt(velocity * velocity + 2 * GRAVITY * heightM) - velocity) / GRAVITY
        // Not before the last frame that still showed the toes on the floor.
        val takeoffMs = maxOf(frameMs - sinceTakeoffS * 1000, lastOnFloorMs ?: Double.NEGATIVE_INFINITY)
        return JumpData.heightFromRiseTime((apexMs - takeoffMs).toFloat())
    }

    /** Head height above where it stood, from the standing toe level and span. */
    private fun headRise(tracking: JumpTrackingState, frame: Frame): Float =
        (tracking.baselineFootY - tracking.bodySpan) - frame.headY

    private fun readFrame(poseResult: PoseResult): Frame? {
        val landmarks = poseResult.landmarks
        // Lower than the usual threshold: in mid-air the feet and knees drop to
        // 0.4-0.5 visibility while their positions stay accurate, and losing
        // them breaks the flight apart or hides it entirely.
        fun visible(index: Int): PoseKeypoint? =
            landmarks.getOrNull(index)?.takeIf { it.visibility >= Jump.MIN_VISIBILITY }
        fun bothVisible(left: Int, right: Int) = listOfNotNull(visible(left), visible(right))

        val head = visible(PoseResult.NOSE)?.y ?: return null
        val feetPoints = bothVisible(PoseResult.LEFT_FOOT_INDEX, PoseResult.RIGHT_FOOT_INDEX)
            .ifEmpty { bothVisible(PoseResult.LEFT_ANKLE, PoseResult.RIGHT_ANKLE) }
            .ifEmpty { return null }
        val hipPoints = bothVisible(PoseResult.LEFT_HIP, PoseResult.RIGHT_HIP).ifEmpty { return null }

        // Track the lowest foot and hip, not the average: side-on the far one
        // sits higher in the image and its visibility flickers, so averaging
        // it in and out makes the feet jump by centimeters. The lowest toe is
        // also the last to leave the ground and the first to touch it.
        // Camera upside down: "lowest" is the smallest y, then flip so that
        // larger y is always lower.
        val upsideDown = head > feetPoints.map { it.y }.average()
        fun lowest(points: List<PoseKeypoint>) =
            if (upsideDown) 1f - points.minOf { it.y } else points.maxOf { it.y }
        val feet = lowest(feetPoints)
        val hips = lowest(hipPoints)
        val aspect = if (poseResult.imageHeight > 0) poseResult.imageWidth.toFloat() / poseResult.imageHeight else 1f
        val toes = feetPoints.map { ToePoint(it.x * aspect, if (upsideDown) 1f - it.y else it.y) }
        return Frame(if (upsideDown) 1f - head else head, hips, feet, upsideDown, toes)
    }

    override fun calculateMetrics(
        poseResult: PoseResult,
        velocityData: VelocityData,
        weight: Kilograms
    ): ExerciseMetrics {
        val peakVelocity = velocityData.instantaneous.abs()
        val force = weight.value * 9.81f
        return ExerciseMetrics(
            displacement = velocityData.position,
            peakVelocity = peakVelocity,
            avgPower = Watts(force * velocityData.average.abs().value),
            peakPower = Watts(force * peakVelocity.value),
            work = Joules(force * velocityData.position.value)
        )
    }

    /**
     * Peak power of [jump], by the Sayers equation (Sayers et al. 1999):
     * P[W] = 60.7·h[cm] + 45.3·m[kg] − 2055, with h the height from flight
     * time and m the system mass: body mass from the calibration, plus [load]
     * for a loaded jump (a bodyweight jump ignores whatever load was entered).
     *
     * Sayers rather than impulse-momentum: peak power from takeoff velocity
     * needs the force over the propulsive phase, and from video that means
     * timing the start of the push-off and assuming a force profile — far
     * noisier than flight time, our most robust measure, which is all Sayers
     * needs. It was fitted to bodyweight squat and countermovement jumps; for
     * loaded jumps the system mass is an extrapolation, fine for tracking
     * change over time, not for comparing against force-plate numbers.
     *
     * Null without a body mass, or when the formula leaves its range (≤ 0 W),
     * rather than a wrong number. [calculateMetrics] reports the mechanical
     * load × velocity product for every lift and has neither body mass nor
     * flight time, so jump power comes from here.
     */
    fun peakPower(jump: JumpData, calibrationData: CalibrationData, load: Kilograms): Watts? {
        val systemMass = systemMass(calibrationData, load) ?: return null
        val power = 60.7f * jump.height.value * 100f + 45.3f * systemMass - 2055f
        return if (power > 0f) Watts(power) else null
    }

    /**
     * Mean push-off force, velocity and power of [jump] by Samozino's simple
     * method (see [JumpMechanics]), with the push-off distance measured from
     * its center-of-mass [excursion] and the same system mass as [peakPower].
     *
     * Null without a body mass, without an excursion (never seen standing, or
     * an approach jump, whose dip from standing means nothing after a run-up),
     * or when the measured push-off is implausible. No assumed push-off
     * distance stands in: a guessed hPO moves the force as much as the jump.
     */
    fun pushOffMechanics(
        jump: JumpData,
        excursion: JumpExcursion?,
        calibrationData: CalibrationData,
        load: Kilograms
    ): JumpMechanics? {
        if (approach || excursion == null) return null
        val systemMass = systemMass(calibrationData, load) ?: return null
        val pushOffCm = JumpMechanics.pushOffCm(excursion) ?: return null
        return JumpMechanics.of(jump.height.value, pushOffCm / 100f, systemMass)
    }

    /** Body mass from the calibration, plus [load] for a loaded jump; null without a body mass. */
    private fun systemMass(calibrationData: CalibrationData, load: Kilograms): Float? {
        val bodyMass = calibrationData.anthropometricData?.userWeight?.takeIf { it > 0f } ?: return null
        return bodyMass + if (exerciseType == ExerciseType.LOADED_JUMP) load.value else 0f
    }

    override fun validateForm(
        poseResult: PoseResult,
        calibrationData: CalibrationData,
        currentState: ExercisePhaseState?,
        phase: MovementPhase?
    ): FormValidationResult = FormValidationResult(isValid = true, issues = emptyList())

    override fun validateRep(
        frames: List<AnalyzeRepUseCase.RepFrame>,
        calibrationData: CalibrationData,
        currentState: ExercisePhaseState?
    ): List<FormIssue> = listOfNotNull(checkLanding(frames))

    /**
     * Stiff landing: the knees bend less than [Jump.STIFF_LANDING_KNEE_FLEXION_DEG]
     * within [Jump.LANDING_WINDOW_MS] of touchdown. Touchdown is the first frame
     * after the flight (the phase leaves TOP when the feet are back down);
     * knee flexion is read on the near side. Time-based, so it holds at any fps.
     * No flight, or no knee seen after it, is no finding.
     */
    private fun checkLanding(frames: List<AnalyzeRepUseCase.RepFrame>): FormIssue? {
        val lastAirborne = frames.indexOfLast { it.phase == MovementPhase.TOP }
        val touchdown = frames.getOrNull(lastAirborne + 1)?.takeIf { lastAirborne >= 0 } ?: return null
        val startUs = touchdown.pose.timestampUs
        val peakFlexion = frames.drop(lastAirborne + 1)
            .takeWhile { it.phase != MovementPhase.TOP && it.pose.timestampUs - startUs <= Jump.LANDING_WINDOW_MS * 1000 }
            .mapNotNull { kneeFlexion(it.pose) }
            .maxOrNull() ?: return null
        if (peakFlexion >= Jump.STIFF_LANDING_KNEE_FLEXION_DEG) return null
        return FormIssue(
            severity = Severity.WARNING,
            message = "Stiff landing: knees bent only %.0f°".format(peakFlexion),
            suggestion = "Land softly: let the hips and knees bend to absorb the landing",
            kind = "stiff_landing"
        )
    }

    /** Near-side knee flexion in degrees (0 = straight), or null if the leg isn't visible. */
    private fun kneeFlexion(pose: PoseResult): Float? {
        val (hip, knee, ankle) = nearSideLandmarks(
            pose,
            left = listOf(PoseResult.LEFT_HIP, PoseResult.LEFT_KNEE, PoseResult.LEFT_ANKLE),
            right = listOf(PoseResult.RIGHT_HIP, PoseResult.RIGHT_KNEE, PoseResult.RIGHT_ANKLE),
            minVisibility = Jump.MIN_VISIBILITY
        ) ?: return null
        val sx = pose.aspectScaleX
        val thigh = kotlin.math.atan2((hip.y - knee.y).toDouble(), ((hip.x - knee.x) * sx).toDouble())
        val shin = kotlin.math.atan2((ankle.y - knee.y).toDouble(), ((ankle.x - knee.x) * sx).toDouble())
        var angle = Math.toDegrees(kotlin.math.abs(thigh - shin))
        if (angle > 180) angle = 360 - angle
        return (180 - angle).toFloat()
    }

    override fun getRequiredLandmarks(): List<Int> = criteria.targetBodyParts

    override fun getCriteria(): ExerciseCriteria = criteria
}
