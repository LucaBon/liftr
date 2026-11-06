package com.liftr.domain.replay

import com.liftr.domain.exercise.ExerciseStrategyFactory
import com.liftr.domain.exercise.strategy.JumpStrategy
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.AnthropometricData
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import com.liftr.domain.usecase.AnalyzeRepUseCase
import com.liftr.domain.usecase.AnalyzeVideoUseCase
import com.liftr.domain.value.Kilograms
import com.liftr.domain.vision.VideoPoseSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/**
 * Replays an offline per-frame pose dump (see [JumpReplayTest] for the strategy-only replay) through [AnalyzeVideoUseCase] and the real
 * [JumpStrategy], with the same coarse/dense frame sampling the phone's
 * `VideoFilePoseSource` gives it. Skipped unless LIFTR_REPLAY is set.
 *
 * Dump format: `{captureFps, frames, width, height, poses:[{i, tUs, lm:[[x,y,z,vis,pres] x33]}]}`
 * (MediaPipe landmark order, normalized coords). A frame without a pose entry is a
 * frame the detector found nothing in.
 *
 * Run (comma-separated files; add -i to see the strategy log):
 * `LIFTR_REPLAY=/path/a.json,/path/b.json ./gradlew :domain:test --tests '*VideoReplayTest' -i`
 * Optional: LIFTR_REPLAY_TYPE (default VERTICAL_JUMP), LIFTR_REPLAY_HEIGHT_M (default 1.75),
 * LIFTR_REPLAY_TIMESCALE (slow-mo factor, default 1).
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
        val strategy = JumpStrategy(type, logger)
        val factory = mockk<ExerciseStrategyFactory> { every { getStrategy(any()) } returns strategy }
        val calculator = mockk<com.liftr.domain.vision.IPositionCalculator>(relaxed = true)
        val useCase = AnalyzeVideoUseCase(
            calculator, factory, AnalyzeRepUseCase(factory, logger), mockk(relaxed = true), logger
        )
        // One decoded frame per index, like the extractor; only accepted ones carry a pose.
        val source = object : VideoPoseSource {
            override val totalFrames = dump.frames
            override val timeScale = scale
            override fun poses(include: (Long) -> Boolean): Flow<VideoPoseSource.Frame> = flow {
                for (i in 0 until dump.frames) {
                    val entry = dump.poses[i]
                    val tUs = entry?.first ?: Math.round(i * 1_000_000.0 / dump.captureFps)
                    val pose = if (include(tUs) && entry != null) {
                        PoseResult(entry.second, tUs / 1_000, dump.width, dump.height, dump.height > dump.width, tUs)
                    } else null
                    emit(VideoPoseSource.Frame(tUs / 1_000, pose))
                }
            }
            override fun close() = Unit
        }
        val calibration = CalibrationData(500f, height, 0.45f, AnthropometricData.getDefaultRatios(height, 75f))
        println("=== ${file.name} (${dump.frames} frames @ ${dump.captureFps} fps, ${dump.width}x${dump.height}) ===")
        val summary = runBlocking {
            useCase.invoke(
                AnalyzeVideoUseCase.Params(File(file.name), type, Kilograms(0f), 1, calibration),
                source, onProgress = {}
            ).getOrThrow()
        }
        println("RESULT ${file.name}: reps=${summary.completedReps.value} heights=${summary.repJumpHeights.map { it.value }} " +
            "apex=${summary.repApexHeights.map { it?.value }}")
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
