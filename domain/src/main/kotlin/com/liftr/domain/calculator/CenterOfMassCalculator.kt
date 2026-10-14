package com.liftr.domain.calculator

import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult

/**
 * Whole-body center of mass from pose landmarks, using the segment model of
 * Winter (2009): each segment's share of body mass and the position of its own
 * center of mass along it, as a fraction of its length from the proximal end.
 *
 * The result is a mass-weighted average of landmark positions, so it is in the
 * same normalized image coordinates as the landmarks (x by width, y by height)
 * and needs neither body weight nor aspect-ratio correction.
 */
object CenterOfMassCalculator {

    /** One body segment: mass fraction and COM position from proximal to distal. */
    private class Segment(val massFraction: Float, val comRatio: Float)

    private val HEAD = Segment(0.081f, 0f)
    private val TRUNK = Segment(0.497f, 0.50f)
    private val UPPER_ARM = Segment(0.028f, 0.436f)
    private val FOREARM = Segment(0.016f, 0.430f)
    private val HAND = Segment(0.006f, 0.506f)
    private val THIGH = Segment(0.100f, 0.433f)
    private val SHANK = Segment(0.0465f, 0.433f)
    private val FOOT = Segment(0.0145f, 0.50f)

    /**
     * Center of mass in normalized image coordinates, or null when a core joint
     * (shoulders, hips, knees, ankles) is not visible.
     *
     * Hands and feet fall back to the wrist and ankle when their landmarks are
     * not visible; a whole arm falls back to the shoulder when the elbow is not.
     *
     * @param extraMassFraction Load carried at the shoulder midpoint (e.g. a
     *   barbell on the back), as a fraction of body mass.
     */
    fun centerOfMass(pose: PoseResult, extraMassFraction: Float = 0f): Pair<Float, Float>? {
        val lm = pose.landmarks
        fun visible(index: Int): PoseKeypoint? =
            lm.getOrNull(index)?.takeIf { it.visibility >= PoseKeypoint.VISIBILITY_THRESHOLD }
        fun mid(a: PoseKeypoint, b: PoseKeypoint) = Point((a.x + b.x) / 2f, (a.y + b.y) / 2f)
        fun point(k: PoseKeypoint) = Point(k.x, k.y)

        val leftShoulder = visible(PoseResult.LEFT_SHOULDER) ?: return null
        val rightShoulder = visible(PoseResult.RIGHT_SHOULDER) ?: return null
        val leftHip = visible(PoseResult.LEFT_HIP) ?: return null
        val rightHip = visible(PoseResult.RIGHT_HIP) ?: return null
        val leftKnee = visible(PoseResult.LEFT_KNEE) ?: return null
        val rightKnee = visible(PoseResult.RIGHT_KNEE) ?: return null
        val leftAnkle = visible(PoseResult.LEFT_ANKLE) ?: return null
        val rightAnkle = visible(PoseResult.RIGHT_ANKLE) ?: return null

        val shoulders = mid(leftShoulder, rightShoulder)
        val acc = Accumulator()

        // Head: ears straddle its center; the nose is a forward-biased fallback.
        val leftEar = visible(PoseResult.LEFT_EAR)
        val rightEar = visible(PoseResult.RIGHT_EAR)
        val head = when {
            leftEar != null && rightEar != null -> mid(leftEar, rightEar)
            else -> (leftEar ?: rightEar ?: visible(PoseResult.NOSE))?.let(::point)
        } ?: shoulders
        acc.add(head, HEAD.massFraction)

        acc.add(TRUNK, shoulders, mid(leftHip, rightHip))

        for (side in Side.entries) {
            val shoulder = if (side == Side.LEFT) leftShoulder else rightShoulder
            addArm(acc, point(shoulder), side, ::visible)

            val hip = point(if (side == Side.LEFT) leftHip else rightHip)
            val knee = point(if (side == Side.LEFT) leftKnee else rightKnee)
            val ankle = point(if (side == Side.LEFT) leftAnkle else rightAnkle)
            acc.add(THIGH, hip, knee)
            acc.add(SHANK, knee, ankle)
            val heel = visible(side.heel)
            val toe = visible(side.footIndex)
            if (heel != null && toe != null) {
                acc.add(FOOT, point(heel), point(toe))
            } else {
                acc.add(ankle, FOOT.massFraction)
            }
        }

        if (extraMassFraction > 0f) acc.add(shoulders, extraMassFraction)
        return acc.result()
    }

    /**
     * Load as a fraction of body mass, for [centerOfMass]'s extraMassFraction;
     * 0 when body weight is unknown, so the body alone is tracked.
     */
    fun loadFraction(loadKg: Float, bodyWeightKg: Float?): Float =
        if (bodyWeightKg != null && bodyWeightKg > 0f && loadKg > 0f) loadKg / bodyWeightKg else 0f

    private fun addArm(
        acc: Accumulator,
        shoulder: Point,
        side: Side,
        visible: (Int) -> PoseKeypoint?
    ) {
        val armMass = UPPER_ARM.massFraction + FOREARM.massFraction + HAND.massFraction
        val elbow = visible(side.elbow)?.let { Point(it.x, it.y) }
            ?: return acc.add(shoulder, armMass)
        acc.add(UPPER_ARM, shoulder, elbow)

        val wrist = visible(side.wrist)?.let { Point(it.x, it.y) }
            ?: return acc.add(elbow, FOREARM.massFraction + HAND.massFraction)
        acc.add(FOREARM, elbow, wrist)

        val index = visible(side.index)
        val pinky = visible(side.pinky)
        if (index != null && pinky != null) {
            acc.add(HAND, wrist, Point((index.x + pinky.x) / 2f, (index.y + pinky.y) / 2f))
        } else {
            acc.add(wrist, HAND.massFraction)
        }
    }

    private data class Point(val x: Float, val y: Float)

    private class Accumulator {
        private var x = 0f
        private var y = 0f
        private var mass = 0f

        fun add(p: Point, m: Float) {
            x += p.x * m
            y += p.y * m
            mass += m
        }

        fun add(segment: Segment, proximal: Point, distal: Point) = add(
            Point(
                proximal.x + segment.comRatio * (distal.x - proximal.x),
                proximal.y + segment.comRatio * (distal.y - proximal.y)
            ),
            segment.massFraction
        )

        fun result() = Pair(x / mass, y / mass)
    }

    private enum class Side(
        val elbow: Int, val wrist: Int, val index: Int, val pinky: Int, val heel: Int, val footIndex: Int
    ) {
        LEFT(
            PoseResult.LEFT_ELBOW, PoseResult.LEFT_WRIST, PoseResult.LEFT_INDEX,
            PoseResult.LEFT_PINKY, PoseResult.LEFT_HEEL, PoseResult.LEFT_FOOT_INDEX
        ),
        RIGHT(
            PoseResult.RIGHT_ELBOW, PoseResult.RIGHT_WRIST, PoseResult.RIGHT_INDEX,
            PoseResult.RIGHT_PINKY, PoseResult.RIGHT_HEEL, PoseResult.RIGHT_FOOT_INDEX
        )
    }
}
