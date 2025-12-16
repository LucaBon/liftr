package com.lifthelper.domain.usecase

import com.lifthelper.domain.model.AnthropometricData
import com.lifthelper.domain.model.CalibrationData
import com.lifthelper.domain.repository.CalibrationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class LoadCalibrationUseCaseTest {

    private lateinit var calibrationRepository: FakeCalibrationRepository
    private lateinit var useCase: LoadCalibrationUseCase

    @Before
    fun setup() {
        calibrationRepository = FakeCalibrationRepository()
        useCase = LoadCalibrationUseCase(calibrationRepository)
    }

    @Test
    fun `should return calibration data when available`() = runTest {
        // Given
        val calibrationData = createTestCalibrationData()
        calibrationRepository.calibrationData = calibrationData

        // When
        val result = useCase(NoParams).first()

        // Then
        assertNotNull(result)
        assertEquals(calibrationData, result)
    }

    @Test
    fun `should return null when no calibration data available`() = runTest {
        // Given
        calibrationRepository.calibrationData = null

        // When
        val result = useCase(NoParams).first()

        // Then
        assertNull(result)
    }

    @Test
    fun `should emit updates when calibration data changes`() = runTest {
        // Given
        val calibration1 = createTestCalibrationData(userHeight = 1.7f)
        val calibration2 = createTestCalibrationData(userHeight = 1.8f)
        calibrationRepository.emissions = listOf(calibration1, calibration2)

        // When
        val emissions = useCase(NoParams).toList()

        // Then
        assertEquals(2, emissions.size)
        assertEquals(1.7f, emissions[0]?.userHeight)
        assertEquals(1.8f, emissions[1]?.userHeight)
    }

    @Test
    fun `should call repository getCalibrationData method`() = runTest {
        // Given
        calibrationRepository.calibrationData = null

        // When
        useCase(NoParams).first()

        // Then
        assertEquals(1, calibrationRepository.getCalibrationDataCallCount)
    }

    @Test
    fun `should handle null to non-null calibration transition`() = runTest {
        // Given
        val calibrationData = createTestCalibrationData()
        calibrationRepository.emissions = listOf(null, calibrationData)

        // When
        val emissions = useCase(NoParams).toList()

        // Then
        assertEquals(2, emissions.size)
        assertNull(emissions[0])
        assertNotNull(emissions[1])
        assertEquals(calibrationData, emissions[1])
    }

    // Helper function
    private fun createTestCalibrationData(
        userHeight: Float = 1.8f,
        pixelsPerMeter: Float = 100f,
        shoulderWidth: Float = 0.45f
    ): CalibrationData {
        return CalibrationData(
            pixelsPerMeter = pixelsPerMeter,
            userHeight = userHeight,
            shoulderWidth = shoulderWidth,
            anthropometricData = null,  // Simplified for testing
            timestamp = System.currentTimeMillis()
        )
    }

    // Fake implementation for testing
    private class FakeCalibrationRepository : CalibrationRepository {
        var calibrationData: CalibrationData? = null
        var emissions: List<CalibrationData?> = emptyList()
        var getCalibrationDataCallCount = 0

        override fun getCalibrationData(): Flow<CalibrationData?> {
            getCalibrationDataCallCount++
            return if (emissions.isNotEmpty()) {
                flowOf(*emissions.toTypedArray())
            } else {
                flowOf(calibrationData)
            }
        }

        override fun isCalibrated(): Flow<Boolean> {
            return flowOf(calibrationData != null)
        }

        override suspend fun saveCalibration(calibrationData: CalibrationData): Result<Unit> {
            throw NotImplementedError()
        }

        override suspend fun updatePixelsPerMeter(pixelsPerMeter: Float): Result<Unit> {
            throw NotImplementedError()
        }

        override suspend fun saveAnthropometricData(anthropometricData: AnthropometricData): Result<Unit> {
            throw NotImplementedError()
        }

        override suspend fun clearCalibration(): Result<Unit> {
            throw NotImplementedError()
        }
    }
}
