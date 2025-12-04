package com.lifthelper.vision.calculator

import android.util.Log
import com.lifthelper.domain.model.AnthropometricData
import com.lifthelper.domain.model.BodySegment
import com.lifthelper.util.Constants.TAG_MOVEMENT
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
class BiomechanicsCalculator @Inject constructor() {

    companion object {
        private const val GRAVITY = 9.81f // m/s²
    }

    /**
     * Calculate mechanical power output during a lift
     *
     * Power = Force × Velocity = (mass × g) × velocity
     *
     * @param weight Barbell weight in kg
     * @param velocity Barbell velocity in m/s
     * @param bodyWeight User body weight in kg (optional, for bodyweight exercises)
     * @return Power in Watts
     */
    fun calculatePower(
        weight: Float,
        velocity: Float,
        bodyWeight: Float? = null
    ): Float {
        val totalMass = weight + (bodyWeight ?: 0f)
        val force = totalMass * GRAVITY
        val power = force * kotlin.math.abs(velocity)

        Log.d(TAG_MOVEMENT, "Power: ${power.toInt()}W (${totalMass}kg × ${velocity}m/s)")
        return power
    }

    /**
     * Calculate work done during a lift
     *
     * Work = Force × Distance = (mass × g) × vertical_displacement
     *
     * @param weight Barbell weight in kg
     * @param displacement Vertical displacement in meters
     * @param bodyWeight User body weight in kg (optional)
     * @return Work in Joules
     */
    fun calculateWork(
        weight: Float,
        displacement: Float,
        bodyWeight: Float? = null
    ): Float {
        val totalMass = weight + (bodyWeight ?: 0f)
        val force = totalMass * GRAVITY
        val work = force * kotlin.math.abs(displacement)

        Log.d(TAG_MOVEMENT, "Work: ${work.toInt()}J (${totalMass}kg × ${displacement}m)")
        return work
    }

    /**
     * Calculate average power over a rep
     *
     * Average Power = Work / Time
     *
     * @param work Work done in Joules
     * @param duration Time duration in seconds
     * @return Average power in Watts
     */
    fun calculateAveragePower(work: Float, duration: Float): Float {
        if (duration <= 0) return 0f
        return work / duration
    }

    /**
     * Calculate peak power during concentric phase
     *
     * Uses velocity and displacement data to find maximum power output
     */
    fun calculatePeakPower(
        weight: Float,
        velocityHistory: List<Float>,
        bodyWeight: Float? = null
    ): Float {
        if (velocityHistory.isEmpty()) return 0f

        val peakVelocity = velocityHistory.maxOrNull() ?: 0f
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
     * @param weight Barbell weight in kg
     * @param velocity Barbell velocity in m/s
     * @return Kinetic energy in Joules
     */
    fun calculateKineticEnergy(weight: Float, velocity: Float): Float {
        return 0.5f * weight * velocity.pow(2)
    }

    /**
     * Calculate potential energy relative to starting position
     *
     * PE = mass × g × height
     *
     * @param weight Barbell weight in kg
     * @param height Height above starting position in meters
     * @return Potential energy in Joules
     */
    fun calculatePotentialEnergy(weight: Float, height: Float): Float {
        return weight * GRAVITY * height
    }

    /**
     * Calculate total mechanical energy (KE + PE)
     */
    fun calculateTotalEnergy(weight: Float, velocity: Float, height: Float): Float {
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

        Log.d(TAG_MOVEMENT, "Hip torque: ${torque.toInt()}Nm " +
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

        Log.d(TAG_MOVEMENT, "Knee torque: ${torque.toInt()}Nm at ${kneeAngle.toInt()}°")

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

        Log.d(TAG_MOVEMENT, "Estimated COM height: ${comHeight}m")

        return comHeight
    }

    /**
     * Calculate velocity at center of mass (for jump height estimation)
     *
     * v_takeoff = sqrt(2 * g * jump_height)
     *
     * @param jumpHeight Jump height in meters
     * @return Takeoff velocity in m/s
     */
    fun calculateTakeoffVelocity(jumpHeight: Float): Float {
        return kotlin.math.sqrt(2 * GRAVITY * kotlin.math.abs(jumpHeight))
    }

    /**
     * Calculate jump height from takeoff velocity
     *
     * jump_height = v² / (2 * g)
     *
     * @param takeoffVelocity Vertical velocity at takeoff in m/s
     * @return Jump height in meters
     */
    fun calculateJumpHeight(takeoffVelocity: Float): Float {
        return (takeoffVelocity.pow(2)) / (2 * GRAVITY)
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
