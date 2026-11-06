package com.liftr.domain.exercise

import com.liftr.domain.model.BarSample
import com.liftr.domain.model.PoseResult
import com.liftr.domain.model.RepBarData
import com.liftr.domain.vision.EndcapDetection

/**
 * The bar series of a video: positions, velocity and acceleration from the
 * tracked endcap only. Gaps the tracker doesn't bridge stay gaps; nothing is
 * filled in from the pose, and velocity never spans a gap.
 */
object BarSeries {

    /** Share of a rep's concentric frames the bar must cover for its bar velocities. */
    const val MIN_CONCENTRIC_COVERAGE = 0.8f

    /**
     * Bar samples 1:1 with [poses], null where [tracker] has no endcap.
     *
     * @param toMeters Vertical position in meters from a normalized y, as for the pose tracking point
     */
    fun build(
        poses: List<PoseResult>,
        endcapsByTimestamp: Map<Long, List<EndcapDetection>>,
        tracker: EndcapTracker,
        toMeters: (y: Float, pose: PoseResult) -> Float
    ): List<BarSample?> {
        val samples: MutableList<BarSample?> = poses.map { pose ->
            tracker.update(pose.timestamp, endcapsByTimestamp[pose.timestamp].orEmpty(), pose)?.let {
                BarSample(pose.timestamp, it.x, it.y, toMeters(it.y, pose), held = it.held)
            }
        }.toMutableList()

        // Velocity and acceleration within each run of consecutive samples.
        var start = 0
        while (start < samples.size) {
            if (samples[start] == null) { start++; continue }
            var end = start
            while (end + 1 < samples.size && samples[end + 1] != null) end++
            fillRun(samples, start, end)
            start = end + 1
        }
        return samples
    }

    private fun fillRun(samples: MutableList<BarSample?>, start: Int, end: Int) {
        if (end <= start) return
        val run = (start..end).map { samples[it]!! }
        val velocities = BidirectionalVelocityCalculator.calculateVelocities(
            run.map { BidirectionalVelocityCalculator.PositionSample(it.positionMeters, it.t) }
        ).map { it.instantaneous.value }
        run.forEachIndexed { k, sample ->
            val acceleration = if (k in 1 until run.lastIndex) {
                val dt = (run[k + 1].t - run[k - 1].t) / 1000f
                if (dt > 0f) (velocities[k + 1] - velocities[k - 1]) / dt else null
            } else null
            samples[start + k] = sample.copy(velocity = velocities[k], acceleration = acceleration)
        }
    }

    /**
     * Collects one rep's bar measurements frame by frame, alongside the pose
     * rep segmentation: [reset] where a rep starts, [onFrame] every frame,
     * [result] when it completes.
     */
    class RepAccumulator(private val minCoverage: Float = MIN_CONCENTRIC_COVERAGE) {
        private var concentricFrames = 0
        private var velocitySum = 0f
        private var velocityCount = 0
        private var velocityPeak = 0f
        private val samples = mutableListOf<BarSample>()

        fun reset() {
            concentricFrames = 0
            velocitySum = 0f
            velocityCount = 0
            velocityPeak = 0f
            samples.clear()
        }

        fun onFrame(sample: BarSample?, active: Boolean, concentric: Boolean) {
            if (active && sample != null) samples += sample
            if (!concentric) return
            concentricFrames++
            val v = sample?.velocity ?: return
            velocitySum += v
            velocityCount++
            velocityPeak = maxOf(velocityPeak, v)
        }

        fun result(): RepBarData {
            val coverage = if (concentricFrames > 0) velocityCount.toFloat() / concentricFrames else 0f
            val measured = concentricFrames > 0 && coverage >= minCoverage
            return RepBarData(
                meanConcentricVelocity = if (measured) velocitySum / velocityCount else null,
                peakConcentricVelocity = if (measured) velocityPeak else null,
                concentricCoverage = coverage,
                samples = samples.toList()
            )
        }
    }
}
