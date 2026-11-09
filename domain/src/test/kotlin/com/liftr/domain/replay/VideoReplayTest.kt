package com.liftr.domain.replay

import com.liftr.domain.exercise.ExerciseStrategy
import com.liftr.domain.exercise.ExerciseStrategyFactory
import com.liftr.domain.exercise.strategy.BackSquatStrategy
import com.liftr.domain.exercise.strategy.FrontSquatStrategy
import com.liftr.domain.exercise.strategy.JumpStrategy
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.AnthropometricData
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.FrameAnnotation
import com.liftr.domain.model.SquatDepthType
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import com.liftr.domain.usecase.AnalyzeRepUseCase
import com.liftr.domain.usecase.AnalyzeVideoUseCase
import com.liftr.domain.repository.SettingsRepository
import com.liftr.domain.value.Kilograms
import com.liftr.domain.vision.BodyPositionCalculator
import com.liftr.domain.vision.IPositionCalculator
import com.liftr.domain.vision.PlateObservation
import com.liftr.domain.vision.VideoPoseSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/**
 * Replays an offline per-frame pose dump (see [JumpReplayTest] for the strategy-only replay) through [AnalyzeVideoUseCase] and the real
 * strategy for LIFTR_REPLAY_TYPE ([JumpStrategy], [FrontSquatStrategy], [BackSquatStrategy]), with the
 * same coarse/dense frame sampling the phone's `VideoFilePoseSource` gives it. Squats use the
 * production position math ([BodyPositionCalculator]). Skipped unless LIFTR_REPLAY is set.
 *
 * Dump format: `{captureFps, frames, width, height, poses:[{i, tUs, lm:[[x,y,z,vis,pres] x33]}]}`
 * (MediaPipe landmark order, normalized coords). A frame without a pose entry is a
 * frame the detector found nothing in.
 *
 * Run (comma-separated files; add -i to see the strategy log):
 * `LIFTR_REPLAY=/path/a.json,/path/b.json ./gradlew :domain:test --tests '*VideoReplayTest' -i`
 * Optional: LIFTR_REPLAY_TYPE (default VERTICAL_JUMP), LIFTR_REPLAY_HEIGHT_M (default 1.75),
 * LIFTR_REPLAY_TIMESCALE (slow-mo factor, default 1), LIFTR_REPLAY_PLATE (a plate track fed in as the
 * source's plate observations: `{fps, w, h, track:[{t (s), c:[cx, cy, r] | null}]}`, cx and r normalized
 * by the width, cy by the height; the plate diameter is the setting's default).
 */
class VideoReplayTest {

    private class Dump(val captureFps: Double, val frames: Int, val width: Int, val height: Int, val poses: Map<Int, Pair<Long, List<PoseKeypoint>>>)

    @Test
    fun replay() {
        val files = System.getenv("LIFTR_REPLAY")?.split(',')?.filter { it.isNotBlank() }
        assumeTrue("LIFTR_REPLAY not set", !files.isNullOrEmpty())
        files!!.forEach { replayFile(File(it.trim())) }
    }

    private fun replayFile(file: File) {
        val dump = parse(file.readText())
        val type = ExerciseType.valueOf(System.getenv("LIFTR_REPLAY_TYPE") ?: "VERTICAL_JUMP")
        val height = System.getenv("LIFTR_REPLAY_HEIGHT_M")?.toFloat() ?: 1.75f
        val scale = System.getenv("LIFTR_REPLAY_TIMESCALE")?.toFloat() ?: 1f

        val logger = object : Logger {
            override fun d(tag: String, message: String) = println("[$tag] $message")
            override fun i(tag: String, message: String) = println("[$tag] $message")
            override fun w(tag: String, message: String) = println("[$tag] W $message")
            override fun e(tag: String, message: String, throwable: Throwable?) = println("[$tag] E $message")
        }
        val calculator: IPositionCalculator = BodyPositionCalculator(logger)
        val strategy: ExerciseStrategy = when (type) {
            ExerciseType.FRONT_SQUAT -> FrontSquatStrategy(calculator, logger)
            ExerciseType.BACK_SQUAT -> BackSquatStrategy(calculator, logger)
            else -> {
                require(type.isJumpExercise()) { "Replay supports jumps and squats, not $type" }
                JumpStrategy(type, logger)
            }
        }
        val factory = mockk<ExerciseStrategyFactory> { every { getStrategy(any()) } returns strategy }
        val settings = mockk<SettingsRepository>(relaxed = true) {
            every { getStringSetting(any(), any()) } returns flowOf(SquatDepthType.DEFAULT.name)
            every { getFloatSetting(any(), any()) } answers { flowOf(secondArg()) }
        }
        val plates = System.getenv("LIFTR_REPLAY_PLATE")?.takeIf { it.isNotBlank() }?.let { parsePlates(File(it).readText()) }
        val useCase = AnalyzeVideoUseCase(
            calculator, factory, AnalyzeRepUseCase(factory, logger), settings, logger
        )
        val annotations = mutableListOf<FrameAnnotation>()
        // One decoded frame per index, like the extractor; only accepted ones carry a pose.
        val source = object : VideoPoseSource {
            override val totalFrames = dump.frames
            override val timeScale = scale
            override fun poses(include: (Long) -> Boolean): Flow<VideoPoseSource.Frame> = flow {
                for (i in 0 until dump.frames) {
                    val entry = dump.poses[i]
                    val tUs = entry?.first ?: Math.round(i * 1_000_000.0 / dump.captureFps)
                    // Like the phone, the plate is tracked on the frames analyzed for pose.
                    val analyzed = include(tUs)
                    val pose = if (analyzed && entry != null) {
                        PoseResult(entry.second, tUs / 1_000, dump.width, dump.height, dump.height > dump.width, tUs)
                    } else null
                    val plate = if (analyzed) plates?.nearest(tUs / 1_000) else null
                    emit(VideoPoseSource.Frame(tUs / 1_000, pose, plate))
                }
            }
            override fun close() = Unit
        }
        val calibration = CalibrationData(500f, height, 0.45f, AnthropometricData.getDefaultRatios(height, 75f))
        println("=== ${file.name} (${dump.frames} frames @ ${dump.captureFps} fps, ${dump.width}x${dump.height}) ===")
        val summary = runBlocking {
            useCase.invoke(
                AnalyzeVideoUseCase.Params(File(file.name), type, Kilograms(0f), 1, calibration),
                source, onProgress = {}, onFrameAnnotation = { annotations += it }
            ).getOrThrow()
        }
        if (!type.isJumpExercise()) {
            printSquatReps(annotations)
            for (n in 0 until summary.completedReps.value) {
                println(("SUMMARY REP ${n + 1} (${summary.barTrackSource}): ROM %.2f m | ecc %.2f m/s over %.2f s | " +
                    "pause %.2f s | con mean %.2f m/s over %.2f s, peak %.2f m/s").format(
                    summary.repRangeOfMotion.getOrNull(n)?.value, summary.repEccentricVelocities.getOrNull(n)?.value,
                    summary.repEccentricDurations.getOrNull(n), summary.repPauseMs.getOrNull(n)?.div(1000.0),
                    summary.repVelocities[n].value, summary.repConcentricDurations.getOrNull(n),
                    summary.repPeakVelocities[n].value
                ))
            }
            println("RESULT ${file.name}: reps=${summary.completedReps.value} " +
                "conMean=${summary.repVelocities.map { "%.2f".format(it.value) }} " +
                "conPeak=${summary.repPeakVelocities.map { "%.2f".format(it.value) }} " +
                "maxFrameVelocity=%.2f m/s".format(annotations.maxOf { it.velocity.value }) +
                // An empty pose measures nothing, so this reads back the scale the set locked.
                " scale=%.0f px/m".format(dump.height / calculator.pixelsToMeters(1f, PoseResult(emptyList(), 0, dump.width, dump.height), calibration)))
            return
        }
        println("RESULT ${file.name}: reps=${summary.completedReps.value} heights=${summary.repJumpHeights.map { it.value }} " +
            "apex=${summary.repApexHeights.map { it?.value }}")
    }

    /**
     * Per rep, from the annotated phases: DESCENT/BOTTOM/DRIVE boundaries, the
     * pause (time labeled BOTTOM), eccentric mean |v| and a descent ROM
     * estimate (|v| integrated over DESCENT+BOTTOM; velocities are unsigned).
     */
    private fun printSquatReps(annotations: List<FrameAnnotation>) {
        val byRep = annotations.zipWithNext().groupBy { (a, b) -> if (b.repCount > a.repCount) a.repCount else b.repCount }
        for ((rep, pairs) in byRep) {
            val active = pairs.map { it.first }.filter { it.phaseLabel in setOf("DESCENT", "BOTTOM", "DRIVE") }
            if (active.isEmpty()) continue
            fun first(label: String) = active.firstOrNull { it.phaseLabel == label }?.timestampMs
            fun span(label: String) = pairs.filter { it.first.phaseLabel == label }.sumOf { (a, b) -> b.timestampMs - a.timestampMs }
            val ecc = active.filter { it.phaseLabel == "DESCENT" }.map { it.velocity.value }
            val rom = pairs.filter { it.first.phaseLabel == "DESCENT" || it.first.phaseLabel == "BOTTOM" }
                .sumOf { (a, b) -> a.velocity.value * (b.timestampMs - a.timestampMs) / 1000.0 }
            val end = pairs.last().second.timestampMs
            println(("REP ${rep + 1}${if (pairs.last().second.repCount > rep) "" else " (incomplete)"}: " +
                "descent=%s bottom=%s drive=%s end=%.2fs | eccMean=%.2f m/s pause(BOTTOM)=%.2fs " +
                "drive=%.2fs ROM~%.2f m peak|v|=%.2f m/s").format(
                first("DESCENT")?.let { "%.2fs".format(it / 1000.0) }, first("BOTTOM")?.let { "%.2fs".format(it / 1000.0) },
                first("DRIVE")?.let { "%.2fs".format(it / 1000.0) }, end / 1000.0,
                ecc.average().takeUnless { it.isNaN() } ?: 0.0, span("BOTTOM") / 1000.0, span("DRIVE") / 1000.0,
                rom, active.maxOf { it.velocity.value }
            ))
        }
    }

    /** Plate observations by frame time (ms); [nearest] matches a frame within half a 60 fps frame. */
    private class PlateTrack(val byMs: java.util.TreeMap<Long, PlateObservation>) {
        fun nearest(ms: Long): PlateObservation? =
            listOfNotNull(byMs.floorEntry(ms), byMs.ceilingEntry(ms))
                .filter { kotlin.math.abs(it.key - ms) <= 8 }
                .minByOrNull { kotlin.math.abs(it.key - ms) }?.value
    }

    private fun parsePlates(json: String): PlateTrack {
        val byMs = java.util.TreeMap<Long, PlateObservation>()
        val entry = Regex("\\{\"t\"\\s*:\\s*([0-9.eE+-]+)\\s*,\\s*\"c\"\\s*:\\s*\\[([^\\]]*)\\]")
        for (m in entry.findAll(json)) {
            val (cx, cy, r) = m.groupValues[2].split(',').map { it.trim().toFloat() }
            byMs[Math.round(m.groupValues[1].toDouble() * 1000)] = PlateObservation(cx, cy, r, 1f)
        }
        return PlateTrack(byMs)
    }

    private fun parse(json: String): Dump {
        fun num(key: String) = Regex("\"$key\"\\s*:\\s*([0-9.]+)").find(json)!!.groupValues[1].toDouble()
        val poses = HashMap<Int, Pair<Long, List<PoseKeypoint>>>()
        val head = Pattern.compile("\\{\"i\":\\s*(\\d+),\\s*\"tUs\":\\s*(-?\\d+),\\s*\"lm\":\\s*\\[(.*?)\\]\\s*\\]\\s*\\}", Pattern.DOTALL)
        val m = head.matcher(json.substring(json.indexOf("\"poses\"")))
        while (m.find()) {
            val nums = Regex("-?[0-9.]+(?:[eE][-+]?\\d+)?").findAll(m.group(3).replace("[", " ")).map { it.value.toFloat() }.toList()
            poses[m.group(1).toInt()] = m.group(2).toLong() to nums.chunked(5).map { PoseKeypoint(it[0], it[1], it[2], it[3]) }
        }
        return Dump(num("captureFps"), num("frames").toInt(), num("width").toInt(), num("height").toInt(), poses)
    }
}
