package com.liftr.domain.vision

import com.liftr.domain.logging.Logger
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.CalibrationQuality
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.hypot

/** Scale of a lifter lying on a bench, measured along the body ([BodyPositionCalculator.isLyingDown]). */
class LyingBodyScaleTest {

    private lateinit var calculator: BodyPositionCalculator
    /** Head landmarks to ankles: 89% of the 1.75 m height. */
    private val bodySpanM = 0.89f * 1.75f
    private val calibration = CalibrationData.fromUserInput(heightCm = 175f)

    @Before
    fun setup() {
        val logger: Logger = mockk(relaxed = true)
        calculator = BodyPositionCalculator(logger)
    }

    private data class Pt(val x: Float, val y: Float)

    /** Side-view pose: both sides of each joint share one point. */
    private fun pose(
        head: Pt, shoulder: Pt, elbow: Pt, wrist: Pt,
        hip: Pt, knee: Pt, ankle: Pt,
        width: Int = 1920, height: Int = 1080
    ): PoseResult {
        val lm = MutableList(33) { PoseKeypoint(0f, 0f, 0f, 0f) }
        fun set(p: Pt, vararg idx: Int) = idx.forEach { lm[it] = PoseKeypoint(p.x, p.y, 0f, 0.99f) }
        set(head, 0, 2, 5, 7, 8)
        set(shoulder, 11, 12)
        set(elbow, 13, 14)
        set(wrist, 15, 16)
        set(hip, 23, 24)
        set(knee, 25, 26)
        set(ankle, 27, 28)
        return PoseResult(lm, timestamp = 0L, imageWidth = width, imageHeight = height)
    }

    /** Lifter lying on a bench, filmed from the side in landscape, feet on the floor. */
    private fun lyingPose(wristY: Float = 0.30f, jitter: Float = 0f) = pose(
        head = Pt(0.25f, 0.45f + jitter),
        shoulder = Pt(0.32f, 0.46f),
        elbow = Pt(0.33f, 0.38f),
        wrist = Pt(0.33f, wristY),
        hip = Pt(0.55f, 0.47f),
        knee = Pt(0.72f, 0.42f),
        ankle = Pt(0.75f, 0.70f - jitter)
    )

    private fun ppmFromMeters(p: PoseResult): Float =
        // pixelsToMeters(y) = y * imageHeight / ppm, so y = 1 gives imageHeight / ppm.
        p.imageHeight / calculator.pixelsToMeters(1f, p, calibration)

    @Test
    fun `lying lifter is scaled by body length along its segments`() {
        val p = lyingPose()
        val w = p.imageWidth.toFloat()
        val h = p.imageHeight.toFloat()
        fun seg(a: Pt, b: Pt) = hypot((a.x - b.x) * w, (a.y - b.y) * h)
        val bodyPx = seg(Pt(0.25f, 0.45f), Pt(0.55f, 0.47f)) +
            seg(Pt(0.55f, 0.47f), Pt(0.72f, 0.42f)) +
            seg(Pt(0.72f, 0.42f), Pt(0.75f, 0.70f))

        assertEquals(bodyPx / bodySpanM, ppmFromMeters(p), bodyPx / bodySpanM * 0.01f)
        assertEquals(CalibrationQuality.OPTIMAL, calculator.getCalibrationQuality(p, calibration))
    }

    @Test
    fun `standing lifter keeps the vertical head to ankle scale`() {
        val p = pose(
            head = Pt(0.50f, 0.10f),
            shoulder = Pt(0.50f, 0.22f),
            elbow = Pt(0.52f, 0.35f),
            wrist = Pt(0.52f, 0.20f),
            hip = Pt(0.51f, 0.52f),
            knee = Pt(0.53f, 0.72f),
            ankle = Pt(0.50f, 0.92f),
            width = 1080, height = 1920
        )
        val expected = (0.92f - 0.10f) * 1920f / bodySpanM

        assertEquals(expected, ppmFromMeters(p), 0.5f)
    }

    @Test
    fun `lying lifter wrist height is stable under landmark jitter`() {
        val onePx = 1f / 1080f
        val heights = listOf(-2, -1, 0, 1, 2).map { j ->
            val p = lyingPose(jitter = j * onePx)
            val wristY = calculator.getBodyPartPositions(p)!!.wristsY!!
            calculator.pixelsToMeters(wristY, p, calibration)
        }

        val spread = heights.max() - heights.min()
        assertTrue("wrist height spread was ${spread * 100} cm", spread < 0.01f)
    }

    @Test
    fun `an arched lying lifter still sets the locked scale`() {
        // Walking in, farther from the camera: locks a smaller standing scale first.
        val standing = (0 until 15).map { i ->
            pose(
                head = Pt(0.50f, 0.40f), shoulder = Pt(0.50f, 0.45f), elbow = Pt(0.50f, 0.52f),
                wrist = Pt(0.50f, 0.58f), hip = Pt(0.50f, 0.56f), knee = Pt(0.50f, 0.63f),
                ankle = Pt(0.50f, 0.70f)
            ).copy(timestamp = i * 33L, timestampUs = i * 33_000L)
        }
        // Hips pushed up above the shoulders: fails the upright stacking check.
        val frames = (15 until 45).map { i ->
            pose(
                head = Pt(0.25f, 0.47f), shoulder = Pt(0.32f, 0.47f), elbow = Pt(0.33f, 0.38f),
                wrist = Pt(0.33f, 0.30f), hip = Pt(0.55f, 0.44f), knee = Pt(0.72f, 0.42f),
                ankle = Pt(0.75f, 0.70f)
            ).copy(timestamp = i * 33L, timestampUs = i * 33_000L)
        }
        val p = frames.first()
        val w = p.imageWidth.toFloat()
        val h = p.imageHeight.toFloat()
        fun seg(a: Pt, b: Pt) = hypot((a.x - b.x) * w, (a.y - b.y) * h)
        val bodyPx = seg(Pt(0.25f, 0.47f), Pt(0.55f, 0.44f)) +
            seg(Pt(0.55f, 0.44f), Pt(0.72f, 0.42f)) +
            seg(Pt(0.72f, 0.42f), Pt(0.75f, 0.70f))

        standing.forEach { calculator.lockedPixelsPerMeter(it, calibration) }
        val locked = frames.map { calculator.lockedPixelsPerMeter(it, calibration) }.last()

        assertEquals(bodyPx / bodySpanM, locked, bodyPx / bodySpanM * 0.01f)
    }

    @Test
    fun `lying lifter with feet raised above the head is not flipped`() {
        // Feet up on the bench end: ankles sit higher in the image than the head.
        val p = pose(
            head = Pt(0.25f, 0.50f),
            shoulder = Pt(0.32f, 0.50f),
            elbow = Pt(0.33f, 0.42f),
            wrist = Pt(0.33f, 0.30f),
            hip = Pt(0.55f, 0.49f),
            knee = Pt(0.68f, 0.38f),
            ankle = Pt(0.78f, 0.45f)
        )

        assertEquals(0.30f, calculator.getBodyPartPositions(p)!!.wristsY!!, 1e-6f)
    }
}
