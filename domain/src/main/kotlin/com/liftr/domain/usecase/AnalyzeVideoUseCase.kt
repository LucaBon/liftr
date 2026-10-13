package com.liftr.domain.usecase

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
            val poseResults = mutableListOf<PoseResult>()
            var frameIndex = 0
            try {
                poseSource.poses().collect { pose ->
                    if (pose != null) poseResults.add(pose)
                    frameIndex++
                    val total = poseSource.totalFrames.coerceAtLeast(1)
                    onProgress(
                        Progress(
                            currentFrame = frameIndex,
                            totalFrames = total,
                            percentComplete = (frameIndex.toFloat() / total).coerceIn(0f, 1f),
                            currentRep = 0
                        )
                    )
                }
            } finally {
                poseSource.close()
            }

            logger.i(TAG, "Pose detection complete: ${poseResults.size} poses from $frameIndex frames")
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
        val previousConcentricDurations = mutableListOf<Float>()

        var currentRepBarPoints = mutableListOf<BarPathPoint>()
        var currentRepPoses = mutableListOf<PoseResult>()
        var currentRepDepthValidation: DepthValidationResult? = null
        var eccentricStart = 0L
        var bottomTime = 0L
        var repVelocitySum = 0f
        var repVelocityCount = 0

        var tempoState: TempoState? = params.tempoPrescription?.let { TempoTracker.reset(it) }

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

            if (newPhase in ACTIVE_PHASES) {
                extractBarPathPoint(pose, velocity, params)?.let { currentRepBarPoints.add(it) }
                currentRepPoses.add(pose)
            }

            if (velocity != null && (newPhase == MovementPhase.ECCENTRIC || newPhase == MovementPhase.CONCENTRIC)) {
                repVelocitySum += velocity.instantaneous.value
                repVelocityCount++
            }

            if (transition.repCompleted) {
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
                    BarPathAnalyzer.analyze(repNumber = repScores.size, points = currentRepBarPoints.toList())
                )

                val avgRepVelocity = if (repVelocityCount > 0) {
                    MetersPerSecond(repVelocitySum / repVelocityCount)
                } else MetersPerSecond(0f)
                repVelocities.add(avgRepVelocity)

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

        return assembleSummary(params, repScores, repVelocities, repBarPaths)
    }

    private fun assembleSummary(
        params: Params,
        repScores: List<RepQualityScore>,
        repVelocities: List<MetersPerSecond>,
        repBarPaths: List<BarPathData>
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
            repBarPaths = repBarPaths
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
        val xy = trackingNormalizedXY(pose, params.exerciseType) ?: return null
        val pixelY = xy.second * pose.imageHeight
        return positionCalculator.pixelsToMeters(pixelY, pose, params.calibrationData)
    }

    private fun extractBarPathPoint(
        pose: PoseResult,
        velocity: VelocityData?,
        params: Params
    ): BarPathPoint? {
        val (nx, ny) = trackingNormalizedXY(pose, params.exerciseType) ?: return null
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

    private fun trackingNormalizedXY(pose: PoseResult, exerciseType: ExerciseType): Pair<Float, Float>? {
        val landmarks = pose.landmarks
        return when (exerciseType) {
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
        private val ACTIVE_PHASES = setOf(
            MovementPhase.ECCENTRIC, MovementPhase.BOTTOM, MovementPhase.CONCENTRIC
        )
    }
}
