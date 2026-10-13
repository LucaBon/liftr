package com.liftr.vision.calculator

import com.liftr.domain.logging.Logger
import com.liftr.domain.model.AnthropometricData
import com.liftr.domain.model.BodySegment
import com.liftr.domain.value.Joules
import com.liftr.domain.value.Kilograms
import com.liftr.domain.value.Meters
import com.liftr.domain.value.MetersPerSecond
import com.liftr.domain.value.Watts
import javax.inject.Inject
import kotlin.math.pow

/**
 * Calculates biomechanical metrics using anthropometric data
 *
 * Provides physics-based analysis of exercises including:
 * - Power output (Watts)
 * - Energy expenditure (Joules)
 * - Joint forces and torques (Newtons, Newton-meters)
 * - Work done (Joules)
 * - Momentum changes
 */
class BiomechanicsCalculator @Inject constructor(
    private val logger: Logger
) {

    companion object {
        private const val TAG = "BiomechanicsCalc"
        private const val GRAVITY = 9.81f // m/s²
    }

    /**
     * Calculate mechanical power output during a lift
     *
     * Power = Force × Velocity = (mass × g) × velocity
     *
     * @param weight Barbell weight
     * @param velocity Barbell velocity
     * @param bodyWeight User body weight (optional, for bodyweight exercises)
     * @return Power in Watts
     */
    fun calculatePower(
        weight: Kilograms,
        velocity: MetersPerSecond,
        bodyWeight: Kilograms? = null
    ): Watts {
        val totalMass = weight.value + (bodyWeight?.value ?: 0f)
        val force = totalMass * GRAVITY
        val power = force * velocity.abs().value

        logger.d(TAG, "Power: ${power.toInt()}W (${totalMass}kg × ${velocity.value}m/s)")
        return Watts(power)
    }

    /**
     * Calculate work done during a lift
     *
     * Work = Force × Distance = (mass × g) × vertical_displacement
     *
     * @param weight Barbell weight
     * @param displacement Vertical displacement
     * @param bodyWeight User body weight (optional)
     * @return Work in Joules
     */
    fun calculateWork(
        weight: Kilograms,
        displacement: Meters,
        bodyWeight: Kilograms? = null
    ): Joules {
        val totalMass = weight.value + (bodyWeight?.value ?: 0f)
        val force = totalMass * GRAVITY
        val work = force * kotlin.math.abs(displacement.value)

        logger.d(TAG, "Work: ${work.toInt()}J (${totalMass}kg × ${displacement.value}m)")
        return Joules(work)
    }

    /**
     * Calculate average power over a rep
     *
     * Average Power = Work / Time
     *
     * @param work Work done
     * @param duration Time duration in seconds
     * @return Average power
     */
    fun calculateAveragePower(work: Joules, duration: Float): Watts {
        if (duration <= 0) return Watts(0f)
        return Watts(work.value / duration)
    }

    /**
     * Calculate peak power during concentric phase
     *
     * Uses velocity and displacement data to find maximum power output
     */
    fun calculatePeakPower(
        weight: Kilograms,
        velocityHistory: List<MetersPerSecond>,
        bodyWeight: Kilograms? = null
    ): Watts {
        if (velocityHistory.isEmpty()) return Watts(0f)

        val peakVelocity = velocityHistory.maxByOrNull { it.value } ?: MetersPerSecond(0f)
        return calculatePower(weight, peakVelocity, bodyWeight)
    }

    /**
     * Estimate bar path efficiency
     *
     * Efficiency = Vertical displacement / Total path length
     * Perfect vertical lift = 1.0, curved path < 1.0
     *
     * @param verticalDisplacement Net vertical movement in meters
     * @param totalPathLength Total 3D path length in meters
     * @return Efficiency ratio (0.0 to 1.0)
     */
    fun calculateBarPathEfficiency(
        verticalDisplacement: Float,
        totalPathLength: Float
    ): Float {
        if (totalPathLength <= 0) return 0f
        return (verticalDisplacement / totalPathLength).coerceIn(0f, 1.0f)
    }

    /**
     * Calculate kinetic energy of the barbell
     *
     * KE = 0.5 × mass × velocity²
     *
     * @param weight Barbell weight
     * @param velocity Barbell velocity
     * @return Kinetic energy
     */
    fun calculateKineticEnergy(weight: Kilograms, velocity: MetersPerSecond): Joules {
        return Joules(0.5f * weight.value * velocity.value.pow(2))
    }

    /**
     * Calculate potential energy relative to starting position
     *
     * PE = mass × g × height
     *
     * @param weight Barbell weight
     * @param height Height above starting position
     * @return Potential energy
     */
    fun calculatePotentialEnergy(weight: Kilograms, height: Meters): Joules {
        return Joules(weight.value * GRAVITY * height.value)
    }

    /**
     * Calculate total mechanical energy (KE + PE)
     */
    fun calculateTotalEnergy(weight: Kilograms, velocity: MetersPerSecond, height: Meters): Joules {
        return calculateKineticEnergy(weight, velocity) + calculatePotentialEnergy(weight, height)
    }

    /**
     * Estimate hip joint torque during squat
     *
     * Simplified model: Torque = Force × lever_arm
     * where lever_arm approximates horizontal distance from hip to bar
     *
     * Requires anthropometric data for accurate calculations
     *
     * @param weight Total weight (barbell + body segment mass) in kg
     * @param hipToBarDistance Horizontal distance from hip joint to bar in meters
     * @return Hip torque in Newton-meters
     */
    fun calculateHipTorque(
        weight: Float,
        hipToBarDistance: Float,
        anthropometricData: AnthropometricData?
    ): Float {
        // Include body segment masses if available
        val trunkMass = anthropometricData?.getSegmentMass(BodySegment.TRUNK) ?: 0f
        val headMass = anthropometricData?.getSegmentMass(BodySegment.HEAD) ?: 0f

        val totalMass = weight + trunkMass + headMass
        val force = totalMass * GRAVITY
        val torque = force * hipToBarDistance

        logger.d(TAG, "Hip torque: ${torque.toInt()}Nm " +
                "(${totalMass.toInt()}kg × ${hipToBarDistance}m)")

        return torque
    }

    /**
     * Estimate knee joint torque during squat
     *
     * Uses anthropometric data for segment masses and lengths
     *
     * @param weight Barbell weight in kg
     * @param kneeAngle Knee flexion angle in degrees (180 = extended, 90 = parallel)
     * @param anthropometricData User body measurements
     * @return Knee torque in Newton-meters
     */
    fun calculateKneeTorque(
        weight: Float,
        kneeAngle: Float,
        anthropometricData: AnthropometricData?
    ): Float {
        if (anthropometricData == null) return 0f

        // Estimate moment arm based on knee angle
        // At 90° (parallel squat), moment arm is maximum
        val shinLength = anthropometricData.getShinLength()
        val leverArm = shinLength * kotlin.math.sin(Math.toRadians(kneeAngle.toDouble())).toFloat()

        // Include shank segment mass
        val shankMass = anthropometricData.getSegmentMass(BodySegment.SHANK) ?: 0f
        val totalMass = weight * 0.3f + shankMass // Approximate distribution

        val force = totalMass * GRAVITY
        val torque = force * kotlin.math.abs(leverArm)

        logger.d(TAG, "Knee torque: ${torque.toInt()}Nm at ${kneeAngle.toInt()}°")

        return torque
    }

    /**
     * Calculate rate of force development (RFD)
     *
     * RFD = ΔForce / ΔTime
     * Important metric for explosive strength
     *
     * @param forceHistory List of force values in Newtons
     * @param timeInterval Time between samples in seconds
     * @return RFD in Newtons/second
     */
    fun calculateRFD(forceHistory: List<Float>, timeInterval: Float): Float {
        if (forceHistory.size < 2 || timeInterval <= 0) return 0f

        val deltaForce = forceHistory.last() - forceHistory.first()
        val deltaTime = timeInterval * (forceHistory.size - 1)

        return if (deltaTime > 0) deltaForce / deltaTime else 0f
    }

    /**
     * Estimate total body center of mass height
     *
     * Weighted average of segment COM positions using anthropometric data
     *
     * @param anthropometricData User body measurements
     * @param bodyPosition Current body configuration (squat depth, etc.)
     * @return Approximate COM height in meters
     */
    fun estimateCenterOfMass(
        anthropometricData: AnthropometricData,
        headY: Float,
        hipsY: Float,
        kneesY: Float,
        anklesY: Float
    ): Float {
        // Weighted sum of segment centers
        val trunkMass = anthropometricData.getSegmentMass(BodySegment.TRUNK) ?: 0f
        val headMass = anthropometricData.getSegmentMass(BodySegment.HEAD) ?: 0f
        val thighMass = anthropometricData.getSegmentMass(BodySegment.THIGH) ?: 0f
        val shankMass = anthropometricData.getSegmentMass(BodySegment.SHANK) ?: 0f

        val totalMass = trunkMass + headMass + 2 * (thighMass + shankMass)

        if (totalMass <= 0) return hipsY // Fallback to hip height

        // Segment COM positions
        val headCOM = headY + anthropometricData.getHeadHeight() *
                anthropometricData.getSegmentCOMRatio(BodySegment.HEAD)
        val trunkCOM = (headY + hipsY) / 2f // Approximate trunk center
        val thighCOM = (hipsY + kneesY) / 2f
        val shankCOM = (kneesY + anklesY) / 2f

        // Weighted average
        val comHeight = (headCOM * headMass +
                        trunkCOM * trunkMass +
                        thighCOM * 2 * thighMass +
                        shankCOM * 2 * shankMass) / totalMass

        logger.d(TAG, "Estimated COM height: ${comHeight}m")

        return comHeight
    }

    /**
     * Calculate relative strength (strength per kg body weight)
     *
     * Useful for comparing athletes of different sizes
     *
     * @param weight Lifted weight in kg
     * @param bodyWeight Athlete body weight in kg
     * @return Relative strength (unitless ratio)
     */
    fun calculateRelativeStrength(weight: Float, bodyWeight: Float): Float {
        if (bodyWeight <= 0) return 0f
        return weight / bodyWeight
    }

    /**
     * Estimate metabolic cost using anthropometric data
     *
     * Simplified estimation based on work done and body mass
     *
     * @param work Mechanical work in Joules
     * @param efficiency Mechanical efficiency (typically 0.20-0.25 for resistance training)
     * @return Estimated energy expenditure in kcal
     */
    fun estimateEnergyExpenditure(work: Float, efficiency: Float = 0.23f): Float {
        // Convert Joules to kcal (1 kcal = 4184 J)
        // Account for mechanical efficiency
        val totalEnergy = work / efficiency
        return totalEnergy / 4184f
    }
}
