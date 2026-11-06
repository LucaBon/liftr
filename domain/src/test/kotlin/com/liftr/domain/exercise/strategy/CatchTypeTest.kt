package com.liftr.domain.exercise.strategy

import com.liftr.domain.exercise.ExercisePhaseState
import com.liftr.domain.exercise.ExerciseStrategy
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.BodyPartPositions
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.CalibrationQuality
import com.liftr.domain.model.CatchType
import com.liftr.domain.model.MovementPhase
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import com.liftr.domain.usecase.AnalyzeRepUseCase
import com.liftr.domain.vision.IPositionCalculator
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

/**
 * Snatch and clean counted and labelled by how the bar was caught: synthetic
 * lifts on a time axis (so the same lift is sampled at 30 and 240 fps), replayed
 * through detectPhase like the app does, then classifyCatch over the rep's frames.
 */
class CatchTypeTest {

    private val calibration = CalibrationData(pixelsPerMeter = 500f, userHeight = 1.75f, shoulderWidth = 0.45f)
    private val positionCalculator = FakePositionCalculator()
    private val logger = mockk<Logger>(relaxed = true)

    /** Side-on joint heights in image-height units (2 m), larger = lower. */
    private class Key(
        val t: Float,
        val nose: Float, val shoulder: Float, val wrist: Float, val hip: Float, val knee: Float,
        val hipX: Float, val kneeX: Float
    )

    private fun key(t: Float, v: FloatArray) = Key(t, v[0], v[1], v[2], v[3], v[4], v[5], v[6])

    // nose, shoulder, wrist, hip, knee, hipX, kneeX
    private val floor = floatArrayOf(0.25f, 0.35f, 0.75f, 0.62f, 0.74f, 0.40f, 0.55f)
    private val kneePull = floatArrayOf(0.24f, 0.34f, 0.62f, 0.60f, 0.74f, 0.42f, 0.55f)
    private val extended = floatArrayOf(0.15f, 0.25f, 0.35f, 0.48f, 0.70f, 0.50f, 0.50f)
    private val standing = floatArrayOf(0.15f, 0.25f, 0.01f, 0.50f, 0.70f, 0.50f, 0.50f)

    private val pullKeys = listOf(0f to floor, 0.5f to floor, 1.0f to kneePull, 1.2f to extended)

    private fun lift(vararg after: Pair<Float, FloatArray>) = (pullKeys + after).map { key(it.first, it.second) }

    // Snatch: the bar goes overhead at the top of the pull.
    private val snatchTop = 1.4f to floatArrayOf(0.13f, 0.23f, 0.00f, 0.47f, 0.70f, 0.50f, 0.50f)
    private val snatchMuscle = lift(snatchTop, 2.2f to standing)
    private val snatchPower = lift(
        snatchTop, 1.7f to floatArrayOf(0.30f, 0.40f, 0.17f, 0.62f, 0.70f, 0.40f, 0.60f), 2.4f to standing
    )
    private val snatchSquat = lift(
        snatchTop, 1.7f to floatArrayOf(0.45f, 0.55f, 0.32f, 0.76f, 0.70f, 0.40f, 0.60f), 2.6f to standing
    )

    // Clean: the bar peaks, then comes down to the shoulders.
    private val cleanTop = 1.35f to floatArrayOf(0.13f, 0.23f, 0.20f, 0.47f, 0.70f, 0.50f, 0.50f)
    private val rackStanding = floatArrayOf(0.15f, 0.25f, 0.26f, 0.50f, 0.70f, 0.50f, 0.50f)
    private val cleanMuscle = lift(cleanTop, 1.65f to rackStanding, 2.4f to rackStanding)
    private val cleanPower = lift(
        cleanTop, 1.7f to floatArrayOf(0.30f, 0.40f, 0.40f, 0.62f, 0.70f, 0.40f, 0.60f), 2.5f to rackStanding
    )
    private val cleanSquat = lift(
        cleanTop, 1.7f to floatArrayOf(0.45f, 0.55f, 0.55f, 0.76f, 0.70f, 0.40f, 0.60f), 2.7f to rackStanding
    )

    private fun at(keys: List<Key>, t: Float): Key {
        val i = keys.indexOfLast { it.t <= t }.coerceAtMost(keys.size - 2)
        val a = keys[i]
        val b = keys[i + 1]
        val k = ((t - a.t) / (b.t - a.t)).coerceIn(0f, 1f)
        fun m(x: Float, y: Float) = x + (y - x) * k
        return Key(t, m(a.nose, b.nose), m(a.shoulder, b.shoulder), m(a.wrist, b.wrist), m(a.hip, b.hip),
            m(a.knee, b.knee), m(a.hipX, b.hipX), m(a.kneeX, b.kneeX))
    }

    private fun pose(k: Key): PoseResult {
        val aspect = WIDTH.toFloat() / HEIGHT
        val landmarks = MutableList(33) { PoseKeypoint(0f, 0f, 0f, 0.1f) }
        fun put(near: Int, far: Int, x: Float, y: Float) {
            landmarks[near] = PoseKeypoint(x / aspect, y, 0f, 0.95f)
            landmarks[far] = PoseKeypoint((x + 0.3f) / aspect, y + 0.05f, 0f, 0.2f)
        }
        landmarks[PoseResult.NOSE] = PoseKeypoint(0.5f / aspect, k.nose, 0f, 0.95f)
        put(PoseResult.LEFT_SHOULDER, PoseResult.RIGHT_SHOULDER, 0.5f, k.shoulder)
        put(PoseResult.LEFT_ELBOW, PoseResult.RIGHT_ELBOW, 0.5f, (k.shoulder + k.wrist) / 2)
        put(PoseResult.LEFT_WRIST, PoseResult.RIGHT_WRIST, 0.5f, k.wrist)
        put(PoseResult.LEFT_HIP, PoseResult.RIGHT_HIP, k.hipX, k.hip)
        put(PoseResult.LEFT_KNEE, PoseResult.RIGHT_KNEE, k.kneeX, k.knee)
        put(PoseResult.LEFT_ANKLE, PoseResult.RIGHT_ANKLE, 0.5f, 0.90f)
        val us = (k.t * 1_000_000f).roundToLong()
        return PoseResult(landmarks, us / 1000, WIDTH, HEIGHT, isPortrait = false, timestampUs = us)
    }

    /** The lift sampled at [fps]: whether it counted, and the frames buffered up to the count. */
    private fun replay(strategy: ExerciseStrategy, keys: List<Key>, fps: Int): Pair<Boolean, List<AnalyzeRepUseCase.RepFrame>> {
        var state = ExercisePhaseState.initial()
        val buffered = mutableListOf<AnalyzeRepUseCase.RepFrame>()
        var counted = false
        for (i in 0..(keys.last().t * fps).toInt()) {
            val p = pose(at(keys, i.toFloat() / fps))
            val result = strategy.detectPhase(p, calibration, state)
            state = result.newState
            if (state.currentPhase in BUFFERED) buffered += AnalyzeRepUseCase.RepFrame(p, state.currentPhase)
            if (result.repCompleted) { counted = true; break }
        }
        return counted to buffered
    }

    private fun check(strategy: ExerciseStrategy, keys: List<Key>, expected: CatchType) {
        for (fps in listOf(30, 240)) {
            val (counted, frames) = replay(strategy, keys, fps)
            assertTrue("$expected counted at $fps fps", counted)
            assertEquals("$expected at $fps fps", expected, strategy.classifyCatch(frames, calibration))
        }
    }

    private fun snatch() = SnatchStrategy(positionCalculator, logger)
    private fun clean() = CleanStrategy(positionCalculator, logger)

    @Test fun `muscle snatch is counted and labelled muscle`() = check(snatch(), snatchMuscle, CatchType.MUSCLE)
    @Test fun `power snatch is labelled power`() = check(snatch(), snatchPower, CatchType.POWER)
    @Test fun `squat snatch is labelled squat`() = check(snatch(), snatchSquat, CatchType.SQUAT)
    @Test fun `muscle clean is counted and labelled muscle`() = check(clean(), cleanMuscle, CatchType.MUSCLE)
    @Test fun `power clean is labelled power`() = check(clean(), cleanPower, CatchType.POWER)
    @Test fun `squat clean is labelled squat`() = check(clean(), cleanSquat, CatchType.SQUAT)

    @Test
    fun `a muscle snatch gets no catch depth note, a power snatch an unscored one`() {
        for ((keys, depthNote) in listOf(snatchMuscle to false, snatchPower to true, snatchSquat to false)) {
            val (_, frames) = replay(snatch(), keys, 30)
            val issue = snatch().validateRep(frames, calibration).firstOrNull { it.kind == "catch_depth" }
            assertEquals(depthNote, issue != null)
            issue?.let { assertEquals(false, it.scored) }
        }
    }

    @Test
    fun `no catch frames means no catch type`() {
        assertEquals(null, snatch().classifyCatch(emptyList(), calibration))
    }

    private class FakePositionCalculator : IPositionCalculator {
        override fun getBodyPartPositions(poseResult: PoseResult): BodyPartPositions {
            val lm = poseResult.landmarks
            fun avg(l: Int, r: Int, pick: (PoseKeypoint) -> Float): Float? =
                listOf(lm[l], lm[r]).filter { it.visibility >= 0.5f }
                    .takeIf { it.isNotEmpty() }?.map(pick)?.average()?.toFloat()
            return BodyPartPositions(
                hipsY = avg(PoseResult.LEFT_HIP, PoseResult.RIGHT_HIP) { it.y },
                shouldersY = avg(PoseResult.LEFT_SHOULDER, PoseResult.RIGHT_SHOULDER) { it.y },
                headY = lm[PoseResult.NOSE].y,
                kneesY = avg(PoseResult.LEFT_KNEE, PoseResult.RIGHT_KNEE) { it.y },
                hipsX = avg(PoseResult.LEFT_HIP, PoseResult.RIGHT_HIP) { it.x },
                kneesX = avg(PoseResult.LEFT_KNEE, PoseResult.RIGHT_KNEE) { it.x },
                wristsY = avg(PoseResult.LEFT_WRIST, PoseResult.RIGHT_WRIST) { it.y },
                wristsX = avg(PoseResult.LEFT_WRIST, PoseResult.RIGHT_WRIST) { it.x },
                elbowsY = avg(PoseResult.LEFT_ELBOW, PoseResult.RIGHT_ELBOW) { it.y },
                anklesY = avg(PoseResult.LEFT_ANKLE, PoseResult.RIGHT_ANKLE) { it.y },
                anklesX = avg(PoseResult.LEFT_ANKLE, PoseResult.RIGHT_ANKLE) { it.x },
                shouldersX = avg(PoseResult.LEFT_SHOULDER, PoseResult.RIGHT_SHOULDER) { it.x }
            )
        }

        override fun pixelsToMeters(pixelValue: Float, poseResult: PoseResult, calibrationData: CalibrationData?) =
            pixelValue * 2f

        override fun getCalibrationQuality(poseResult: PoseResult, calibrationData: CalibrationData?) =
            CalibrationQuality.OPTIMAL
    }

    private companion object {
        const val WIDTH = 1920
        const val HEIGHT = 1080
        val BUFFERED = setOf(MovementPhase.ECCENTRIC, MovementPhase.BOTTOM, MovementPhase.CONCENTRIC)
    }
}
