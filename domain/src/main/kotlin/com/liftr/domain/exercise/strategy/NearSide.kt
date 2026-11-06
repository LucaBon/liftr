package com.liftr.domain.exercise.strategy

import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult

/**
 * The given joints on the body side facing the camera, or null if neither side
 * has them all visible. Filmed side-on, the far side is hidden behind the body,
 * so requiring both sides would leave the checks unmeasured. The side whose
 * least visible joint is seen better wins.
 */
internal fun nearSideLandmarks(
    poseResult: PoseResult,
    left: List<Int>,
    right: List<Int>,
    minVisibility: Float
): List<PoseKeypoint>? {
    fun side(indices: List<Int>): List<PoseKeypoint>? =
        indices.map { poseResult.landmarks.getOrNull(it) ?: return null }
    val candidates = listOfNotNull(side(left), side(right))
        .filter { joints -> joints.all { it.visibility >= minVisibility } }
    return candidates.maxByOrNull { joints -> joints.minOf { it.visibility } }
}
