package com.liftr.domain.calculator

import com.liftr.domain.model.JumpRepRecord
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One point of a jump force-velocity profile: the best jump at one load.
 *
 * @property loadKg External load, rounded to [ForceVelocityProfile.LOAD_STEP_KG]
 * @property relativeForce Mean push-off force per kilogram of **body** mass, in N/kg
 * @property meanVelocity Mean push-off velocity, in m/s
 * @property pushOffM Push-off distance of that jump, in meters
 */
data class ForceVelocityPoint(
    val loadKg: Float,
    val relativeForce: Float,
    val meanVelocity: Float,
    val pushOffM: Float
)

/** Which end of the force-velocity line holds back the jump. */
enum class ForceVelocityDeficit {
    /** The profile is flatter than optimal (|SFV| < |SFVopt|): more force would jump higher. */
    FORCE,
    /** The profile is steeper than optimal: more velocity would jump higher. */
    VELOCITY
}

/** Why no profile could be fitted. */
enum class ForceVelocityRejection {
    /** Fewer than [ForceVelocityProfile.MIN_LOADS] distinct loads with push-off mechanics. */
    TOO_FEW_LOADS,
    /** Force does not fall as velocity rises (or every point has the same velocity). */
    NON_NEGATIVE_SLOPE,
    /** The line's force at zero velocity cannot even hold the body up (F0 ≤ g). */
    FORCE_BELOW_GRAVITY
}

/** Outcome of [ForceVelocityProfile.fit]: a profile, or the reason there is none. */
sealed interface ForceVelocityProfileResult {
    data class Fitted(val profile: ForceVelocityProfile) : ForceVelocityProfileResult
    data class Rejected(val reason: ForceVelocityRejection, val loadCount: Int) : ForceVelocityProfileResult

    val profileOrNull: ForceVelocityProfile? get() = (this as? Fitted)?.profile
}

/**
 * Jump force-velocity profile by the method of Samozino et al. (2012),
 * "Optimal force-velocity profile in ballistic movements — altius: citius or
 * fortius?", Med Sci Sports Exerc 44(2):313-322, as used for individualized
 * training by Jiménez-Reyes et al. (2017), "Effectiveness of an individualized
 * training based on force-velocity profiling during jumping", Front Physiol 7:677.
 *
 * Each load gives one point (mean push-off force per kg of body mass against
 * mean push-off velocity, from [JumpMechanics]); a least-squares line through
 * them extrapolates to F0 (force at zero velocity), V0 (velocity at zero force)
 * and Pmax = F0·V0/4, the apex of the parabolic power-velocity curve that a
 * linear F-V relation implies.
 *
 * The same Pmax can come with a steep (force-dominant) or flat
 * (velocity-dominant) line, and only one slope maximizes an unloaded jump:
 * SFVopt. The distance from it, FVimb, tells which quality to train; Samozino
 * showed it explains jump height beyond Pmax alone.
 *
 * @property points The best jump per load, by increasing load
 * @property f0 Force at zero velocity, N/kg of body mass (the line's intercept)
 * @property sfv Slope of the line, N·s/m/kg (negative)
 * @property v0 Velocity at zero force, m/s (−F0/SFV)
 * @property pmax Maximal power, W/kg (F0·V0/4)
 * @property rSquared Coefficient of determination of the fit
 * @property pushOffM Push-off distance the optimum is computed for: the median of the points'
 * @property optimalSfv Slope that would maximize an unloaded jump at the same Pmax and push-off
 * @property imbalancePercent FVimb = 100·|1 − SFV/SFVopt|
 * @property deficit Which quality falls short of the optimum
 * @property optimalHeightM Unloaded jump height the model predicts at SFVopt
 * @property profileHeightM Unloaded jump height the model predicts at the actual SFV
 */
data class ForceVelocityProfile(
    val points: List<ForceVelocityPoint>,
    val f0: Float,
    val sfv: Float,
    val v0: Float,
    val pmax: Float,
    val rSquared: Float,
    val pushOffM: Float,
    val optimalSfv: Float,
    val imbalancePercent: Float,
    val deficit: ForceVelocityDeficit,
    val optimalHeightM: Float,
    val profileHeightM: Float
) {
    /**
     * Too few loads or too poor a line to trust: two points always fit
     * perfectly, and video-measured points scatter more than force-plate ones.
     */
    val lowConfidence: Boolean get() = points.size < CONFIDENT_LOADS || rSquared < MIN_R_SQUARED

    /** Within [BALANCED_IMBALANCE_PERCENT] of the optimum, the band Jiménez-Reyes (2017) left untargeted. */
    val isWellBalanced: Boolean get() = imbalancePercent <= BALANCED_IMBALANCE_PERCENT

    companion object {
        private const val G = 9.81

        /** Loads are grouped to the nearest half kilogram, the smallest plate step. */
        const val LOAD_STEP_KG = 0.5f

        /** A line needs two loads. */
        const val MIN_LOADS = 2

        /** Fewer loads than this gives a low-confidence profile. */
        const val CONFIDENT_LOADS = 3

        /** Fits below this R² are low confidence. */
        const val MIN_R_SQUARED = 0.95f

        /** FVimb up to this counts as well balanced (Jiménez-Reyes et al. 2017: 90–110 % of SFVopt). */
        const val BALANCED_IMBALANCE_PERCENT = 10f

        /** Steepest slope scanned for the optimum, N·s/m/kg; real SFVopt lie around −10 to −20. */
        private const val SCAN_MAX_STEEPNESS = 200.0
        private const val SCAN_STEP = 0.5
        private const val GOLDEN_TOLERANCE = 1e-6

        /**
         * Profile of the [jumps] that carry push-off mechanics, one point per
         * load: the jump with the highest mean velocity (so the highest jump)
         * at that load. Bodyweight jumps (load 0) are a point like any other.
         */
        fun fit(jumps: List<JumpRepRecord>): ForceVelocityProfileResult {
            val points = jumps.mapNotNull(::pointOf)
                .groupBy { it.loadKg }
                .map { (_, atLoad) -> atLoad.maxBy { it.meanVelocity } }
                .sortedBy { it.loadKg }
            if (points.size < MIN_LOADS) {
                return ForceVelocityProfileResult.Rejected(ForceVelocityRejection.TOO_FEW_LOADS, points.size)
            }

            val n = points.size
            val meanV = points.sumOf { it.meanVelocity.toDouble() } / n
            val meanF = points.sumOf { it.relativeForce.toDouble() } / n
            val sxx = points.sumOf { (it.meanVelocity - meanV).let { d -> d * d } }
            val sxy = points.sumOf { (it.meanVelocity - meanV) * (it.relativeForce - meanF) }
            val syy = points.sumOf { (it.relativeForce - meanF).let { d -> d * d } }
            if (sxx <= 0.0) return ForceVelocityProfileResult.Rejected(ForceVelocityRejection.NON_NEGATIVE_SLOPE, n)
            val sfv = sxy / sxx
            val f0 = meanF - sfv * meanV
            if (sfv >= 0.0) return ForceVelocityProfileResult.Rejected(ForceVelocityRejection.NON_NEGATIVE_SLOPE, n)
            if (f0 <= G) return ForceVelocityProfileResult.Rejected(ForceVelocityRejection.FORCE_BELOW_GRAVITY, n)

            val residual = points.sumOf { (it.relativeForce - (f0 + sfv * it.meanVelocity)).let { d -> d * d } }
            val rSquared = if (syy > 0.0) 1.0 - residual / syy else 1.0
            val v0 = -f0 / sfv
            val pmax = f0 * v0 / 4.0
            val pushOff = median(points.map { it.pushOffM.toDouble() })
            val optimalSfv = optimalSlope(pmax, pushOff)
            val imbalance = 100.0 * abs(1.0 - sfv / optimalSfv)

            return ForceVelocityProfileResult.Fitted(
                ForceVelocityProfile(
                    points = points,
                    f0 = f0.toFloat(),
                    sfv = sfv.toFloat(),
                    v0 = v0.toFloat(),
                    pmax = pmax.toFloat(),
                    rSquared = rSquared.toFloat(),
                    pushOffM = pushOff.toFloat(),
                    optimalSfv = optimalSfv.toFloat(),
                    imbalancePercent = imbalance.toFloat(),
                    deficit = deficitOf(sfv, optimalSfv),
                    optimalHeightM = jumpHeight(optimalSfv, pmax, pushOff).toFloat(),
                    profileHeightM = jumpHeight(sfv, pmax, pushOff).toFloat()
                )
            )
        }

        /** Force deficit when the line is flatter than optimal, velocity deficit otherwise. */
        fun deficitOf(sfv: Double, optimalSfv: Double): ForceVelocityDeficit =
            if (abs(sfv) < abs(optimalSfv)) ForceVelocityDeficit.FORCE else ForceVelocityDeficit.VELOCITY

        /**
         * Unloaded jump height, in meters, of an athlete whose F-V line has
         * slope [sfv] and apex [pmax] (W/kg), pushing off over [pushOffM].
         *
         * Per kg of body mass, an unloaded jump taking off at v has
         * F̄ = v²/(2·hPO) + g (Samozino 2008 with h = v²/2g) and v̄ = v/2, and
         * the point lies on F̄ = F0 + SFV·v̄. That gives
         * v²/(2hPO) − (SFV/2)·v + (g − F0) = 0, whose positive root is
         * v = hPO·[SFV/2 + √(SFV²/4 − 2(g − F0)/hPO)]. Pmax = F0·V0/4 =
         * −F0²/(4·SFV) fixes F0 = 2·√(−Pmax·SFV), and h = v²/(2g).
         *
         * −∞ where no jump exists: a non-negative slope, or F0 ≤ g (the push
         * cannot lift the body, and the root would not be a takeoff).
         */
        fun jumpHeight(sfv: Double, pmax: Double, pushOffM: Double): Double {
            if (sfv >= 0.0 || pmax <= 0.0 || pushOffM <= 0.0) return Double.NEGATIVE_INFINITY
            val f0 = 2.0 * sqrt(-pmax * sfv)
            if (f0 <= G) return Double.NEGATIVE_INFINITY
            val discriminant = sfv * sfv / 4.0 - 2.0 * (G - f0) / pushOffM
            val takeoff = pushOffM * (sfv / 2.0 + sqrt(discriminant))
            if (takeoff <= 0.0) return Double.NEGATIVE_INFINITY
            return takeoff * takeoff / (2.0 * G)
        }

        /**
         * SFVopt: the slope that maximizes [jumpHeight] at fixed [pmax] and
         * [pushOffM], found numerically rather than by Samozino's closed form
         * so it follows exactly the model above. A scan over slopes brackets
         * the maximum (too shallow and F0 ≤ g, too steep and V0 → 0, so it is
         * interior), then golden-section search refines it.
         */
        fun optimalSlope(pmax: Double, pushOffM: Double): Double {
            var bestIndex = 1
            var bestHeight = Double.NEGATIVE_INFINITY
            val steps = (SCAN_MAX_STEEPNESS / SCAN_STEP).toInt()
            for (i in 1..steps) {
                val h = jumpHeight(-i * SCAN_STEP, pmax, pushOffM)
                if (h > bestHeight) {
                    bestHeight = h
                    bestIndex = i
                }
            }
            // Bracket in steepness s = −SFV, so a < b both read as "less to more steep".
            var a = (bestIndex - 1) * SCAN_STEP
            var b = (bestIndex + 1) * SCAN_STEP
            val ratio = (sqrt(5.0) - 1.0) / 2.0
            var c = b - ratio * (b - a)
            var d = a + ratio * (b - a)
            while (b - a > GOLDEN_TOLERANCE) {
                if (jumpHeight(-c, pmax, pushOffM) > jumpHeight(-d, pmax, pushOffM)) b = d else a = c
                c = b - ratio * (b - a)
                d = a + ratio * (b - a)
            }
            return -(a + b) / 2.0
        }

        /**
         * The [JumpRepRecord] as a profile point, force per kg of body mass
         * (system mass less the load), or null without push-off mechanics.
         */
        private fun pointOf(record: JumpRepRecord): ForceVelocityPoint? {
            val jump = record.jump
            val force = jump.meanForceN ?: return null
            val velocity = jump.meanVelocity ?: return null
            val pushOffCm = jump.pushOffCm ?: return null
            val systemMass = jump.systemMassKg ?: return null
            val bodyMass = systemMass - record.loadKg
            if (bodyMass <= 0f || velocity <= 0f) return null
            return ForceVelocityPoint(
                loadKg = roundLoad(record.loadKg),
                relativeForce = force / bodyMass,
                meanVelocity = velocity,
                pushOffM = pushOffCm / 100f
            )
        }

        /** [loadKg] to the nearest [LOAD_STEP_KG]. */
        fun roundLoad(loadKg: Float): Float = (loadKg / LOAD_STEP_KG).roundToInt() * LOAD_STEP_KG

        private fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }
}
