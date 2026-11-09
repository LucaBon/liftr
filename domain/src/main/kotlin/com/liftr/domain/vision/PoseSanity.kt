package com.liftr.domain.vision

import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult

/**
 * Plausibility gate for upright lifts (squats). When the plate hides the upper
 * body, the pose model can fold the skeleton onto the legs or turn it upside
 * down (head below the shoulders, shoulders below the hips). Such a frame says
 * nothing about where the body is: it is dropped from tracking and form checks.
 */
object PoseSanity {

    /** Fastest a squat's tracked point can really move; anything faster is a landmark jump. */
    const val MAX_SQUAT_SPEED_MPS = 3f

    /**
     * Shortest vertical torso, as a share of the standing one, a real squat
     * shows: even a deep, forward-leaning squat keeps well over half of it.
     */
    const val MIN_TORSO_SHARE = 0.5f

    /**
     * True when at least one shoulder and one hip are visible and the visible
     * head, shoulders and hips are stacked top to bottom (smaller y = higher).
     * With [standingTorso] (see [torsoHeight]), a torso folded to under
     * [MIN_TORSO_SHARE] of it is rejected too.
     */
    fun isPlausible(pose: PoseResult, standingTorso: Float? = null): Boolean {
        val lm = pose.landmarks
        val shouldersY = meanY(lm, PoseResult.LEFT_SHOULDER, PoseResult.RIGHT_SHOULDER) ?: return false
        val hipsY = meanY(lm, PoseResult.LEFT_HIP, PoseResult.RIGHT_HIP) ?: return false
        if (shouldersY >= hipsY) return false
        if (standingTorso != null && hipsY - shouldersY < standingTorso * MIN_TORSO_SHARE) return false
        val headY = meanY(
            lm, PoseResult.NOSE, PoseResult.LEFT_EYE, PoseResult.RIGHT_EYE, PoseResult.LEFT_EAR, PoseResult.RIGHT_EAR
        )
        return headY == null || headY < shouldersY
    }

    /** Vertical shoulder-to-hip distance (normalized y), or null when either is not visible. */
    fun torsoHeight(pose: PoseResult): Float? {
        val shouldersY = meanY(pose.landmarks, PoseResult.LEFT_SHOULDER, PoseResult.RIGHT_SHOULDER) ?: return null
        val hipsY = meanY(pose.landmarks, PoseResult.LEFT_HIP, PoseResult.RIGHT_HIP) ?: return null
        return hipsY - shouldersY
    }

    private fun meanY(lm: List<PoseKeypoint>, vararg ids: Int): Float? =
        ids.map { lm.getOrNull(it) }.filterNotNull()
            .filter { it.visibility >= PoseKeypoint.VISIBILITY_THRESHOLD }
            .takeIf { it.isNotEmpty() }
            ?.map { it.y }?.average()?.toFloat()
}
