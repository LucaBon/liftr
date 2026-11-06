package com.liftr.domain.exercise.strategy

import com.liftr.domain.exercise.ExercisePhaseState
import com.liftr.domain.exercise.FormIssue
import com.liftr.domain.exercise.worstPerKind
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.BodyPartPositions
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.CalibrationQuality
import com.liftr.domain.model.MovementPhase
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import com.liftr.domain.usecase.AnalyzeRepUseCase
import com.liftr.domain.vision.IPositionCalculator
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Form checks for the clean, replayed the way AnalyzeRepUseCase does it: every
 * frame of the rep buffered in ECCENTRIC/BOTTOM/CONCENTRIC is validated with the
 * phase detected for it and the phase state at rep completion, then the whole rep
 * goes through validateRep, and the issues are collapsed to one per kind.
 *
 * The lifter is filmed side-on facing +x with the left side towards the camera;
 * the far (right) side is barely visible and placed elsewhere, so any check that
 * averages left and right instead of using the near side reads it wrong.
 */
class CleanStrategyFormTest {

    private val calibrationData = CalibrationData(
        pixelsPerMeter = 500f,
        userHeight = 1.75f,
        shoulderWidth = 0.45f
    )

    private val strategy = CleanStrategy(FakePositionCalculator(), mockk<Logger>(relaxed = true))

    @Test
    fun `good rep has no faults`() {
        val issues = replay(goodRep())
        assertEquals(emptyList<String>(), issues.map { it.kind })
    }

    @Test
    fun `bent arms in the first pull is an early arm bend, once`() {
        val frames = goodRep().toMutableList()
        for (i in 3..5) {
            val f = frames[i]
            frames[i] = f.copy(elbow = P(f.shoulder.x + 0.06f, f.shoulder.y + 0.14f))
        }
        assertEquals(listOf("early_arm_bend"), replay(frames).map { it.kind })
    }

    @Test
    fun `bar dropping fast onto the rack at the catch is a bar crash, once`() {
        val frames = goodRep().toMutableList()
        // The bar falls 14 cm in one frame at the catch (4.2 m/s), then rides down.
        frames[10] = rack(sy = 0.53f, hip = P(0.40f, 0.72f), knee = P(0.60f, 0.76f))
        frames[11] = rack(sy = 0.54f, hip = P(0.40f, 0.74f), knee = P(0.60f, 0.76f))
        frames[12] = rack(sy = 0.55f, hip = P(0.40f, 0.76f), knee = P(0.60f, 0.76f))
        frames[13] = rack(sy = 0.55f, hip = P(0.40f, 0.76f), knee = P(0.60f, 0.76f))
        assertEquals(listOf("bar_crash"), replay(frames).map { it.kind })
    }

    @Test
    fun `landing 16 cm forward of the starting stance is a forward jump, once`() {
        val frames = goodRep().toMutableList()
        for (i in 10 until frames.size) frames[i] = frames[i].shifted(0.08f)
        assertEquals(listOf("forward_jump"), replay(frames).map { it.kind })
    }

    @Test
    fun `elbows pointing down in the rack is an elbow drop, once`() {
        val frames = goodRep().toMutableList()
        for (i in 10..16) {
            val f = frames[i]
            frames[i] = f.copy(elbow = P(f.shoulder.x + 0.02f, f.wrist.y + 0.15f))
        }
        assertEquals(listOf("elbow_drop"), replay(frames).map { it.kind })
    }

    // --- replay -----------------------------------------------------------

    private fun run(bodies: List<Body>): Pair<List<AnalyzeRepUseCase.RepFrame>, ExercisePhaseState> {
        var state = ExercisePhaseState.initial()
        val buffered = mutableListOf<AnalyzeRepUseCase.RepFrame>()
        var completed = false
        bodies.forEachIndexed { i, body ->
            val pose = body.pose(1_000L + i * 33L)
            val result = strategy.detectPhase(pose, calibrationData, state)
            state = result.newState
            if (state.currentPhase in BUFFERED) buffered += AnalyzeRepUseCase.RepFrame(pose, state.currentPhase)
            if (result.repCompleted) completed = true
        }
        assertTrue("fixture rep must complete", completed)
        return buffered to state
    }

    private fun replay(bodies: List<Body>): List<FormIssue> {
        val (frames, state) = run(bodies)
        assertTrue(frames.any { it.phase == MovementPhase.ECCENTRIC })
        assertTrue(frames.any { it.phase == MovementPhase.BOTTOM })
        assertTrue(frames.any { it.phase == MovementPhase.CONCENTRIC })
        val perFrame = frames.flatMap { (pose, phase) ->
            strategy.validateForm(pose, calibrationData, state, phase).issues
        }
        return (perFrame + strategy.validateRep(frames, calibrationData, state)).worstPerKind()
    }

    // --- fixture ----------------------------------------------------------

    /** A point in image-height units (x already scaled by the aspect ratio). */
    private data class P(val x: Float, val y: Float)

    private data class Body(
        val shoulder: P, val elbow: P, val wrist: P,
        val hip: P, val knee: P, val ankle: P
    ) {
        fun shifted(dx: Float) = Body(
            P(shoulder.x + dx, shoulder.y), P(elbow.x + dx, elbow.y), P(wrist.x + dx, wrist.y),
            P(hip.x + dx, hip.y), P(knee.x + dx, knee.y), P(ankle.x + dx, ankle.y)
        )

        fun pose(timestamp: Long): PoseResult {
            val aspect = WIDTH.toFloat() / HEIGHT
            val landmarks = MutableList(33) { PoseKeypoint(0f, 0f, 0f, 0.1f) }
            fun put(near: Int, far: Int, p: P) {
                landmarks[near] = PoseKeypoint(p.x / aspect, p.y, 0f, 0.95f)
                // Far side: hidden behind the body, poorly tracked and off.
                landmarks[far] = PoseKeypoint((p.x + 0.3f) / aspect, p.y + 0.05f, 0f, 0.2f)
            }
            landmarks[PoseResult.NOSE] = PoseKeypoint(shoulder.x / aspect, shoulder.y - 0.12f, 0f, 0.95f)
            put(PoseResult.LEFT_SHOULDER, PoseResult.RIGHT_SHOULDER, shoulder)
            put(PoseResult.LEFT_ELBOW, PoseResult.RIGHT_ELBOW, elbow)
            put(PoseResult.LEFT_WRIST, PoseResult.RIGHT_WRIST, wrist)
            put(PoseResult.LEFT_HIP, PoseResult.RIGHT_HIP, hip)
            put(PoseResult.LEFT_KNEE, PoseResult.RIGHT_KNEE, knee)
            put(PoseResult.LEFT_ANKLE, PoseResult.RIGHT_ANKLE, ankle)
            return PoseResult(landmarks, timestamp, WIDTH, HEIGHT, isPortrait = false)
        }
    }

    /** First-pull posture: arms hanging straight from the shoulders. */
    private fun pull(wristY: Float, hipY: Float) = Body(
        shoulder = P(0.54f, wristY - 0.28f),
        elbow = P(0.54f, wristY - 0.14f),
        wrist = P(0.54f, wristY),
        hip = P(0.42f, hipY),
        knee = P(0.56f, 0.78f),
        ankle = P(0.50f, 0.90f)
    )

    /** Second pull: hips extended, elbows bending up and out to pull under. */
    private fun highPull(wristY: Float) = Body(
        shoulder = P(0.52f, 0.32f),
        elbow = P(0.46f, 0.36f),
        wrist = P(0.55f, wristY),
        hip = P(0.50f, 0.58f),
        knee = P(0.52f, 0.74f),
        ankle = P(0.50f, 0.90f)
    )

    /** Front rack: wrists at shoulder height, elbows high and forward. */
    private fun rack(sy: Float, hip: P, knee: P) = Body(
        shoulder = P(0.50f, sy),
        elbow = P(0.58f, sy - 0.01f),
        wrist = P(0.53f, sy),
        hip = hip,
        knee = knee,
        ankle = P(0.50f, 0.90f)
    )

    private fun goodRep(): List<Body> = listOf(
        pull(0.78f, 0.68f),                                         // 0 IDLE: start
        pull(0.78f, 0.68f),                                         // 1 IDLE
        pull(0.75f, 0.675f),                                        // 2 first pull
        pull(0.72f, 0.670f),                                        // 3
        pull(0.69f, 0.665f),                                        // 4
        pull(0.66f, 0.660f),                                        // 5
        pull(0.62f, 0.620f),                                        // 6 second pull (hips snap)
        highPull(0.56f),                                            // 7
        highPull(0.50f),                                            // 8
        highPull(0.46f),                                            // 9 bar peaks
        rack(0.47f, P(0.40f, 0.72f), P(0.60f, 0.76f)),              // 10 catch
        rack(0.48f, P(0.40f, 0.74f), P(0.60f, 0.76f)),              // 11 ride down
        rack(0.49f, P(0.40f, 0.76f), P(0.60f, 0.76f)),              // 12
        rack(0.49f, P(0.40f, 0.76f), P(0.60f, 0.76f)),              // 13 bottom
        rack(0.45f, P(0.42f, 0.70f), P(0.58f, 0.76f)),              // 14 recovery
        rack(0.40f, P(0.45f, 0.65f), P(0.55f, 0.75f)),              // 15
        rack(0.35f, P(0.48f, 0.61f), P(0.52f, 0.74f)),              // 16
        rack(0.30f, P(0.50f, 0.58f), P(0.50f, 0.74f))               // 17 standing: rep
    )

    /**
     * Averages the sides that are visible, like the app's PositionCalculator;
     * one image height is 2 m.
     */
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
                elbowsX = avg(PoseResult.LEFT_ELBOW, PoseResult.RIGHT_ELBOW) { it.x },
                anklesY = avg(PoseResult.LEFT_ANKLE, PoseResult.RIGHT_ANKLE) { it.y },
                anklesX = avg(PoseResult.LEFT_ANKLE, PoseResult.RIGHT_ANKLE) { it.x },
                shouldersX = avg(PoseResult.LEFT_SHOULDER, PoseResult.RIGHT_SHOULDER) { it.x }
            )
        }

        override fun pixelsToMeters(pixelValue: Float, poseResult: PoseResult, calibrationData: CalibrationData?) =
            pixelValue * METERS_PER_IMAGE_HEIGHT

        override fun getCalibrationQuality(poseResult: PoseResult, calibrationData: CalibrationData?) =
            CalibrationQuality.OPTIMAL
    }

    private companion object {
        const val WIDTH = 1920
        const val HEIGHT = 1080
        const val METERS_PER_IMAGE_HEIGHT = 2f
        val BUFFERED = setOf(MovementPhase.ECCENTRIC, MovementPhase.BOTTOM, MovementPhase.CONCENTRIC)
    }
}
