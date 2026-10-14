package com.liftr.domain.calculator

import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import kotlin.math.abs

/** Which way the athlete faces the camera. */
enum class CameraView { SIDE, FRONT, UNKNOWN }

/**
 * Tells a side-on athlete from one facing the camera by how wide the shoulders
 * look relative to body height: about 0.25 facing the camera, 0.18 at 45° and
 * under 0.08 side-on.
 *
 * Unlike [CameraAlignmentChecker], which needs both shoulders clearly visible
 * (a frontal calibration pose), the shoulders only need to be present: side-on
 * the far one is hidden, but MediaPipe still places it next to the near one.
 */
object CameraViewClassifier {

    /** Shoulder width ratio above which the athlete is turned more than ~30° from side-on */
    const val FRONT_MIN_RATIO = 0.12f

    /** Fewer samples than this can't be judged */
    const val MIN_SAMPLES = 5

    /** Shoulder width in image-height units over the nose-to-ankle height, or null if not measurable. */
    fun shoulderWidthRatio(pose: PoseResult): Float? {
        val landmarks = pose.landmarks
        val leftShoulder = landmarks.getOrNull(PoseResult.LEFT_SHOULDER) ?: return null
        val rightShoulder = landmarks.getOrNull(PoseResult.RIGHT_SHOULDER) ?: return null
        fun visible(index: Int) =
            landmarks.getOrNull(index)?.takeIf { it.visibility >= PoseKeypoint.VISIBILITY_THRESHOLD }
        val nose = visible(PoseResult.NOSE) ?: return null
        val ankles = listOfNotNull(visible(PoseResult.LEFT_ANKLE), visible(PoseResult.RIGHT_ANKLE))
        if (ankles.isEmpty()) return null
        val height = ankles.map { it.y }.average().toFloat() - nose.y
        if (height <= 0f) return null
        return abs(rightShoulder.x - leftShoulder.x) * pose.aspectScaleX / height
    }

    /** The view over a stretch of frames, from the median ratio so turning briefly doesn't count. */
    fun classify(ratios: List<Float>): CameraView {
        if (ratios.size < MIN_SAMPLES) return CameraView.UNKNOWN
        val sorted = ratios.sorted()
        val median = sorted[sorted.size / 2]
        return if (median > FRONT_MIN_RATIO) CameraView.FRONT else CameraView.SIDE
    }
}
