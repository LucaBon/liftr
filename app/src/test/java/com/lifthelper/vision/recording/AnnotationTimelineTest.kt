package com.lifthelper.vision.recording

import com.lifthelper.domain.model.FrameAnnotation
import com.lifthelper.domain.model.PoseResult
import com.lifthelper.domain.model.VbtZone
import com.lifthelper.domain.value.MetersPerSecond
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [AnnotationTimeline] — the pure frame→annotation matching with
 * carry-forward used by the annotated-video pass (Feature 09).
 */
class AnnotationTimelineTest {

    private fun pose(ts: Long) = PoseResult(landmarks = emptyList(), timestamp = ts)

    private fun annotation(
        ts: Long,
        rep: Int,
        velocity: Float,
        zone: VbtZone?
    ) = FrameAnnotation(
        timestampMs = ts,
        pose = pose(ts),
        velocity = MetersPerSecond(velocity),
        repCount = rep,
        vbtZone = zone
    )

    @Test
    fun `exact match returns the source annotation with its pose`() {
        val source = annotation(100L, rep = 2, velocity = 0.8f, zone = VbtZone.SPEED_STRENGTH)
        val timeline = AnnotationTimeline(listOf(source))

        val result = timeline.annotationFor(100L)

        assertEquals(source, result)
        assertNotNull(result.pose)
        assertEquals(2, result.repCount)
    }

    @Test
    fun `frame before any annotation yields neutral defaults and no skeleton`() {
        val timeline = AnnotationTimeline(listOf(annotation(100L, 1, 0.5f, VbtZone.STRENGTH_SPEED)))

        val result = timeline.annotationFor(50L)

        assertNull(result.pose)
        assertEquals(0, result.repCount)
        assertEquals(0f, result.velocity.value, 0.0001f)
        assertNull(result.vbtZone)
    }

    @Test
    fun `gap frame carries forward HUD values but drops the stale skeleton`() {
        val timeline = AnnotationTimeline(
            listOf(annotation(100L, rep = 3, velocity = 0.72f, zone = VbtZone.STRENGTH_SPEED))
        )

        // Consume the exact frame, then a gap frame after it.
        timeline.annotationFor(100L)
        val gap = timeline.annotationFor(133L)

        assertNull("stale skeleton must not be re-drawn", gap.pose)
        assertEquals(3, gap.repCount)
        assertEquals(0.72f, gap.velocity.value, 0.0001f)
        assertEquals(VbtZone.STRENGTH_SPEED, gap.vbtZone)
        assertEquals(133L, gap.timestampMs)
    }

    @Test
    fun `carry-forward updates as later annotations are matched`() {
        val timeline = AnnotationTimeline(
            listOf(
                annotation(100L, rep = 1, velocity = 0.9f, zone = VbtZone.SPEED_STRENGTH),
                annotation(200L, rep = 2, velocity = 0.4f, zone = VbtZone.MAX_STRENGTH)
            )
        )

        timeline.annotationFor(100L)
        val midGap = timeline.annotationFor(150L)
        assertEquals(1, midGap.repCount)
        assertEquals(VbtZone.SPEED_STRENGTH, midGap.vbtZone)

        timeline.annotationFor(200L)
        val lateGap = timeline.annotationFor(250L)
        assertEquals(2, lateGap.repCount)
        assertEquals(VbtZone.MAX_STRENGTH, lateGap.vbtZone)
        assertEquals(0.4f, lateGap.velocity.value, 0.0001f)
    }
}
