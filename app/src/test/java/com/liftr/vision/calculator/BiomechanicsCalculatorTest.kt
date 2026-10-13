package com.liftr.vision.calculator

import com.liftr.domain.logging.Logger
import com.liftr.domain.value.Joules
import com.liftr.domain.value.Kilograms
import com.liftr.domain.value.Meters
import com.liftr.domain.value.MetersPerSecond
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import kotlin.math.pow
import kotlin.math.sqrt

class BiomechanicsCalculatorTest {

    private lateinit var calculator: BiomechanicsCalculator
    private lateinit var logger: Logger

    companion object {
        private const val GRAVITY = 9.81f
        private const val DELTA = 0.1f  // Allow small delta for floating point calculations
    }

    @Before
    fun setup() {
        logger = mockk(relaxed = true)
        calculator = BiomechanicsCalculator(logger)
    }

    // Power Calculation Tests
    @Test
    fun `should calculate power correctly with weight only`() {
        // Given
        val weight = Kilograms(100f)
        val velocity = MetersPerSecond(1.0f)

        // When
        val power = calculator.calculatePower(weight, velocity)

        // Then
        val expectedPower = 100f * GRAVITY * 1.0f
        assertEquals(expectedPower, power.value, DELTA)
    }

    @Test
    fun `should calculate power correctly with weight and bodyweight`() {
        // Given
        val weight = Kilograms(100f)
        val velocity = MetersPerSecond(1.0f)
        val bodyWeight = Kilograms(75f)

        // When
        val power = calculator.calculatePower(weight, velocity, bodyWeight)

        // Then
        val expectedPower = (100f + 75f) * GRAVITY * 1.0f
        assertEquals(expectedPower, power.value, DELTA)
    }

    @Test
    fun `should use absolute velocity value for power calculation`() {
        // Given
        val weight = Kilograms(100f)
        val velocity = MetersPerSecond(-1.0f)  // Negative velocity

        // When
        val power = calculator.calculatePower(weight, velocity)

        // Then
        val expectedPower = 100f * GRAVITY * 1.0f  // Should use abs value
        assertEquals(expectedPower, power.value, DELTA)
    }

    @Test
    fun `should return zero power for zero velocity`() {
        // Given
        val weight = Kilograms(100f)
        val velocity = MetersPerSecond(0f)

        // When
        val power = calculator.calculatePower(weight, velocity)

        // Then
        assertEquals(0f, power.value, DELTA)
    }

    // Work Calculation Tests
    @Test
    fun `should calculate work correctly with weight only`() {
        // Given
        val weight = Kilograms(100f)
        val displacement = Meters(0.5f)

        // When
        val work = calculator.calculateWork(weight, displacement)

        // Then
        val expectedWork = 100f * GRAVITY * 0.5f
        assertEquals(expectedWork, work.value, DELTA)
    }

    @Test
    fun `should calculate work correctly with weight and bodyweight`() {
        // Given
        val weight = Kilograms(100f)
        val displacement = Meters(0.5f)
        val bodyWeight = Kilograms(75f)

        // When
        val work = calculator.calculateWork(weight, displacement, bodyWeight)

        // Then
        val expectedWork = (100f + 75f) * GRAVITY * 0.5f
        assertEquals(expectedWork, work.value, DELTA)
    }

    @Test
    fun `should calculate work for positive displacement`() {
        // Given
        val weight = Kilograms(100f)
        val displacement = Meters(0.5f)

        // When
        val work = calculator.calculateWork(weight, displacement)

        // Then
        val expectedWork = 100f * GRAVITY * 0.5f
        assertEquals(expectedWork, work.value, DELTA)
    }

    // Average Power Calculation Tests
    @Test
    fun `should calculate average power correctly`() {
        // Given
        val work = Joules(490.5f)
        val duration = 2.0f

        // When
        val avgPower = calculator.calculateAveragePower(work, duration)

        // Then
        assertEquals(245.25f, avgPower.value, DELTA)
    }

    @Test
    fun `should return zero average power for zero duration`() {
        // Given
        val work = Joules(490.5f)
        val duration = 0f

        // When
        val avgPower = calculator.calculateAveragePower(work, duration)

        // Then
        assertEquals(0f, avgPower.value, DELTA)
    }

    @Test
    fun `should return zero average power for negative duration`() {
        // Given
        val work = Joules(490.5f)
        val duration = -1.0f

        // When
        val avgPower = calculator.calculateAveragePower(work, duration)

        // Then
        assertEquals(0f, avgPower.value, DELTA)
    }

    // Peak Power Calculation Tests
    @Test
    fun `should calculate peak power from velocity history`() {
        // Given
        val weight = Kilograms(100f)
        val velocityHistory = listOf(
            MetersPerSecond(0.5f),
            MetersPerSecond(0.8f),
            MetersPerSecond(1.2f),  // Peak
            MetersPerSecond(1.0f)
        )

        // When
        val peakPower = calculator.calculatePeakPower(weight, velocityHistory)

        // Then
        val expectedPower = 100f * GRAVITY * 1.2f
        assertEquals(expectedPower, peakPower.value, DELTA)
    }

    @Test
    fun `should return zero peak power for empty velocity history`() {
        // Given
        val weight = Kilograms(100f)
        val velocityHistory = emptyList<MetersPerSecond>()

        // When
        val peakPower = calculator.calculatePeakPower(weight, velocityHistory)

        // Then
        assertEquals(0f, peakPower.value, DELTA)
    }

    // Bar Path Efficiency Tests
    @Test
    fun `should calculate perfect efficiency for vertical lift`() {
        // Given
        val verticalDisplacement = 0.5f
        val totalPathLength = 0.5f

        // When
        val efficiency = calculator.calculateBarPathEfficiency(verticalDisplacement, totalPathLength)

        // Then
        assertEquals(1.0f, efficiency, 0.001f)
    }

    @Test
    fun `should calculate reduced efficiency for curved path`() {
        // Given
        val verticalDisplacement = 0.5f
        val totalPathLength = 0.7f

        // When
        val efficiency = calculator.calculateBarPathEfficiency(verticalDisplacement, totalPathLength)

        // Then
        assertEquals(0.714f, efficiency, 0.01f)
    }

    @Test
    fun `should return zero efficiency for zero path length`() {
        // Given
        val verticalDisplacement = 0.5f
        val totalPathLength = 0f

        // When
        val efficiency = calculator.calculateBarPathEfficiency(verticalDisplacement, totalPathLength)

        // Then
        assertEquals(0f, efficiency, 0.001f)
    }

    @Test
    fun `should clamp efficiency to maximum 1_0`() {
        // Given - impossible scenario but test the clamping
        val verticalDisplacement = 0.6f
        val totalPathLength = 0.5f

        // When
        val efficiency = calculator.calculateBarPathEfficiency(verticalDisplacement, totalPathLength)

        // Then
        assertEquals(1.0f, efficiency, 0.001f)
    }

    // Kinetic Energy Tests
    @Test
    fun `should calculate kinetic energy correctly`() {
        // Given
        val weight = Kilograms(100f)
        val velocity = MetersPerSecond(2.0f)

        // When
        val ke = calculator.calculateKineticEnergy(weight, velocity)

        // Then
        val expected = 0.5f * 100f * 2.0f.pow(2)
        assertEquals(expected, ke.value, DELTA)
    }

    @Test
    fun `should return zero kinetic energy for zero velocity`() {
        // Given
        val weight = Kilograms(100f)
        val velocity = MetersPerSecond(0f)

        // When
        val ke = calculator.calculateKineticEnergy(weight, velocity)

        // Then
        assertEquals(0f, ke.value, DELTA)
    }

    // Potential Energy Tests
    @Test
    fun `should calculate potential energy correctly`() {
        // Given
        val weight = Kilograms(100f)
        val height = Meters(1.5f)

        // When
        val pe = calculator.calculatePotentialEnergy(weight, height)

        // Then
        val expected = 100f * GRAVITY * 1.5f
        assertEquals(expected, pe.value, DELTA)
    }

    @Test
    fun `should return zero potential energy at zero height`() {
        // Given
        val weight = Kilograms(100f)
        val height = Meters(0f)

        // When
        val pe = calculator.calculatePotentialEnergy(weight, height)

        // Then
        assertEquals(0f, pe.value, DELTA)
    }

    // Total Energy Tests
    @Test
    fun `should calculate total energy correctly`() {
        // Given
        val weight = Kilograms(100f)
        val velocity = MetersPerSecond(1.0f)
        val height = Meters(0.5f)

        // When
        val totalEnergy = calculator.calculateTotalEnergy(weight, velocity, height)

        // Then
        val ke = 0.5f * 100f * 1.0f.pow(2)
        val pe = 100f * GRAVITY * 0.5f
        val expected = ke + pe
        assertEquals(expected, totalEnergy.value, DELTA)
    }

    // Relative Strength Tests
    @Test
    fun `should calculate relative strength correctly`() {
        // Given
        val weight = 150f
        val bodyWeight = 75f

        // When
        val relativeStrength = calculator.calculateRelativeStrength(weight, bodyWeight)

        // Then
        assertEquals(2.0f, relativeStrength, 0.001f)
    }

    @Test
    fun `should return zero relative strength for zero bodyweight`() {
        // Given
        val weight = 150f
        val bodyWeight = 0f

        // When
        val relativeStrength = calculator.calculateRelativeStrength(weight, bodyWeight)

        // Then
        assertEquals(0f, relativeStrength, 0.001f)
    }

    @Test
    fun `should return zero relative strength for negative bodyweight`() {
        // Given
        val weight = 150f
        val bodyWeight = -75f

        // When
        val relativeStrength = calculator.calculateRelativeStrength(weight, bodyWeight)

        // Then
        assertEquals(0f, relativeStrength, 0.001f)
    }

    // Energy Expenditure Tests
    @Test
    fun `should estimate energy expenditure correctly`() {
        // Given
        val work = 1000f  // Joules
        val efficiency = 0.25f

        // When
        val energyExpenditure = calculator.estimateEnergyExpenditure(work, efficiency)

        // Then
        val expected = (work / efficiency) / 4184f
        assertEquals(expected, energyExpenditure, 0.01f)
    }

    @Test
    fun `should use default efficiency when not specified`() {
        // Given
        val work = 1000f

        // When
        val energyExpenditure = calculator.estimateEnergyExpenditure(work)

        // Then
        val expected = (work / 0.23f) / 4184f
        assertEquals(expected, energyExpenditure, 0.01f)
    }

    // Rate of Force Development Tests
    @Test
    fun `should calculate RFD correctly`() {
        // Given
        val forceHistory = listOf(0f, 100f, 200f, 300f, 400f)
        val timeInterval = 0.1f  // 0.1 seconds between samples

        // When
        val rfd = calculator.calculateRFD(forceHistory, timeInterval)

        // Then
        val deltaForce = 400f - 0f
        val deltaTime = 0.1f * (5 - 1)  // 0.4 seconds total
        val expected = deltaForce / deltaTime
        assertEquals(expected, rfd, 0.01f)
    }

    @Test
    fun `should return zero RFD for insufficient force history`() {
        // Given
        val forceHistory = listOf(100f)
        val timeInterval = 0.1f

        // When
        val rfd = calculator.calculateRFD(forceHistory, timeInterval)

        // Then
        assertEquals(0f, rfd, 0.001f)
    }

    @Test
    fun `should return zero RFD for zero time interval`() {
        // Given
        val forceHistory = listOf(0f, 100f, 200f)
        val timeInterval = 0f

        // When
        val rfd = calculator.calculateRFD(forceHistory, timeInterval)

        // Then
        assertEquals(0f, rfd, 0.001f)
    }

    @Test
    fun `should return zero RFD for negative time interval`() {
        // Given
        val forceHistory = listOf(0f, 100f, 200f)
        val timeInterval = -0.1f

        // When
        val rfd = calculator.calculateRFD(forceHistory, timeInterval)

        // Then
        assertEquals(0f, rfd, 0.001f)
    }

    @Test
    fun `should handle negative RFD for decreasing force`() {
        // Given
        val forceHistory = listOf(400f, 300f, 200f, 100f, 0f)
        val timeInterval = 0.1f

        // When
        val rfd = calculator.calculateRFD(forceHistory, timeInterval)

        // Then
        val deltaForce = 0f - 400f
        val deltaTime = 0.1f * (5 - 1)
        val expected = deltaForce / deltaTime
        assertEquals(expected, rfd, 0.01f)
    }
}
