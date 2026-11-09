package com.liftr.vision.calculator

import com.liftr.domain.exercise.ExercisePhaseState
import com.liftr.domain.exercise.strategy.BenchPressStrategy
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random

/**
 * Bench rep counting through the real [PositionCalculator] scale, for a lifter
 * lying on a bench filmed from the side in landscape. The strategy tests mock
 * the scale, which hid that a lying body was measured as a standing one.
 * An in-app recording starts before the lifter lies down, so the walk-in
 * cases begin standing next to the bench.
 */
class BenchPressScaleTest {

    private val logger: Logger = mockk(relaxed = true)
    private val calibration = CalibrationData.fromUserInput(heightCm = 175f)

    private data class Pt(val x: Float, val y: Float)

    private fun lerp(a: Pt, b: Pt, t: Float) = Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    // About 35 cm of bar travel for a 1.75 m lifter spanning ~1215 px in 1920x1080.
    private val shoulder = Pt(0.32f, 0.46f)
    private val wristTop = Pt(0.33f, 0.20f)
    private val wristBottom = Pt(0.33f, 0.425f)
    private val elbowTop = Pt(0.325f, 0.33f)
    private val elbowBottom = Pt(0.27f, 0.50f)

    private class Body(
        val head: Pt, val shoulder: Pt, val elbow: Pt, val wrist: Pt,
        val hip: Pt, val knee: Pt, val ankle: Pt
    )

    /** [depth] 0 = lockout, 1 = bar on chest. */
    private fun lying(depth: Float) = Body(
        head = Pt(0.25f, 0.45f),
        shoulder = shoulder,
        elbow = lerp(elbowTop, elbowBottom, depth),
        wrist = lerp(wristTop, wristBottom, depth),
        hip = Pt(0.55f, 0.47f),
        knee = Pt(0.72f, 0.42f),
        ankle = Pt(0.75f, 0.70f)
    )

    /** Standing next to the bench, arms down: where an in-app recording starts. */
    private val standing = Body(
        head = Pt(0.50f, 0.15f), shoulder = Pt(0.50f, 0.25f), elbow = Pt(0.50f, 0.38f),
        wrist = Pt(0.50f, 0.50f), hip = Pt(0.50f, 0.52f), knee = Pt(0.50f, 0.72f),
        ankle = Pt(0.50f, 0.92f)
    )

    /** Sitting on the bench end, hands on the knees, before lying back. */
    private val sitting = Body(
        head = Pt(0.45f, 0.35f), shoulder = Pt(0.45f, 0.45f), elbow = Pt(0.47f, 0.55f),
        wrist = Pt(0.50f, 0.60f), hip = Pt(0.45f, 0.65f), knee = Pt(0.55f, 0.65f),
        ankle = Pt(0.55f, 0.90f)
    )

    /** Every landmark gets ±2 px of noise. */
    private fun frame(body: Body, timeMs: Long, rnd: Random): PoseResult {
        val lm = MutableList(33) { PoseKeypoint(0f, 0f, 0f, 0f) }
        fun set(p: Pt, vararg idx: Int) = idx.forEach {
            val nx = (rnd.nextFloat() * 4f - 2f) / 1920f
            val ny = (rnd.nextFloat() * 4f - 2f) / 1080f
            lm[it] = PoseKeypoint(p.x + nx, p.y + ny, 0f, 0.95f)
        }
        set(body.head, 0, 2, 5, 7, 8)
        set(body.shoulder, 11, 12)
        set(body.elbow, 13, 14)
        set(body.wrist, 15, 16)
        set(body.hip, 23, 24)
        set(body.knee, 25, 26)
        set(body.ankle, 27, 28)
        return PoseResult(lm, timestamp = timeMs, imageWidth = 1920, imageHeight = 1080)
    }

    private fun countReps(reps: Int, walkIn: Boolean = false): Int {
        val fps = 30
        val repFrames = 2 * fps
        val pauseFrames = fps

        val bodies = mutableListOf<Body>()
        if (walkIn) {
            repeat(2 * fps) { bodies += standing }
            repeat(fps) { bodies += sitting }
        }
        repeat(pauseFrames) { bodies += lying(0f) }
        repeat(reps) {
            for (f in 0 until repFrames) {
                // 0 → 1 → 0 over one rep.
                bodies += lying(((1 - cos(2 * PI * f / repFrames)) / 2).toFloat())
            }
            repeat(pauseFrames) { bodies += lying(0f) }
        }

        val strategy = BenchPressStrategy(PositionCalculator(logger), logger)
        val rnd = Random(42)
        var state = ExercisePhaseState.initial()
        var count = 0
        bodies.forEachIndexed { i, body ->
            val result = strategy.detectPhase(frame(body, i * 1000L / fps, rnd), calibration, state)
            if (result.repCompleted) count++
            state = result.newState
        }
        return count
    }

    @Test
    fun `three bench reps lying down are counted`() {
        assertEquals(3, countReps(3))
    }

    @Test
    fun `lying still on the bench counts no reps`() {
        assertEquals(0, countReps(0))
    }

    @Test
    fun `reps count when the recording starts with the lifter standing`() {
        assertEquals(3, countReps(3, walkIn = true))
    }

    @Test
    fun `walking in and lying still counts no reps`() {
        assertEquals(0, countReps(0, walkIn = true))
    }
}
