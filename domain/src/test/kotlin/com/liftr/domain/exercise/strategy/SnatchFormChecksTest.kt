package com.liftr.domain.exercise.strategy

import com.liftr.domain.exercise.FormIssue
import com.liftr.domain.exercise.worstPerKind
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.AnthropometricData
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.MovementPhase
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import com.liftr.domain.usecase.AnalyzeRepUseCase
import com.liftr.domain.vision.IPositionCalculator
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Snatch form checks over a whole rep, filmed side-on: each check runs only in
 * the part of the lift it is about, and a fault is reported once per rep.
 */
class SnatchFormChecksTest {

    private lateinit var strategy: SnatchStrategy

    private val calibrationData = CalibrationData(
        pixelsPerMeter = 500f,
        userHeight = 1.75f,
        shoulderWidth = 0.45f,
        anthropometricData = AnthropometricData.getDefaultRatios(1.75f, 75f),
        timestamp = 0L
    )

    @Before
    fun setup() {
        // Form checks read landmarks only; 2 m per image height.
        val positionCalculator = mockk<IPositionCalculator>()
        every { positionCalculator.pixelsToMeters(any(), any(), any()) } answers { firstArg<Float>() * 2f }
        strategy = SnatchStrategy(positionCalculator, mockk<Logger>(relaxed = true))
    }

    /** A joint in image-height units: x forward from the body's line, y down from the top. */
    private data class J(val x: Float, val y: Float)

    private data class Body(
        val nose: J, val ear: J,
        val shoulder: J, val elbow: J, val wrist: J,
        val hip: J, val knee: J, val ankle: J
    )

    private val pull = Body(
        nose = J(0.08f, 0.20f), ear = J(0.03f, 0.20f),
        shoulder = J(0.03f, 0.30f), elbow = J(0.03f, 0.42f), wrist = J(0.03f, 0.54f),
        hip = J(-0.05f, 0.52f), knee = J(0.04f, 0.70f), ankle = J(0f, 0.90f)
    )
    // Pulling under the bar: arms bent, wrists at the chest.
    private val pullUnder = pull.copy(elbow = J(-0.06f, 0.36f), wrist = J(0.06f, 0.33f))
    // Overhead, the bar 0.06 image heights (12 cm) behind the nose: over the back of
    // the head. Without the aspect correction it would read 21 cm.
    private val deepCatch = Body(
        nose = J(0.06f, 0.40f), ear = J(0.01f, 0.40f),
        shoulder = J(0f, 0.50f), elbow = J(0f, 0.38f), wrist = J(0f, 0.26f),
        hip = J(-0.10f, 0.72f), knee = J(0.12f, 0.70f), ankle = J(0f, 0.90f)
    )
    private val standingOverhead = Body(
        nose = J(0.06f, 0.15f), ear = J(0.01f, 0.15f),
        shoulder = J(0f, 0.25f), elbow = J(0f, 0.13f), wrist = J(0f, 0.01f),
        hip = J(0f, 0.50f), knee = J(0.02f, 0.70f), ankle = J(0f, 0.90f)
    )
    // Elbows bent with the bar still above the head.
    private fun Body.bentOverhead() = copy(
        elbow = J(shoulder.x + 0.10f, shoulder.y - 0.05f),
        wrist = J(shoulder.x, shoulder.y - 0.18f)
    )

    private var clock = 0L

    /**
     * The body facing [facing] (+1 = right of the frame, -1 = left), its left
     * side toward the camera; the far side is hidden, at a wrong place.
     */
    private fun pose(body: Body, facing: Int = 1): PoseResult {
        val width = 1080
        val height = 1920
        val xPerHeightUnit = height.toFloat() / width
        fun kp(j: J, visibility: Float) = PoseKeypoint(0.5f + facing * j.x * xPerHeightUnit, j.y, 0f, visibility)
        val landmarks = MutableList(33) { PoseKeypoint(0.9f, 0.9f, 0f, 0.1f) }
        fun near(index: Int, j: J) {
            landmarks[index] = kp(j, 0.9f)
            landmarks[index + 1] = kp(J(j.x + 0.3f, j.y + 0.1f), 0.1f)
        }
        landmarks[PoseResult.NOSE] = kp(body.nose, 0.9f)
        near(PoseResult.LEFT_EAR, body.ear)
        near(PoseResult.LEFT_SHOULDER, body.shoulder)
        near(PoseResult.LEFT_ELBOW, body.elbow)
        near(PoseResult.LEFT_WRIST, body.wrist)
        near(PoseResult.LEFT_HIP, body.hip)
        near(PoseResult.LEFT_KNEE, body.knee)
        near(PoseResult.LEFT_ANKLE, body.ankle)
        clock += 33L
        return PoseResult(landmarks = landmarks, timestamp = clock, imageWidth = width, imageHeight = height, isPortrait = true)
    }

    private fun rep(
        catch: Body = deepCatch,
        recovery: Body = standingOverhead,
        facing: Int = 1
    ): List<AnalyzeRepUseCase.RepFrame> {
        fun f(body: Body, phase: MovementPhase) = AnalyzeRepUseCase.RepFrame(pose(body, facing), phase)
        return listOf(
            // The previous rep's bar coming down, bent arms still overhead.
            f(standingOverhead.bentOverhead(), MovementPhase.TOP),
            f(pull, MovementPhase.IDLE),
            f(pull, MovementPhase.ECCENTRIC),
            f(pull, MovementPhase.ECCENTRIC),
            f(pullUnder, MovementPhase.BOTTOM),
            f(catch, MovementPhase.CONCENTRIC),
            f(catch, MovementPhase.CONCENTRIC),
            f(recovery, MovementPhase.CONCENTRIC),
            f(standingOverhead, MovementPhase.TOP)
        )
    }

    /** Like AnalyzeRepUseCase: every frame, then the whole rep, one fault per kind. */
    private fun faults(frames: List<AnalyzeRepUseCase.RepFrame>): List<FormIssue> {
        val issues = frames.flatMap { (pose, phase) ->
            strategy.validateForm(pose, calibrationData, null, phase).issues
        } + strategy.validateRep(frames, calibrationData, null)
        return issues.worstPerKind()
    }

    @Test
    fun `good rep has no faults`() {
        assertEquals(emptyList<FormIssue>(), faults(rep()))
    }

    @Test
    fun `good rep facing the other way has no faults`() {
        assertEquals(emptyList<FormIssue>(), faults(rep(facing = -1)))
    }

    @Test
    fun `catch above parallel is one catch depth fault`() {
        val powerCatch = deepCatch.copy(hip = J(-0.12f, 0.62f), knee = J(0.10f, 0.72f))
        val frames = rep(catch = powerCatch)

        assertEquals(listOf("catch_depth"), faults(frames).map { it.kind })
        assertEquals(1, strategy.validateRep(frames, calibrationData, null).count { it.kind == "catch_depth" })
    }

    @Test
    fun `bent arms overhead after the catch is one press out fault`() {
        assertEquals(listOf("press_out"), faults(rep(catch = deepCatch.bentOverhead())).map { it.kind })
    }

    @Test
    fun `bar far behind the head overhead is one fault`() {
        // Wrists 0.10 image heights (20 cm) behind the nose.
        val behind = deepCatch.copy(elbow = J(-0.02f, 0.38f), wrist = J(-0.04f, 0.26f))

        assertEquals(listOf("bar_behind_head"), faults(rep(catch = behind)).map { it.kind })
        assertEquals(listOf("bar_behind_head"), faults(rep(catch = behind, facing = -1)).map { it.kind })
    }

    @Test
    fun `bar in front of the head is not behind it`() {
        val inFront = deepCatch.copy(elbow = J(0.08f, 0.38f), wrist = J(0.16f, 0.26f))

        assertEquals(emptyList<String>(), faults(rep(catch = inFront)).map { it.kind })
    }
}
