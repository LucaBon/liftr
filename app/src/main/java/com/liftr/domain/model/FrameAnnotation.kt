package com.liftr.domain.model

import com.liftr.domain.value.MetersPerSecond

/**
 * Per-frame overlay data produced during post-exercise analysis and replayed
 * by the annotated-video compositor (Feature 09 — Video Recording with
 * skeleton overlay, built on top of Feature 17's recording pipeline).
 *
 * Each annotation is keyed by [timestampMs] so the compositing pass can match
 * it to the corresponding decoded video frame. Coordinates inside [pose] are
 * already in the decoded-frame's normalized space (the same bitmap the heavy
 * model ran on), so they map directly onto the frame with no rotation/mirror
 * transform.
 *
 * @property timestampMs Presentation timestamp of the source frame (ms)
 * @property pose Detected skeleton for this frame, or null if no pose
 * @property velocity Instantaneous bar velocity at this frame
 * @property repCount Cumulative reps completed up to and including this frame
 * @property vbtZone VBT zone for the velocity, used to colour the HUD
 * @property phaseLabel Optional movement-phase label (e.g. "DESCENT")
 */
data class FrameAnnotation(
    val timestampMs: Long,
    val pose: PoseResult?,
    val velocity: MetersPerSecond,
    val repCount: Int,
    val vbtZone: VbtZone?,
    val phaseLabel: String? = null
)
