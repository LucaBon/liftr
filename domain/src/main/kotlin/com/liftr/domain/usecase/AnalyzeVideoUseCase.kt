package com.liftr.domain.usecase

import com.liftr.domain.exercise.JumpPathWindow
import com.liftr.domain.calculator.CenterOfMassCalculator
import com.liftr.domain.exercise.BarPathAnalyzer
import com.liftr.domain.exercise.BidirectionalVelocityCalculator
import com.liftr.domain.exercise.ExercisePhaseState
import com.liftr.domain.exercise.ExerciseStrategyFactory
import com.liftr.domain.exercise.FormIssue
import com.liftr.domain.exercise.RepQualityScorer
import com.liftr.domain.exercise.TempoAdherence
import com.liftr.domain.exercise.TempoState
import com.liftr.domain.exercise.TempoTracker
import com.liftr.domain.exercise.strategy.BackSquatStrategy
import com.liftr.domain.exercise.strategy.FrontSquatStrategy
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.AggregateFormIssue
import com.liftr.domain.model.BarPathData
import com.liftr.domain.model.BarPathPoint
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.DepthValidationResult
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.FrameAnnotation
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
import com.liftr.domain.vision.IPositionCalculator
import com.liftr.domain.vision.VideoPoseSource
import com.liftr.domain.model.MovementPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

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
 * 3. Walk frames: phase detection + rep counting (`ExerciseStrategy`).
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
            val poseResults = try {
                val coarseShare = if (params.exerciseType.isJumpExercise()) COARSE_PASS_SHARE else 1f
                val coarse = detectPoses(
                    poseSource, everyMs(coarseIntervalMs(params.exerciseType)), onProgress, 0f, coarseShare
                )
                val windows = if (params.exerciseType.isJumpExercise()) jumpWindows(params, coarse.poses) else emptyList()
                if (windows.isEmpty() || coarse.skippedFrames == 0) {
                    coarse.poses
                } else {
                    val analyzed = coarse.poses.mapTo(HashSet()) { it.timestamp }
                    val dense = detectPoses(
                        poseSource,
                        { t -> t !in analyzed && windows.any { t in it } },
                        onProgress, COARSE_PASS_SHARE, 1f
                    )
                    logger.i(TAG, "Dense pass: ${dense.poses.size} poses around ${windows.size} takeoffs/landings")
                    (coarse.poses + dense.poses).sortedBy { it.timestamp }
                }
            } finally {
                poseSource.close()
            }

            logger.i(TAG, "Pose detection complete: ${poseResults.size} poses")
            if (poseResults.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("No poses detected in video"))
            }

            // --- Phase 2: bidirectional velocities over the whole set ---
            // Frames whose tracked joint is occluded produce no position. Hold
            // the last known one instead of substituting 0: a fabricated zero is
            // a ~1.4 m jump from a real bar position, which one frame later
            // reads as a ~40 m/s spike and wrecks peak velocity, velocity loss
            // and the VBT zones. The list stays 1:1 with poseResults, which
            // buildSummary relies on.
            var lastKnownPosition: Float? = null
            val positionSamples = poseResults.map { pose ->
                val measured = trackingPositionMeters(pose, params)
                if (measured != null) lastKnownPosition = measured
                BidirectionalVelocityCalculator.PositionSample(
                    positionMeters = measured ?: lastKnownPosition ?: 0f,
                    timestampMs = pose.timestamp
                )
            }
            val velocities = BidirectionalVelocityCalculator.calculateVelocities(positionSamples)

            // --- Phase 3: phase detection, rep counting, scoring ---
            val summary = buildSummary(params, poseResults, velocities, onProgress, onFrameAnnotation)

            logger.i(TAG, "Analysis complete: ${summary.completedReps.value} reps, score=${summary.overallScore}")
            Result.success(summary)
        } catch (e: Exception) {
            logger.e(TAG, "Analysis failed", e)
            Result.failure(e)
        }
    }

    private class PassResult(val poses: List<PoseResult>, val skippedFrames: Int)

    /** Runs one decoding pass, analyzing the frames [include] accepts. */
    private suspend fun detectPoses(
        poseSource: VideoPoseSource,
        include: (Long) -> Boolean,
        onProgress: (Progress) -> Unit,
        progressFrom: Float,
        progressTo: Float
    ): PassResult {
        val poses = mutableListOf<PoseResult>()
        var frameIndex = 0
        var skipped = 0
        poseSource.poses { t -> include(t).also { if (!it) skipped++ } }.collect { frame ->
            frame.pose?.let { poses.add(it) }
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
        return PassResult(poses, skipped)
    }

    /** Accepts frames at least [intervalMs] apart, thinning faster video to that rate. */
    private fun everyMs(intervalMs: Long): (Long) -> Boolean {
        var last = Long.MIN_VALUE / 2
        return { t -> (t - last >= intervalMs).also { if (it) last = t } }
    }

    /**
     * Pass 1 frame spacing: ~30 fps for jumps (pass 2 adds every frame around
     * takeoff and landing), ~60 fps for other fast movements, ~30 fps for lifts.
     * Slightly under the nominal interval so 30/60 fps video keeps every frame.
     */
    private fun coarseIntervalMs(exerciseType: ExerciseType): Long =
        if (exerciseType.isFastMovement() && !exerciseType.isJumpExercise()) 15L else 30L

    /** Time windows around each takeoff and landing found in the [coarse] poses. */
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
        onFrameAnnotation: ((FrameAnnotation) -> Unit)? = null
    ): SetSummary {
        val strategy = strategyFactory.getStrategy(params.exerciseType)
        var phaseState = ExercisePhaseState.initial()
        var previousPhase = MovementPhase.IDLE

        val repScores = mutableListOf<RepQualityScore>()
        val repVelocities = mutableListOf<MetersPerSecond>()
        val repBarPaths = mutableListOf<BarPathData>()
        val repJumpHeights = mutableListOf<Meters>()
        val previousConcentricDurations = mutableListOf<Float>()

        var currentRepBarPoints = mutableListOf<BarPathPoint>()
        var currentRepPoses = mutableListOf<PoseResult>()
        var currentRepDepthValidation: DepthValidationResult? = null
        var eccentricStart = 0L
        var bottomTime = 0L
        var repVelocitySum = 0f
        var repVelocityCount = 0

        var tempoState: TempoState? = params.tempoPrescription?.let { TempoTracker.reset(it) }
        val jumpPath = if (params.exerciseType.isJumpExercise()) JumpPathWindow() else null

        for (i in poseResults.indices) {
            val pose = poseResults[i]
            val velocity = velocities.getOrNull(i)
            val now = pose.timestamp

            val transition = strategy.detectPhase(pose, params.calibrationData, phaseState)
            val newPhase = transition.newState.currentPhase
            phaseState = transition.newState

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
                JumpPathWindow.cmPerUnitY(pose, params.calibrationData.userHeight)
            )
            if (newPhase in ACTIVE_PHASES) {
                pathPoint?.let { currentRepBarPoints.add(it) }
                currentRepPoses.add(pose)
            }

            if (velocity != null && (newPhase == MovementPhase.ECCENTRIC || newPhase == MovementPhase.CONCENTRIC)) {
                repVelocitySum += velocity.instantaneous.value
                repVelocityCount++
            }

            if (transition.repCompleted) {
                // A jump's path runs through its flight, which ACTIVE_PHASES leaves out.
                val jumpWindow = transition.jump?.let { jumpPath?.pathForLanding(now, it.flightTimeMs) }
                jumpWindow?.let { window ->
                    currentRepBarPoints = window.points.toMutableList()
                    window.excursion?.let {
                        logger.i(
                            TAG,
                            "Jump ${repScores.size + 1}: dip %.1f cm, peak %.1f cm above standing"
                                .format(it.countermovementCm, it.peakRiseCm)
                        )
                    }
                }
                val eccentricDuration = if (bottomTime > 0 && eccentricStart > 0) {
                    (bottomTime - eccentricStart) / 1000f
                } else 0f
                val concentricDuration = if (bottomTime > 0) (now - bottomTime) / 1000f else 0f

                val tempoAdherence: TempoAdherence? = tempoState?.let { ts ->
                    val finalized = TempoTracker.onPhaseTransition(ts, MovementPhase.IDLE, now)
                    tempoState = TempoTracker.reset(ts.prescription)
                    TempoTracker.calculateAdherence(finalized)
                }

                val analysis = runCatching {
                    analyzeRepUseCase(
                        AnalyzeRepUseCase.Params(
                            exerciseType = params.exerciseType,
                            calibrationData = params.calibrationData,
                            weight = params.weight,
                            bufferedPoses = currentRepPoses.toList(),
                            finalVelocityData = velocity
                        )
                    )
                }.getOrNull()
                val formIssues: List<FormIssue> = analysis?.formValidation?.issues ?: emptyList()

                val score = RepQualityScorer.scoreRep(
                    exerciseType = params.exerciseType,
                    depthValidation = currentRepDepthValidation,
                    barXPositions = currentRepBarPoints.map { it.normalizedX },
                    eccentricDuration = eccentricDuration,
                    concentricDuration = concentricDuration,
                    previousConcentricDurations = previousConcentricDurations.toList(),
                    formIssues = formIssues,
                    tempoAdherence = tempoAdherence
                )
                repScores.add(score)
                previousConcentricDurations.add(concentricDuration)

                repBarPaths.add(
                    BarPathAnalyzer.analyze(
                        repNumber = repScores.size,
                        points = currentRepBarPoints.toList(),
                        excursion = jumpWindow?.excursion
                    )
                )

                // A jump's takeoff velocity (from flight time) is its rep velocity,
                // so VBT zones and velocity loss work the same way for jumps.
                val jump = transition.jump
                val avgRepVelocity = when {
                    jump != null -> jump.takeoffVelocity
                    repVelocityCount > 0 -> MetersPerSecond(repVelocitySum / repVelocityCount)
                    else -> MetersPerSecond(0f)
                }
                repVelocities.add(avgRepVelocity)
                jump?.let { repJumpHeights.add(it.height) }

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

        return assembleSummary(params, repScores, repVelocities, repBarPaths, repJumpHeights)
    }

    private fun assembleSummary(
        params: Params,
        repScores: List<RepQualityScore>,
        repVelocities: List<MetersPerSecond>,
        repBarPaths: List<BarPathData>,
        repJumpHeights: List<Meters>
    ): SetSummary {
        val avgVelocity = if (repVelocities.isNotEmpty()) {
            MetersPerSecond(repVelocities.map { it.value }.average().toFloat())
        } else MetersPerSecond(0f)

        val peakVelocity = repVelocities.maxByOrNull { it.value } ?: MetersPerSecond(0f)

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

    private fun aggregateFormIssues(scores: List<RepQualityScore>): List<AggregateFormIssue> {
        val issueMap = mutableMapOf<String, Pair<FormIssue, MutableList<Int>>>()
        scores.forEachIndexed { index, score ->
            score.formIssues.forEach { issue ->
                val entry = issueMap.getOrPut(issue.message) { issue to mutableListOf() }
                entry.second.add(index + 1)
            }
        }
        return issueMap.values.map { (issue, repNumbers) ->
            AggregateFormIssue(issue = issue, repNumbers = repNumbers)
        }
    }

    /**
     * Vertical tracking position in meters for velocity estimation — the same
     * body part the bar-path/real-time pipeline tracks per exercise.
     */
    /**
     * Vertical tracking position in meters, or null when the tracked joint is
     * not visible enough in this frame to measure. Callers must not treat a
     * missing measurement as position zero.
     */
    private fun trackingPositionMeters(pose: PoseResult, params: Params): Float? {
        val xy = trackingNormalizedXY(pose, params) ?: return null
        val pixelY = xy.second * pose.imageHeight
        return positionCalculator.pixelsToMeters(pixelY, pose, params.calibrationData)
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
        val landmarks = pose.landmarks
        return when (params.exerciseType) {
            // No bar to follow: track the whole body's center of mass, plus
            // the bar on the shoulders for a loaded jump.
            ExerciseType.VERTICAL_JUMP -> CenterOfMassCalculator.centerOfMass(pose)
            ExerciseType.LOADED_JUMP -> CenterOfMassCalculator.centerOfMass(
                pose,
                CenterOfMassCalculator.loadFraction(
                    params.weight.value,
                    params.calibrationData.anthropometricData?.userWeight
                )
            )
            ExerciseType.BACK_SQUAT, ExerciseType.FRONT_SQUAT ->
                averageXY(landmarks.getOrNull(PoseResult.LEFT_SHOULDER), landmarks.getOrNull(PoseResult.RIGHT_SHOULDER))
            ExerciseType.BENCH_PRESS, ExerciseType.DEADLIFT ->
                averageXY(landmarks.getOrNull(PoseResult.LEFT_WRIST), landmarks.getOrNull(PoseResult.RIGHT_WRIST))
            else -> averageXY(
                landmarks.getOrNull(PoseResult.LEFT_SHOULDER),
                landmarks.getOrNull(PoseResult.RIGHT_SHOULDER)
            )
        }
    }

    private fun averageXY(a: PoseKeypoint?, b: PoseKeypoint?): Pair<Float, Float>? {
        if (a == null || b == null) return null
        if (a.visibility < 0.5f || b.visibility < 0.5f) return null
        return Pair((a.x + b.x) / 2f, (a.y + b.y) / 2f)
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
                    SettingsKeys.SQUAT_DEPTH_BACK_SQUAT, SquatDepthType.DEFAULT.name
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

    companion object {
        private const val TAG = "AnalyzeVideoUseCase"

        /** Share of the progress bar for pass 1 when a dense pass follows. */
        private const val COARSE_PASS_SHARE = 0.8f

        /** Half-width of the every-frame window around each takeoff and landing. */
        private const val DENSE_WINDOW_MS = 150L
        private val ACTIVE_PHASES = setOf(
            MovementPhase.ECCENTRIC, MovementPhase.BOTTOM, MovementPhase.CONCENTRIC
        )
    }
}
