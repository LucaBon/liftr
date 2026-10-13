package com.liftr.vision.recording

import com.liftr.domain.model.FrameAnnotation
import com.liftr.domain.model.VbtZone
import com.liftr.domain.value.MetersPerSecond

/**
 * Resolves which [FrameAnnotation] to draw for a given decoded video frame
 * during the annotated-video pass (Feature 09).
 *
 * Analysis only produces an annotation for frames that yielded a pose, so the
 * compositing pass sees gaps. For frames with no exact annotation we carry the
 * last known HUD values (rep count, velocity, VBT zone) forward so the overlay
 * numbers stay continuous, but draw no (stale) skeleton — the returned
 * annotation's [FrameAnnotation.pose] is null in that case.
 *
 * Pure Kotlin (no Android types) so it is unit-testable on the JVM.
 */
class AnnotationTimeline(annotations: List<FrameAnnotation>) {

    private val byTimestamp: Map<Long, FrameAnnotation> = annotations.associateBy { it.timestampMs }

    private var lastRepCount = 0
    private var lastZone: VbtZone? = null
    private var lastVelocity = MetersPerSecond(0f)

    /** The annotation to render for the frame at [timestampMs]. */
    fun annotationFor(timestampMs: Long): FrameAnnotation {
        val exact = byTimestamp[timestampMs]
        if (exact != null) {
            lastRepCount = exact.repCount
            lastZone = exact.vbtZone
            lastVelocity = exact.velocity
            return exact
        }
        return FrameAnnotation(
            timestampMs = timestampMs,
            pose = null,
            velocity = lastVelocity,
            repCount = lastRepCount,
            vbtZone = lastZone
        )
    }
}
