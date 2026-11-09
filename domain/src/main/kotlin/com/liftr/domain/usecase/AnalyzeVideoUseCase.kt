package com.liftr.domain.usecase

import com.liftr.domain.calculator.CameraView
import com.liftr.domain.calculator.CameraViewClassifier
import com.liftr.domain.calculator.JumpMechanics
import com.liftr.domain.exercise.JumpPathWindow
import com.liftr.domain.calculator.CenterOfMassCalculator
import com.liftr.domain.exercise.normalizedXY
import com.liftr.domain.exercise.trackingPoint
import com.liftr.domain.exercise.BarPathAnalyzer
import com.liftr.domain.exercise.BidirectionalVelocityCalculator
import com.liftr.domain.exercise.ExercisePhaseState
import com.liftr.domain.exercise.ExerciseStrategy
import com.liftr.domain.exercise.ExerciseStrategyFactory
import com.liftr.domain.exercise.FormIssue
import com.liftr.domain.exercise.PhaseTransitionResult
import com.liftr.domain.exercise.RepQualityScorer
import com.liftr.domain.exercise.RepSegmenter
import com.liftr.domain.exercise.SquatDepthValidator
import com.liftr.domain.exercise.TempoAdherence
import com.liftr.domain.exercise.TempoState
import com.liftr.domain.exercise.TempoTracker
import com.liftr.domain.exercise.strategy.BackSquatStrategy
import com.liftr.domain.exercise.strategy.FrontSquatStrategy
import com.liftr.domain.exercise.strategy.JumpStrategy
import com.liftr.domain.exercise.strategy.SquatStrategyBase
import com.liftr.domain.exercise.ExerciseThresholds
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.AggregateFormIssue
import com.liftr.domain.model.aggregateFormIssues
import com.liftr.domain.model.BarPathData
import com.liftr.domain.model.BarPathPoint
import com.liftr.domain.model.BarTrackSource
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.CatchType
import com.liftr.domain.model.DepthValidationResult
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.FrameAnnotation
import com.liftr.domain.model.JumpQuality
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import com.liftr.domain.model.RepQualityScore
import com.liftr.domain.model.SetSummary
import com.liftr.domain.model.SquatDepthType
import com.liftr.domain.model.TempoPrescription
import com.liftr.domain.model.VbtZone
import com.liftr.domain.model.VelocityData
import com.liftr.domain.repository.SettingsKeys
import com.liftr.domain.repository.SettingsRepository
import com.liftr.domain.value.Kilograms
import com.liftr.domain.value.Meters
import com.liftr.domain.value.MetersPerSecond
import com.liftr.domain.value.RepCount
import com.liftr.domain.value.Watts
import com.liftr.domain.vision.IPositionCalculator
import com.liftr.domain.vision.PlateObservation
import com.liftr.domain.vision.PoseSanity
import com.liftr.domain.video.VideoSpeedCheck
import com.liftr.domain.vision.VideoPoseSource
import com.liftr.domain.model.MovementPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Analyzes a recorded workout video offline using the heavy pose model
 * (Feature 17 — post-exercise analysis).
 *
 * The pipeline deliberately reuses the same domain logic as the real-time path
 * (`ExerciseStrategy`, [RepQualityScorer], [BarPathAnalyzer], [TempoTracker],
 * [AnalyzeRepUseCase]) so the produced [SetSummary] is byte-for-byte compatible
 * with the live pipeline's output and renders in the identical summary sheet.
 *
 * 1. Decode frames and detect pose per frame ([VideoPoseSource], heavy model).
 * 2. Smooth velocities bidirectionally ([BidirectionalVelocityCalculator]).
 * 3. Walk frames: phase detection + rep counting (`ExerciseStrategy`). Squats,
 *    bench press and deadlift instead take reps and phases from their bar track
 *    ([RepSegmenter]), the plate when it was tracked, else the landmarks.
 * 4. Per rep: form validation ([AnalyzeRepUseCase]), quality scoring, bar path.
 * 5. Assemble a [SetSummary].
 */
class AnalyzeVideoUseCase @Inject constructor(
    private val positionCalculator: IPositionCalculator,
    private val strategyFactory: ExerciseStrategyFactory,
    private val analyzeRepUseCase: AnalyzeRepUseCase,
    private val settingsRepository: SettingsRepository,
    private val logger: Logger
) {

    data class Params(
        val videoFile: File,
        val exerciseType: ExerciseType,
        val weight: Kilograms,
        val targetReps: Int,
        val calibrationData: CalibrationData,
        val tempoPrescription: TempoPrescription? = null
    )

    /** Progress reported during the (potentially long) pose-detection phase. */
    data class Progress(
        val currentFrame: Int,
        val totalFrames: Int,
        val percentComplete: Float,
        val currentRep: Int
    )

    /**
     * Run the full post-exercise analysis.
     *
     * @param params Video file, exercise config, and calibration
     * @param poseSource Per-frame poses for the video; closed by this call
     * @param onProgress Per-frame progress callback
     * @param onFrameAnnotation Optional sink for per-frame overlay data, used to
     *   build the shareable annotated video (Feature 09). Called once per frame
     *   that produced a pose, in presentation order.
     */
    /**
     * Decodes the whole recording and runs pose detection over every frame.
     *
     * Pinned to [Dispatchers.Default]: this is minutes of frame extraction and
     * per-frame ML inference. Callers dispatch from a service `lifecycleScope`,
     * which is `Dispatchers.Main.immediate`, so without this switch the entire
     * analysis executes on the UI thread of the same process and ANRs the app.
     *
     * [onProgress] and [onFrameAnnotation] are therefore invoked on a background
     * thread — they must only touch thread-safe sinks (a StateFlow, the
     * notification manager), never Views.
     */
    suspend fun invoke(
        params: Params,
        poseSource: VideoPoseSource,
        onProgress: (Progress) -> Unit,
        onFrameAnnotation: ((FrameAnnotation) -> Unit)? = null
    ): Result<SetSummary> = withContext(Dispatchers.Default) {
        try {
            logger.i(TAG, "Analyzing ${params.videoFile.name} exercise=${params.exerciseType} weight=${params.weight.value}")

            configureSquatDepth(params.exerciseType)

            // --- Phase 1: extract frames + detect poses ---
            // High-speed video (120/240 fps) has too many frames to run the pose
            // model on all of them. Pass 1 analyzes a steady subset; for jumps,
            // pass 2 then analyzes every frame around each takeoff and landing,
            // where the flight timing comes from.
            val detected = try {
                val coarseShare = if (params.exerciseType.isJumpExercise()) COARSE_PASS_SHARE else 1f
                val coarse = detectPoses(
                    poseSource, everyMs(coarseIntervalMs(params.exerciseType)), onProgress, 0f, coarseShare
                )
                val windows = if (params.exerciseType.isJumpExercise()) jumpWindows(params, coarse.poses) else emptyList()
                if (windows.isEmpty() || coarse.skippedFrames == 0) {
                    coarse
                } else {
                    val analyzed = coarse.poses.mapTo(HashSet()) { it.timestampUs }
                    val dense = detectPoses(
                        poseSource,
                        { t -> t !in analyzed && windows.any { w -> t / 1_000 in w } },
                        onProgress, COARSE_PASS_SHARE, 1f
                    )
                    logger.i(TAG, "Dense pass: ${dense.poses.size} poses around ${windows.size} takeoffs/landings")
                    PassResult(
                        (coarse.poses + dense.poses).sortedBy { it.timestampUs },
                        coarse.plates + dense.plates,
                        coarse.skippedFrames
                    )
                }
            } finally {
                poseSource.close()
            }
            val poseResults = detected.poses
            // Barbell plate per analyzed frame time (ms), when the source tracks it.
            val plateTrack = detected.plates

            val timeScale = poseSource.timeScale
            logger.i(TAG, "Pose detection complete: ${poseResults.size} poses, ${plateTrack.size} plate observations")
            if (poseResults.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("No poses detected in video"))
            }

            // --- Phase 2: bidirectional velocities over the whole set ---
            // The scale locks frame by frame, as it does live, in a calculator
            // of this analysis' own: the app's is shared with live sets, whose
            // resetScale() must not move this scale halfway through.
            val calculator = positionCalculator.newSession()
            // Frames whose tracked joint is occluded produce no position, and for
            // squats a broken skeleton or an impossible jump is dropped too.
            // Never substitute 0: a fabricated zero is a ~1.4 m jump from a real
            // bar position, which reads as a ~40 m/s spike and wrecks peak
            // velocity, velocity loss and the VBT zones. The list stays 1:1 with
            // poseResults, which buildSummary relies on.
            val isSquat = params.exerciseType.isSquat()
            if (isSquat) calculator.lockScaleToStanding(poseResults, params.calibrationData)
            // Most of a set is spent standing: the median torso of the upright
            // frames is the standing one a folded skeleton is measured against.
            val standingTorso = if (isSquat) {
                poseResults.filter { PoseSanity.isPlausible(it) }.mapNotNull { PoseSanity.torsoHeight(it) }
                    .sorted().let { it.getOrNull(it.size / 2) }
            } else null
            val lostBecause = arrayOfNulls<String>(poseResults.size)
            val measured = poseResults.mapIndexed { i, pose ->
                val position = trackingPositionMeters(pose, params, calculator)
                when {
                    position == null -> null.also { lostBecause[i] = "tracking point not visible" }
                    isSquat && !PoseSanity.isPlausible(pose, standingTorso) -> null.also { lostBecause[i] = "sanity" }
                    else -> position
                }
            }
            val timesUs = poseResults.map { it.timestampUs }
            val kept = if (isSquat) dropTrackJumps(timesUs, measured, PoseSanity.MAX_SQUAT_SPEED_MPS) else measured
            kept.forEachIndexed { i, p -> if (p == null && measured[i] != null) lostBecause[i] = "track jump" }
            val track = fillTrackGaps(timesUs, kept, MAX_INTERPOLATED_GAP_US)
            logger.i(TAG, "Tracking: ${track.missing} of ${measured.size} frames without a usable position")
            logTrackingGaps(timesUs, lostBecause.toList())
            val positionSamples = poseResults.mapIndexed { i, pose ->
                BidirectionalVelocityCalculator.PositionSample(
                    positionMeters = track.positions[i],
                    timestampMs = pose.timestamp,
                    timestampUs = pose.timestampUs
                )
            }
            // A plate-tracked lift's kinematics come from its bar track: the plate
            // when it was followed, else the landmark track above.
            val barTrack = if (isSquat || params.exerciseType.tracksBarPlate()) barTrack(params, poseResults, plateTrack, kept) else null
            val velocities = barTrack?.velocities() ?: BidirectionalVelocityCalculator.calculateVelocities(positionSamples)

            // --- Phase 3: phase detection, rep counting, scoring ---
            // Strategies convert positions again, from the first frame on, with
            // the app's calculator (a bench or deadlift's form checks still run
            // on them). A bar track's phases and reps replace the strategy's,
            // and a squat's depth checks read the hips and knees with this
            // analysis' calculator, its scale locked over the whole set.
            if (!isSquat) positionCalculator.resetScale()
            val summary = buildSummary(
                params, poseResults, velocities, onProgress, onFrameAnnotation, timeScale,
                barTrack, SquatContext(calculator, standingTorso, if (isSquat) metersPerUnit(poseResults, params) else emptyList())
            )

            logger.i(TAG, "Analysis complete: ${summary.completedReps.value} reps, score=${summary.overallScore}")
            Result.success(summary)
        } catch (e: Exception) {
            logger.e(TAG, "Analysis failed", e)
            Result.failure(e)
        }
    }

    private class PassResult(
        val poses: List<PoseResult>,
        val plates: Map<Long, PlateObservation>,
        val skippedFrames: Int
    )

    /** Runs one decoding pass, analyzing the frames [include] accepts. */
    private suspend fun detectPoses(
        poseSource: VideoPoseSource,
        include: (timestampUs: Long) -> Boolean,
        onProgress: (Progress) -> Unit,
        progressFrom: Float,
        progressTo: Float
    ): PassResult {
        val poses = mutableListOf<PoseResult>()
        val plates = HashMap<Long, PlateObservation>()
        var frameIndex = 0
        var skipped = 0
        poseSource.poses { t -> include(t).also { if (!it) skipped++ } }.collect { frame ->
            frame.pose?.let { poses.add(it) }
            frame.plate?.let { plates[frame.timestampMs] = it }
            frameIndex++
            val total = poseSource.totalFrames.coerceAtLeast(1)
            val passFraction = (frameIndex.toFloat() / total).coerceIn(0f, 1f)
            onProgress(
                Progress(
                    // A later pass revisits frames already counted: hold the counter at the total.
                    currentFrame = if (progressFrom > 0f) total else frameIndex.coerceAtMost(total),
                    totalFrames = total,
                    percentComplete = progressFrom + (progressTo - progressFrom) * passFraction,
                    currentRep = 0
                )
            )
        }
        return PassResult(poses, plates, skipped)
    }

    /** Accepts frames (by µs time) at least [intervalMs] apart, thinning faster video to that rate. */
    private fun everyMs(intervalMs: Long): (Long) -> Boolean {
        var last = Long.MIN_VALUE / 2
        return { t -> (t - last >= intervalMs * 1_000).also { if (it) last = t } }
    }

    /**
     * Pass 1 frame spacing: ~30 fps for jumps (pass 2 adds every frame around
     * takeoff and landing), ~60 fps for other fast movements, ~30 fps for lifts.
     * Slightly under the nominal interval so 30/60 fps video keeps every frame.
     */
    private fun coarseIntervalMs(exerciseType: ExerciseType): Long =
        if (exerciseType.isFastMovement() && !exerciseType.isJumpExercise()) 15L else 30L

    /**
     * Time windows around each takeoff and landing found in the [coarse] poses.
     * A rebound's ground contact is timed from the landing crossing to the next
     * takeoff crossing, each inside its own window; the frames between, with
     * the feet on the floor, time nothing and may stay coarse.
     */
    private fun jumpWindows(params: Params, coarse: List<PoseResult>): List<LongRange> {
        val strategy = strategyFactory.getStrategy(params.exerciseType)
        var state = ExercisePhaseState.initial()
        val windows = mutableListOf<LongRange>()
        for (pose in coarse) {
            val transition = strategy.detectPhase(pose, params.calibrationData, state)
            state = transition.newState
            val jump = transition.jump
            if (transition.repCompleted && jump != null) {
                val landingMs = pose.timestamp
                val takeoffMs = landingMs - jump.flightTimeMs.toLong()
                windows += (takeoffMs - DENSE_WINDOW_MS)..(takeoffMs + DENSE_WINDOW_MS)
                windows += (landingMs - DENSE_WINDOW_MS)..(landingMs + DENSE_WINDOW_MS)
            }
        }
        return windows
    }

    private suspend fun buildSummary(
        params: Params,
        poseResults: List<PoseResult>,
        velocities: List<VelocityData>,
        onProgress: (Progress) -> Unit,
        onFrameAnnotation: ((FrameAnnotation) -> Unit)? = null,
        timeScale: Float = 1f,
        barTrack: BarTrack? = null,
        squatContext: SquatContext? = null
    ): SetSummary {
        val strategy = strategyFactory.getStrategy(params.exerciseType)
        // A plate-tracked lift's phases and reps come from its bar track, not the
        // strategy's state machine. A rep completes at its end: a deadlift's at
        // lockout (the pull), the others' when the bar is back at the top.
        val squatPhases = barTrack?.phases()
        val squatRepsByEnd = barTrack?.reps.orEmpty().associateBy { barTrack?.completionIndex(it) }
        // Bench and deadlift have no depth to judge from the bar track, but the
        // strategy still validates their form (the deadlift's lockout): its
        // verdicts are matched to the bar track's reps by time.
        val strategyVerdicts = if (barTrack != null && strategy !is SquatStrategyBase) {
            strategyVerdicts(strategy, params, poseResults)
        } else emptyMap()
        val repEccentricVelocities = mutableListOf<MetersPerSecond>()
        val repPauseMs = mutableListOf<Long>()
        val repEccentricDurations = mutableListOf<Float>()
        val repConcentricDurations = mutableListOf<Float>()
        val repRangeOfMotion = mutableListOf<Meters>()
        var phaseState = ExercisePhaseState.initial()
        var previousPhase = MovementPhase.IDLE

        val repScores = mutableListOf<RepQualityScore>()
        val repVelocities = mutableListOf<MetersPerSecond>()
        val repPeakVelocities = mutableListOf<MetersPerSecond>()
        val repBarPaths = mutableListOf<BarPathData>()
        val repJumpHeights = mutableListOf<Meters>()
        val repApexHeights = mutableListOf<Meters?>()
        val repFittedGravities = mutableListOf<Float>()
        val repPeakPowers = mutableListOf<Watts?>()
        val repCatchTypes = mutableListOf<CatchType?>()
        var lastCatchType: CatchType? = null
        val repJumpQualities = mutableListOf<JumpQuality>()
        val repMechanics = mutableListOf<JumpMechanics?>()
        val repContactTimesMs = mutableListOf<Float?>()
        val repReactiveStrengthIndices = mutableListOf<Float?>()
        val previousConcentricDurations = mutableListOf<Float>()

        var currentRepBarPoints = mutableListOf<BarPathPoint>()
        var currentRepPoses = mutableListOf<AnalyzeRepUseCase.RepFrame>()
        var currentRepDepthValidation: DepthValidationResult? = null
        var eccentricStart = 0L
        var bottomTime = 0L
        var repVelocitySum = 0f
        var repVelocityCount = 0
        var repVelocityPeak = 0f

        var tempoState: TempoState? = params.tempoPrescription?.let { TempoTracker.reset(it) }
        val jumpPath = if (params.exerciseType.isJumpExercise()) JumpPathWindow() else null
        var headOutOfFrame = false
        // Shoulder width while standing tells whether the set was filmed side-on.
        val standingShoulderRatios = mutableListOf<Float>()
        // A jump counts on landing, before the knees bend: its form is checked
        // again once the landing window has passed, with those frames added.
        // The next flight ends the window early: its landing isn't this rep's.
        var landing: PendingLanding? = null
        suspend fun finishLanding() {
            landing?.let { repScores[it.repIndex] = it.rescore(it.frames) }
            landing = null
        }

        for (i in poseResults.indices) {
            val pose = poseResults[i]
            val velocity = velocities.getOrNull(i)
            val now = pose.timestamp

            val squatRep = squatRepsByEnd[i]
            val transition = if (squatPhases != null) {
                PhaseTransitionResult(
                    newState = phaseState.withCurrentPhase(squatPhases[i]),
                    repCompleted = squatRep != null,
                    depthValidation = squatRep?.let {
                        squatDepth(it, strategy, params, poseResults, squatContext)
                            ?: strategyVerdicts.entries.firstOrNull { v -> v.key in it.eccentricStart..it.concentricEnd }?.value
                    }
                )
            } else strategy.detectPhase(pose, params.calibrationData, phaseState)
            val newPhase = transition.newState.currentPhase
            // Only accepted jumps: a discarded flight's fit says nothing about the video speed.
            if (transition.repCompleted) transition.flightGravity?.let(repFittedGravities::add)
            if (transition.headLeftFrameInFlight) headOutOfFrame = true
            phaseState = transition.newState
            landing?.let {
                if (pose.timestampUs <= it.untilUs && newPhase != MovementPhase.TOP) it.frames += AnalyzeRepUseCase.RepFrame(pose, newPhase) else finishLanding()
            }
            if (params.exerciseType.canJudgeCameraView() && params.exerciseType.isStandingPhase(newPhase)) {
                CameraViewClassifier.shoulderWidthRatio(pose)?.let { standingShoulderRatios += it }
            }

            // A deadlift rep is the pull: it starts leaving the floor and ends at
            // lockout, so the lowering afterwards isn't part of it. Timing the pull
            // from here makes it the rep's concentric duration.
            if (params.exerciseType.startsWithConcentric() &&
                (previousPhase == MovementPhase.IDLE || (barTrack != null && previousPhase == MovementPhase.ECCENTRIC)) &&
                newPhase == MovementPhase.CONCENTRIC
            ) {
                currentRepBarPoints = mutableListOf()
                currentRepPoses = mutableListOf()
                currentRepDepthValidation = null
                eccentricStart = 0L
                bottomTime = now
                repVelocitySum = 0f
                repVelocityCount = 0
                repVelocityPeak = 0f
            }

            if (newPhase != previousPhase) {
                when (newPhase) {
                    MovementPhase.ECCENTRIC -> {
                        currentRepBarPoints = mutableListOf()
                        currentRepPoses = mutableListOf()
                        currentRepDepthValidation = null
                        eccentricStart = now
                        bottomTime = 0L
                        repVelocitySum = 0f
                        repVelocityCount = 0
                        repVelocityPeak = 0f
                        params.tempoPrescription?.let { presc ->
                            tempoState = TempoTracker.onPhaseTransition(
                                TempoTracker.reset(presc), newPhase, now
                            )
                        }
                    }
                    MovementPhase.BOTTOM -> {
                        bottomTime = now
                        transition.depthValidation?.let { currentRepDepthValidation = it }
                        tempoState?.let { tempoState = TempoTracker.onPhaseTransition(it, newPhase, now) }
                    }
                    else -> {
                        tempoState?.let { tempoState = TempoTracker.onPhaseTransition(it, newPhase, now) }
                    }
                }
            }

            val pathPoint = extractBarPathPoint(pose, velocity, params)
            jumpPath?.onFrame(
                pathPoint, newPhase, now,
                JumpPathWindow.cmPerUnitY(pose, params.calibrationData.userHeight),
                nowUs = pose.timestampUs
            )
            if (newPhase in ACTIVE_PHASES) pathPoint?.let { currentRepBarPoints.add(it) }
            // A jump's form check also needs its flight, to find the touchdown.
            if (newPhase in ACTIVE_PHASES || (jumpPath != null && newPhase == MovementPhase.TOP)) {
                currentRepPoses.add(AnalyzeRepUseCase.RepFrame(pose, newPhase))
            }

            if (velocity != null && params.exerciseType.isConcentricPhase(newPhase)) {
                repVelocitySum += velocity.instantaneous.value
                repVelocityCount++
                repVelocityPeak = maxOf(repVelocityPeak, velocity.instantaneous.value)
            }

            if (transition.repCompleted) {
                finishLanding()
                // A rep complete at lockout (deadlift) reports its lockout here, scored as its depth part.
                transition.depthValidation?.let { currentRepDepthValidation = it }
                // A jump's path runs through its flight, which ACTIVE_PHASES leaves out.
                val jumpWindow = transition.jump?.let {
                    jumpPath?.pathForLanding(now, it.flightTimeMs, it.takeoffAtMs, it.landingAtMs)
                }
                jumpWindow?.let { window ->
                    currentRepBarPoints = window.points.toMutableList()
                    window.excursion?.let {
                        logger.i(
                            TAG,
                            ("Jump ${repScores.size + 1}: dip %.1f cm, peak %.1f cm above standing, " +
                                "center of mass rise %s, takeoff velocity %s").format(
                                it.countermovementCm, it.peakRiseCm,
                                it.flightRiseCm?.let { cm -> "%.1f cm".format(cm) } ?: "n/a",
                                it.takeoffVelocity?.let { v -> "%.2f m/s (%.1f cm)".format(v, it.velocityHeightCm) } ?: "n/a"
                            )
                        )
                        logger.i(TAG, "Jump ${repScores.size + 1} fit check: ${it.fitCheck?.describe() ?: "n/a"}")
                    }
                }
                val eccentricDuration = when {
                    squatRep != null -> squatRep.eccentricDurationS
                    bottomTime > 0 && eccentricStart > 0 -> (bottomTime - eccentricStart) / 1000f
                    else -> 0f
                }
                val concentricDuration = when {
                    squatRep != null -> squatRep.concentricDurationS
                    bottomTime > 0 -> (now - bottomTime) / 1000f
                    else -> 0f
                }

                val tempoAdherence: TempoAdherence? = tempoState?.let { ts ->
                    val finalized = TempoTracker.onPhaseTransition(ts, MovementPhase.IDLE, now)
                    tempoState = TempoTracker.reset(ts.prescription)
                    TempoTracker.calculateAdherence(finalized)
                }

                val depthValidation = currentRepDepthValidation
                val barXPositions = currentRepBarPoints.map { it.normalizedX }
                val earlierConcentricDurations = previousConcentricDurations.toList()
                val scoreRep: suspend (List<AnalyzeRepUseCase.RepFrame>) -> RepQualityScore = { frames ->
                    val analysis = runCatching {
                        analyzeRepUseCase(
                            AnalyzeRepUseCase.Params(
                                exerciseType = params.exerciseType,
                                calibrationData = params.calibrationData,
                                weight = params.weight,
                                bufferedFrames = frames,
                                finalVelocityData = velocity
                            )
                        )
                    }.getOrNull()
                    val formIssues: List<FormIssue> = analysis?.formValidation?.issues ?: emptyList()
                    lastCatchType = analysis?.catchType

                    RepQualityScorer.scoreRep(
                        exerciseType = params.exerciseType,
                        depthValidation = depthValidation,
                        barXPositions = barXPositions,
                        eccentricDuration = eccentricDuration,
                        concentricDuration = concentricDuration,
                        previousConcentricDurations = earlierConcentricDurations,
                        formIssues = formIssues,
                        tempoAdherence = tempoAdherence
                    )
                }
                val repFrames = currentRepPoses.toList()
                repScores.add(scoreRep(repFrames))
                repCatchTypes.add(lastCatchType)
                if (jumpPath != null && transition.jump != null) {
                    landing = PendingLanding(
                        repIndex = repScores.lastIndex,
                        untilUs = pose.timestampUs + ExerciseThresholds.Jump.LANDING_WINDOW_MS * 1000,
                        frames = (repFrames + AnalyzeRepUseCase.RepFrame(pose, newPhase)).toMutableList(),
                        rescore = scoreRep
                    )
                    // Without a dip to reset it, the next jump would inherit this flight.
                    currentRepPoses = mutableListOf()
                }
                previousConcentricDurations.add(concentricDuration)

                repBarPaths.add(
                    BarPathAnalyzer.analyze(
                        repNumber = repScores.size,
                        points = currentRepBarPoints.toList(),
                        // The dip from standing means nothing after a run-up.
                        excursion = jumpWindow?.excursion.takeUnless { params.exerciseType == ExerciseType.APPROACH_JUMP }
                    )
                )

                // A jump's takeoff velocity (from flight time) is its rep velocity,
                // so VBT zones and velocity loss work the same way for jumps.
                val jump = transition.jump
                val avgRepVelocity = when {
                    jump != null -> jump.takeoffVelocity
                    squatRep != null -> MetersPerSecond(squatRep.meanConcentricVelocity)
                    repVelocityCount > 0 -> MetersPerSecond(repVelocitySum / repVelocityCount)
                    else -> MetersPerSecond(0f)
                }
                repVelocities.add(avgRepVelocity)
                repPeakVelocities.add(
                    jump?.takeoffVelocity ?: squatRep?.let { MetersPerSecond(it.peakConcentricVelocity) }
                        ?: MetersPerSecond(repVelocityPeak)
                )
                squatRep?.let {
                    repEccentricVelocities.add(MetersPerSecond(it.meanEccentricVelocity))
                    repPauseMs.add(it.pauseMs)
                    repEccentricDurations.add(it.eccentricDurationS)
                    repConcentricDurations.add(it.concentricDurationS)
                    repRangeOfMotion.add(Meters(it.rangeOfMotion))
                }
                jump?.let {
                    repJumpHeights.add(it.height)
                    repApexHeights.add(it.apexHeight)
                    repPeakPowers.add((strategy as? JumpStrategy)?.peakPower(it, params.calibrationData, params.weight))
                    // From the excursion the bar path just kept, so an approach jump's is UNCHECKED.
                    repJumpQualities.add(JumpQuality.of(repBarPaths.last().excursion?.fitCheck))
                    repMechanics.add(
                        (strategy as? JumpStrategy)?.pushOffMechanics(
                            it, repBarPaths.last().excursion, params.calibrationData, params.weight
                        )
                    )
                    repContactTimesMs.add(it.contactTimeMs)
                    repReactiveStrengthIndices.add(it.reactiveStrengthIndex)
                }

                onProgress(
                    Progress(
                        currentFrame = poseResults.size,
                        totalFrames = poseResults.size,
                        percentComplete = 1f,
                        currentRep = repScores.size
                    )
                )
            }

            // Emit per-frame overlay data for the annotated-video pass (Feature 09).
            onFrameAnnotation?.let { sink ->
                val instantaneous = velocity?.instantaneous ?: MetersPerSecond(0f)
                sink(
                    FrameAnnotation(
                        timestampMs = pose.timestamp,
                        pose = pose,
                        velocity = instantaneous,
                        repCount = repScores.size,
                        vbtZone = if (instantaneous.value > 0.01f) VbtZone.fromVelocity(instantaneous) else null,
                        phaseLabel = phaseLabel(newPhase)
                    )
                )
            }

            previousPhase = newPhase
        }
        finishLanding()

        val cameraView = if (params.exerciseType.canJudgeCameraView()) {
            CameraViewClassifier.classify(standingShoulderRatios)
        } else CameraView.UNKNOWN
        return assembleSummary(params, repScores, repVelocities, repPeakVelocities, repBarPaths, repJumpHeights)
            .copy(
                cameraView = cameraView, repApexHeights = repApexHeights, repPeakPowers = repPeakPowers,
                headOutOfFrame = headOutOfFrame,
                repCatchTypes = repCatchTypes,
                repJumpQualities = repJumpQualities,
                repMechanics = repMechanics,
                repContactTimesMs = repContactTimesMs, repReactiveStrengthIndices = repReactiveStrengthIndices,
                repEccentricVelocities = repEccentricVelocities, repPauseMs = repPauseMs,
                repEccentricDurations = repEccentricDurations, repConcentricDurations = repConcentricDurations,
                repRangeOfMotion = repRangeOfMotion, barTrackSource = barTrack?.source,
                videoSpeedSuspect = VideoSpeedCheck.looksSlowMotion(repFittedGravities, timeScale).also {
                    if (it) logger.w(TAG, "Jump gravity fits %s m/s² with time scale 1: video speed looks wrong (slow motion?)"
                        .format(repFittedGravities))
                }
            )
    }

    private fun assembleSummary(
        params: Params,
        repScores: List<RepQualityScore>,
        repVelocities: List<MetersPerSecond>,
        repPeakVelocities: List<MetersPerSecond>,
        repBarPaths: List<BarPathData>,
        repJumpHeights: List<Meters>
    ): SetSummary {
        val avgVelocity = if (repVelocities.isNotEmpty()) {
            MetersPerSecond(repVelocities.map { it.value }.average().toFloat())
        } else MetersPerSecond(0f)

        val peakVelocity = repPeakVelocities.maxByOrNull { it.value } ?: MetersPerSecond(0f)

        val velocityLossPercent = if (repVelocities.size >= 2) {
            val fastest = repVelocities.maxOf { it.value }
            val last = repVelocities.last().value
            if (fastest > 0f) (1f - last / fastest).coerceIn(0f, 1f) else 0f
        } else 0f

        return SetSummary(
            exerciseType = params.exerciseType,
            weight = params.weight,
            targetReps = RepCount(params.targetReps.coerceAtLeast(0)),
            completedReps = RepCount(repScores.size),
            repScores = repScores,
            repVelocities = repVelocities,
            repPeakVelocities = repPeakVelocities,
            avgVelocity = avgVelocity,
            peakVelocity = peakVelocity,
            velocityLossPercent = velocityLossPercent,
            overallScore = if (repScores.isNotEmpty()) {
                repScores.map { it.totalScore }.average().toInt()
            } else 0,
            bestRepIndex = repScores.indices.maxByOrNull { repScores[it].totalScore } ?: 0,
            worstRepIndex = repScores.indices.minByOrNull { repScores[it].totalScore } ?: 0,
            aggregateFormIssues = aggregateFormIssues(repScores),
            repBarPaths = repBarPaths,
            repJumpHeights = repJumpHeights
        )
    }

    /**
     * Vertical tracking position in meters, or null when the tracked joint is
     * not visible enough in this frame to measure. Callers must not treat a
     * missing measurement as position zero.
     */
    private fun trackingPositionMeters(pose: PoseResult, params: Params, calculator: IPositionCalculator): Float? {
        val xy = trackingNormalizedXY(pose, params) ?: return null
        // pixelsToMeters takes normalized y and scales by the image height itself.
        return calculator.pixelsToMeters(xy.second, pose, params.calibrationData)
    }

    /**
     * Each completed rep's depth verdict (a deadlift's lockout) from running
     * the strategy over the whole set, by the pose index it came at.
     */
    private fun strategyVerdicts(
        strategy: ExerciseStrategy,
        params: Params,
        poses: List<PoseResult>
    ): Map<Int, DepthValidationResult> {
        var state = ExercisePhaseState.initial()
        val verdicts = linkedMapOf<Int, DepthValidationResult>()
        poses.forEachIndexed { i, pose ->
            val transition = strategy.detectPhase(pose, params.calibrationData, state)
            state = transition.newState
            if (transition.repCompleted) transition.depthValidation?.let { verdicts[i] = it }
        }
        return verdicts
    }

    /** What a squat's depth checks need from the tracking pass. */
    private class SquatContext(
        val calculator: IPositionCalculator,
        val standingTorso: Float?,
        /** Diagnostics: meters per normalized-y/image-height unit at each pose, from a calculator session of its own. */
        val metersPerUnit: List<Float>
    )

    /**
     * Meters per unit of a pose's normalized y (and image-height X) as the
     * scale stood at that frame, from a session of its own so that asking
     * never moves the one the analysis judges with. Log output only.
     */
    private fun metersPerUnit(poses: List<PoseResult>, params: Params): List<Float> {
        val session = positionCalculator.newSession()
        session.lockScaleToStanding(poses, params.calibrationData)
        return poses.map { session.pixelsToMeters(1f, it, params.calibrationData) }
    }

    /** Logs the frames lost from the tracking pass as time ranges per reason, and the gaps with no pose at all. */
    private fun logTrackingGaps(timesUs: List<Long>, reasons: List<String?>) {
        val gaps = gapRanges(timesUs, reasons) + noPoseGaps(timesUs)
        gaps.take(MAX_GAP_LINES).forEach { logger.i(TAG, "Tracking gap: $it") }
        if (gaps.size > MAX_GAP_LINES) logger.i(TAG, "Tracking gap: and ${gaps.size - MAX_GAP_LINES} more")
    }

    /**
     * Logs, per rep, every frame around its bottom before any plausibility
     * filtering: hip below the knee (cm, negative = above), femur angle, the
     * torso as a share of the standing one and whether [PoseSanity] drops it,
     * then the deepest frame and the projected thigh length there (a short
     * thigh in a side view means an oblique camera or collapsed landmarks).
     */
    private fun logDepthWindow(
        rep: RepSegmenter.Rep,
        poses: List<PoseResult>,
        context: SquatContext,
        fromUs: Long,
        toUs: Long
    ) {
        class Frame(val index: Int, val hipBelowKneeCm: Float, val thighCm: Float, val femur: Float, val share: Float?, val rejected: Boolean)

        fun frameAt(i: Int): Frame? {
            val pose = poses[i]
            val factor = context.metersPerUnit.getOrNull(i) ?: return null
            val parts = context.calculator.getBodyPartPositions(pose) ?: return null
            val hipsY = parts.hipsY ?: return null
            val kneesY = parts.kneesY ?: return null
            val hipsX = parts.hipsX ?: return null
            val kneesX = parts.kneesX ?: return null
            val dy = (hipsY - kneesY) * factor
            val dx = (hipsX - kneesX) * factor
            return Frame(
                i, dy * 100f, sqrt(dx * dx + dy * dy) * 100f,
                SquatDepthValidator.femurAngleDegrees(hipsX, hipsY, kneesX, kneesY),
                context.standingTorso?.let { st -> PoseSanity.torsoHeight(pose)?.let { it / st } },
                !PoseSanity.isPlausible(pose, context.standingTorso)
            )
        }

        fun s(us: Long) = "%.2f".format(us / 1e6)
        val frames = poses.indices.filter { poses[it].timestampUs in fromUs..toUs }.mapNotNull(::frameAt)
        if (frames.isEmpty()) {
            logger.i(TAG, "Depth window ${s(fromUs)}-${s(toUs)} s: no frame shows hips and knees")
            return
        }
        logger.i(
            TAG, "Depth window ${s(fromUs)}-${s(toUs)} s, ${frames.size} frames " +
                "(time hip-below-knee cm, femur deg, torso % of standing, ! = dropped by PoseSanity): " +
                frames.joinToString(" | ") {
                    "%s %.1f %.0f %s%s".format(
                        s(poses[it.index].timestampUs), it.hipBelowKneeCm, it.femur,
                        it.share?.let { share -> "%.0f%%".format(share * 100) } ?: "n/a", if (it.rejected) " !" else ""
                    )
                }
        )
        val deepest = frames.maxBy { it.hipBelowKneeCm }
        logger.i(
            TAG, "Depth window deepest frame before filtering: ${s(poses[deepest.index].timestampUs)} s, hip %.1f cm %s the knee, thigh %.1f cm, torso %s, %s"
                .format(
                    abs(deepest.hipBelowKneeCm), if (deepest.hipBelowKneeCm >= 0) "below" else "above", deepest.thighCm,
                    deepest.share?.let { "%.0f%% of standing".format(it * 100) } ?: "n/a",
                    if (deepest.rejected) "dropped by PoseSanity" else "kept"
                )
        )
        frameAt(rep.bottom)?.let {
            logger.i(TAG, "Bar-track bottom ${s(poses[rep.bottom].timestampUs)} s: hip %.1f cm %s the knee, thigh %.1f cm".format(
                abs(it.hipBelowKneeCm), if (it.hipBelowKneeCm >= 0) "below" else "above", it.thighCm
            ))
        }
    }

    /**
     * A plate-tracked lift's bar track: heights (m, up positive) and signed
     * velocities per pose, and the reps [RepSegmenter] found in it.
     */
    private class BarTrack(
        val source: BarTrackSource,
        val samples: List<RepSegmenter.Sample>,
        val reps: List<RepSegmenter.Rep>,
        val direction: RepSegmenter.Direction = RepSegmenter.Direction.DOWN_FIRST
    ) {
        /** The sample a [rep] is complete at: lockout of a pull, else the bar back at the top. */
        fun completionIndex(rep: RepSegmenter.Rep): Int =
            if (direction == RepSegmenter.Direction.UP_FIRST) rep.pauseStart else rep.concentricEnd

        /** Per-frame speed for the overlay and bar path, 1:1 with the poses; 0 where the bar was not seen. */
        fun velocities(): List<VelocityData> = samples.map {
            val speed = MetersPerSecond(if (it.measured) abs(it.velocity) else 0f)
            VelocityData(speed, speed, Meters(abs(it.height)), it.timestampUs / 1_000)
        }

        /**
         * Phase per frame: the descent, the frames within the pause band
         * around the bottom, the ascent, then TOP from lockout for up to
         * [TOP_HOLD_US] or until the next rep; IDLE elsewhere.
         */
        fun phases(): List<MovementPhase> {
            val phases = MutableList(samples.size) { MovementPhase.IDLE }
            if (direction == RepSegmenter.Direction.UP_FIRST) {
                // The pull, the hold at lockout, then the lowering back to the floor (IDLE).
                reps.forEach { rep ->
                    for (i in rep.eccentricStart until rep.pauseStart) phases[i] = MovementPhase.CONCENTRIC
                    for (i in rep.pauseStart..rep.concentricStart) phases[i] = MovementPhase.TOP
                    for (i in rep.concentricStart + 1 until rep.concentricEnd) phases[i] = MovementPhase.ECCENTRIC
                }
                return phases
            }
            reps.forEachIndexed { n, rep ->
                for (i in rep.eccentricStart until rep.pauseStart) phases[i] = MovementPhase.ECCENTRIC
                for (i in rep.pauseStart..rep.concentricStart) phases[i] = MovementPhase.BOTTOM
                for (i in rep.concentricStart + 1 until rep.concentricEnd) phases[i] = MovementPhase.CONCENTRIC
                val lockoutUs = samples[rep.concentricEnd].timestampUs
                val next = reps.getOrNull(n + 1)?.eccentricStart ?: samples.size
                for (i in rep.concentricEnd until next) {
                    if (samples[i].timestampUs - lockoutUs > TOP_HOLD_US) break
                    phases[i] = MovementPhase.TOP
                }
            }
            return phases
        }
    }

    /**
     * The bar track for rep counting and velocities. The plate is in the bar's
     * plane and scaled by its own diameter, so it is used when it shows reps
     * and was seen on at least [MIN_PLATE_COVERAGE] of their frames (short
     * gaps are interpolated). Otherwise the cleaned landmark track, scaled by
     * the body: an estimate, and its long gaps drop the reps they cross.
     */
    private suspend fun barTrack(
        params: Params,
        poses: List<PoseResult>,
        plates: Map<Long, PlateObservation>,
        landmarks: List<Float?>
    ): BarTrack {
        val minDescent = when (params.exerciseType) {
            ExerciseType.BENCH_PRESS -> ExerciseThresholds.BenchPress.MIN_DESCENT_DEPTH.value
            ExerciseType.DEADLIFT -> ExerciseThresholds.Deadlift.MIN_BAR_PULL.value
            else -> ((strategyFactory.getStrategy(params.exerciseType) as? SquatStrategyBase)
                ?.getCriteria()?.squatDepthType ?: SquatDepthType.DEFAULT).minBarDescent.value
        }
        val direction = if (params.exerciseType.startsWithConcentric()) {
            RepSegmenter.Direction.UP_FIRST
        } else RepSegmenter.Direction.DOWN_FIRST
        val timesUs = poses.map { it.timestampUs }
        // Plates are keyed by frame time in ms.
        val observed = poses.map { plates[it.timestampUs / 1_000] }
        val seen = observed.filterNotNull()
        if (seen.isNotEmpty() && params.exerciseType.tracksBarPlate()) {
            val diameter = settingsRepository.getFloatSetting(
                SettingsKeys.PLATE_DIAMETER_M, SettingsKeys.DEFAULT_PLATE_DIAMETER_M
            ).first()
            // One radius for the set (the tracker holds it within a few percent):
            // a per-frame radius would put its jitter into every height.
            val radius = seen.map { it.r }.sorted()[seen.size / 2]
            // cx and r are normalized by the image width, cy by its height.
            val heights = poses.mapIndexed { i, pose ->
                observed[i]?.let { -it.cy * pose.imageHeight * diameter / (2f * radius * pose.imageWidth) }
            }
            val plateTrack = barTrack(BarTrackSource.PLATE, timesUs, heights, PLATE_MEASUREMENT_NOISE, minDescent, direction)
            val sortedHeights = heights.filterNotNull().sorted()
            val travel = sortedHeights[(sortedHeights.size * 0.95f).toInt().coerceAtMost(sortedHeights.size - 1)] -
                sortedHeights[(sortedHeights.size * 0.05f).toInt()]
            if (travel < minDescent * MIN_PLATE_TRAVEL_FRACTION) {
                logger.i(TAG, "Plate ignored: it moved %.3f m, too little to be the bar (a static circle)".format(travel))
                return landmarkTrack(timesUs, landmarks, minDescent, direction)
            }
            val repFrames = plateTrack.reps.flatMap { it.eccentricStart..it.concentricEnd }
            val coverage = if (repFrames.isEmpty()) 0f else repFrames.count { observed[it] != null }.toFloat() / repFrames.size
            logger.i(
                TAG, "Plate: seen on %d of %d frames, radius %.3f of the width, %d reps, seen on %.0f%% of their frames"
                    .format(seen.size, poses.size, radius, plateTrack.reps.size, coverage * 100)
            )
            if (coverage >= MIN_PLATE_COVERAGE) return plateTrack.also(::logReps)
        }
        logger.i(TAG, "Bar track from the landmarks (no usable plate track)")
        return landmarkTrack(timesUs, landmarks, minDescent, direction)
    }

    private fun landmarkTrack(
        timesUs: List<Long>,
        landmarks: List<Float?>,
        minDescent: Float,
        direction: RepSegmenter.Direction
    ): BarTrack = barTrack(
        BarTrackSource.LANDMARKS, timesUs, landmarks.map { it?.let { y -> -y } }, LANDMARK_MEASUREMENT_NOISE,
        minDescent, direction
    ).also(::logReps)

    /**
     * Heights (null where unseen) to a [BarTrack]. Every gap is bridged by a
     * straight line, so a long one neither steps the velocity nor fakes a
     * plateau, but only short ones count as measured.
     */
    private fun barTrack(
        source: BarTrackSource,
        timesUs: List<Long>,
        heights: List<Float?>,
        measurementNoise: Float,
        minDescent: Float,
        direction: RepSegmenter.Direction
    ): BarTrack {
        val held = fillTrackGaps(timesUs, heights, MAX_INTERPOLATED_GAP_US).held
        gapRanges(timesUs, held.map { if (it) "held" else null }).take(MAX_GAP_LINES)
            .forEach { logger.i(TAG, "Bar track ${source.name.lowercase()} gap: $it") }
        val bridged = fillTrackGaps(timesUs, heights, Long.MAX_VALUE).positions
        val velocities = BidirectionalVelocityCalculator.signedVelocities(
            timesUs.mapIndexed { i, t -> BidirectionalVelocityCalculator.PositionSample(bridged[i], t / 1_000, t) },
            measurementNoise = measurementNoise,
            accelerationNoise = BAR_ACCELERATION_NOISE
        )
        val samples = timesUs.indices.map {
            RepSegmenter.Sample(timesUs[it], bridged[it], velocities[it], measured = !held[it])
        }
        val segmentation = RepSegmenter.segmentDetailed(samples, minDescent, direction)
        segmentation.rejections.forEach { logger.i(TAG, "Rep candidate rejected: ${describe(it, minDescent)}") }
        return BarTrack(source, samples, segmentation.reps, direction)
    }

    private fun describe(r: RepSegmenter.Rejection, minDescent: Float): String {
        val span = "%.1f-%.1f s".format(r.startUs / 1e6, r.endUs / 1e6)
        return when (r.reason) {
            RepSegmenter.RejectionReason.CROSSED_GAP -> "$span, crossed a %.1f s tracking gap (travel %.2f m)".format(r.gapUs / 1e6, r.depth)
            RepSegmenter.RejectionReason.NEVER_RETURNED -> "$span, did not return to the top before the end (travel %.2f m)".format(r.depth)
            RepSegmenter.RejectionReason.TOO_SHALLOW -> "$span, descended only %.2f m, under the %.2f m a rep needs".format(r.depth, minDescent)
        }
    }

    private fun logReps(track: BarTrack) {
        track.reps.forEachIndexed { n, rep ->
            fun s(index: Int) = "%.2f".format(track.samples[index].timestampUs / 1e6)
            logger.i(
                TAG, ("Rep ${n + 1} (${track.source}): ${s(rep.eccentricStart)}-${s(rep.bottom)}-${s(rep.concentricEnd)} s, " +
                    "ROM %.2f m, ecc %.2f m/s over %.2f s, pause %d ms, con %.2f m/s over %.2f s, peak %.2f m/s").format(
                    rep.rangeOfMotion, rep.meanEccentricVelocity, rep.eccentricDurationS, rep.pauseMs,
                    rep.meanConcentricVelocity, rep.concentricDurationS, rep.peakConcentricVelocity
                )
            )
        }
    }

    /**
     * The rep's depth, judged by the strategy on the plausible frames around
     * its bottom: the first that reaches depth, else the most confident miss.
     * Null when no frame there shows the hips and knees (the plate hid them).
     */
    private fun squatDepth(
        rep: RepSegmenter.Rep,
        strategy: ExerciseStrategy,
        params: Params,
        poses: List<PoseResult>,
        context: SquatContext?
    ): DepthValidationResult? {
        val squatStrategy = strategy as? SquatStrategyBase ?: return null
        context ?: return null
        val fromUs = poses[rep.pauseStart].timestampUs - DEPTH_WINDOW_US
        val toUs = poses[rep.concentricStart].timestampUs + DEPTH_WINDOW_US
        logDepthWindow(rep, poses, context, fromUs, toUs)
        val results = poses.filter { it.timestampUs in fromUs..toUs && PoseSanity.isPlausible(it, context.standingTorso) }
            .mapNotNull { squatStrategy.validateDepthAt(it, params.calibrationData, context.calculator) }
        return results.firstOrNull { it.isValid } ?: results.maxByOrNull { it.confidence }
    }

    private fun extractBarPathPoint(
        pose: PoseResult,
        velocity: VelocityData?,
        params: Params
    ): BarPathPoint? {
        val (nx, ny) = trackingNormalizedXY(pose, params) ?: return null
        val ppm = params.calibrationData.pixelsPerMeter.takeIf { it > 0f } ?: 1f
        val imageWidth = pose.imageWidth.toFloat()
        val imageHeight = pose.imageHeight.toFloat()
        return BarPathPoint(
            normalizedX = nx,
            normalizedY = ny,
            realWorldXCm = (nx * imageWidth / ppm) * 100f,
            realWorldYCm = (ny * imageHeight / ppm) * 100f,
            velocity = velocity?.instantaneous ?: MetersPerSecond(0f),
            timestamp = pose.timestamp
        )
    }

    private fun trackingNormalizedXY(pose: PoseResult, params: Params): Pair<Float, Float>? {
        val loadFraction = if (params.exerciseType == ExerciseType.LOADED_JUMP) {
            CenterOfMassCalculator.loadFraction(
                params.weight.value,
                params.calibrationData.anthropometricData?.userWeight
            )
        } else 0f
        return normalizedXY(pose, trackingPoint(params.exerciseType), loadFraction)
    }

    private fun phaseLabel(phase: MovementPhase): String? = when (phase) {
        MovementPhase.ECCENTRIC -> "DESCENT"
        MovementPhase.BOTTOM -> "BOTTOM"
        MovementPhase.CONCENTRIC -> "DRIVE"
        MovementPhase.TOP -> "TOP"
        else -> null
    }

    private suspend fun configureSquatDepth(exerciseType: ExerciseType) {
        when (val strategy = runCatching { strategyFactory.getStrategy(exerciseType) }.getOrNull()) {
            is BackSquatStrategy -> {
                val name = settingsRepository.getStringSetting(
                    if (exerciseType == ExerciseType.AIR_SQUAT) SettingsKeys.SQUAT_DEPTH_AIR_SQUAT
                    else SettingsKeys.SQUAT_DEPTH_BACK_SQUAT,
                    SquatDepthType.DEFAULT.name
                ).first()
                strategy.updateSquatDepthType(SquatDepthType.fromString(name))
            }
            is FrontSquatStrategy -> {
                val name = settingsRepository.getStringSetting(
                    SettingsKeys.SQUAT_DEPTH_FRONT_SQUAT, SquatDepthType.DEFAULT.name
                ).first()
                strategy.updateSquatDepthType(SquatDepthType.fromString(name))
            }
            else -> Unit
        }
    }

    /** A jump's rep, scored again with the frames up to [untilUs] after its landing. */
    private class PendingLanding(
        val repIndex: Int,
        val untilUs: Long,
        val frames: MutableList<AnalyzeRepUseCase.RepFrame>,
        val rescore: suspend (List<AnalyzeRepUseCase.RepFrame>) -> RepQualityScore
    )

    /**
     * A tracked position per frame, gaps filled; [missing] counts the frames
     * that had none, [held] marks those filled by holding a value (gaps too
     * long to interpolate, before the first and after the last position).
     */
    internal class FilledTrack(val positions: List<Float>, val missing: Int, val held: List<Boolean>)

    companion object {
        private const val TAG = "AnalyzeVideoUseCase"

        /** Longest tracking gap bridged by a straight line; longer gaps hold the last position. */
        internal const val MAX_INTERPOLATED_GAP_US = 200_000L

        /**
         * Drops positions reached faster than [maxSpeedMps] from the last kept
         * one. The allowance grows with the time since that position, so the
         * track picks up again after a real move that a gap hid.
         */
        internal fun dropTrackJumps(timesUs: List<Long>, positions: List<Float?>, maxSpeedMps: Float): List<Float?> {
            var lastIndex = -1
            return positions.mapIndexed { i, p ->
                if (p == null) return@mapIndexed null
                val last = lastIndex
                if (last >= 0 && abs(p - positions[last]!!) > maxSpeedMps * (timesUs[i] - timesUs[last]) / 1_000_000f) {
                    null
                } else {
                    lastIndex = i
                    p
                }
            }
        }

        /**
         * Fills the frames without a position: linearly between the known
         * neighbors when they are at most [maxInterpolatedGapUs] apart,
         * otherwise holding the last known position (the first known one
         * before it). All null gives zeros.
         */
        internal fun fillTrackGaps(timesUs: List<Long>, positions: List<Float?>, maxInterpolatedGapUs: Long): FilledTrack {
            val filled = FloatArray(positions.size)
            val held = BooleanArray(positions.size) { positions[it] == null }
            var prev = -1
            for (i in positions.indices) {
                val p = positions[i] ?: continue
                if (prev < 0) {
                    for (j in 0 until i) filled[j] = p
                } else {
                    val start = positions[prev]!!
                    val span = timesUs[i] - timesUs[prev]
                    val interpolate = span in 1..maxInterpolatedGapUs
                    for (j in prev + 1 until i) {
                        filled[j] = if (interpolate) {
                            start + (p - start) * (timesUs[j] - timesUs[prev]) / span.toFloat()
                        } else start
                        held[j] = !interpolate
                    }
                }
                filled[i] = p
                prev = i
            }
            if (prev >= 0) for (j in prev + 1 until positions.size) filled[j] = positions[prev]!!
            return FilledTrack(filled.toList(), positions.count { it == null }, held.toList())
        }

        /** Most gap ranges logged per analysis. */
        private const val MAX_GAP_LINES = 60

        /**
         * Consecutive frames lost for the same reason, as `5.20-5.65 s (sanity, 14 frames)`;
         * [reasons] is 1:1 with [timesUs], null where the frame was kept.
         */
        internal fun gapRanges(timesUs: List<Long>, reasons: List<String?>): List<String> {
            val ranges = mutableListOf<String>()
            var i = 0
            while (i < reasons.size) {
                val reason = reasons[i]
                if (reason == null) { i++; continue }
                val start = i
                while (i < reasons.size && reasons[i] == reason) i++
                ranges += "%.2f-%.2f s (%s, %d frames)".format(
                    timesUs[start] / 1e6, timesUs[i - 1] / 1e6, reason, i - start
                )
            }
            return ranges
        }

        /** Spans between consecutive poses 2.5 times the usual spacing or more: frames the pose model found no one in. */
        internal fun noPoseGaps(timesUs: List<Long>): List<String> {
            if (timesUs.size < 3) return emptyList()
            val steps = timesUs.zipWithNext { a, b -> b - a }
            val usual = steps.sorted()[steps.size / 2]
            if (usual <= 0) return emptyList()
            return steps.indices.filter { steps[it] * 2 > usual * 5 }.map {
                "%.2f-%.2f s (no pose, ~%d frames)".format(
                    timesUs[it] / 1e6, timesUs[it + 1] / 1e6, (steps[it] / usual - 1).coerceAtLeast(1)
                )
            }
        }

        /** Share of a squat's rep frames the plate must be seen on for its track to be used. */
        private const val MIN_PLATE_COVERAGE = 0.8f

        /** Plate center measurement variance (m²): about 1 cm. */
        /**
         * A plate whose height (95th minus 5th percentile) varies by less than
         * this fraction of the exercise's minimum descent is not the bar: a
         * round object that stays put, such as a plate on a rack or a wall clock.
         */
        private const val MIN_PLATE_TRAVEL_FRACTION = 0.5f

        private const val PLATE_MEASUREMENT_NOISE = 1e-4f

        /** Landmark measurement variance (m²): about 2 cm. */
        private const val LANDMARK_MEASUREMENT_NOISE = 4e-4f

        /** Bar track Kalman acceleration noise (m²/s³), see [BidirectionalVelocityCalculator.signedVelocities]. */
        private const val BAR_ACCELERATION_NOISE = 0.5f

        /** How long after a squat's lockout its frames read TOP. */
        private const val TOP_HOLD_US = 1_000_000L

        /** Frames this far around a squat's bottom pause are judged for depth. */
        private const val DEPTH_WINDOW_US = 200_000L

        /** Share of the progress bar for pass 1 when a dense pass follows. */
        private const val COARSE_PASS_SHARE = 0.8f

        /** Half-width of the every-frame window around each takeoff and landing. */
        private const val DENSE_WINDOW_MS = 150L
        private val ACTIVE_PHASES = setOf(
            MovementPhase.ECCENTRIC, MovementPhase.BOTTOM, MovementPhase.CONCENTRIC
        )
    }
}
