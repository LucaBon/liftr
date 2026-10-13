package com.liftr.data.repository

import app.cash.turbine.test
import com.liftr.data.local.preferences.SettingsDataStore
import com.liftr.domain.error.LiftrError
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.AnthropometricData
import com.liftr.domain.model.CalibrationData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Full integration tests for CalibrationRepositoryImpl.
 *
 * Tests the complete data flow from repository through SettingsDataStore,
 * with comprehensive error handling scenarios.
 */
class CalibrationRepositoryFullIntegrationTest {

    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var repository: CalibrationRepositoryImpl
    private lateinit var logger: Logger

    private val testCalibrationData = CalibrationData(
        pixelsPerMeter = 500f,
        userHeight = 1.75f,
        shoulderWidth = 0.45f,
        anthropometricData = AnthropometricData.getDefaultRatios(1.75f, 75f),
        timestamp = System.currentTimeMillis()
    )

    @Before
    fun setup() {
        settingsDataStore = mockk()
        logger = mockk(relaxed = true)

        repository = CalibrationRepositoryImpl(
            settingsDataStore = settingsDataStore,
            logger = logger
        )
    }

    @Test
    fun `saveCalibration should persist data through DataStore`() = runTest {
        // Given
        coEvery { settingsDataStore.saveCalibration(any()) } returns Unit

        // When
        val result = repository.saveCalibration(testCalibrationData)

        // Then
        assertTrue(result.isSuccess)
        coVerify { settingsDataStore.saveCalibration(testCalibrationData) }
        verify { logger.d(any(), any<String>()) }
    }

    @Test
    fun `saveCalibration should handle IOException`() = runTest {
        // Given
        coEvery { settingsDataStore.saveCalibration(any()) } throws IOException("Disk full")

        // When
        val result = repository.saveCalibration(testCalibrationData)

        // Then
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is LiftrError.CalibrationError)
        verify { logger.e(any(), any<String>(), any()) }
    }

    @Test
    fun `getCalibrationData should return flow from DataStore`() = runTest {
        // Given
        every { settingsDataStore.calibrationData } returns flowOf(testCalibrationData)

        // When
        repository.getCalibrationData().test {
            val data = awaitItem()

            // Then
            assertNotNull(data)
            assertEquals(testCalibrationData.pixelsPerMeter, data?.pixelsPerMeter)
            assertEquals(testCalibrationData.userHeight, data?.userHeight)
            assertEquals(testCalibrationData.shoulderWidth, data?.shoulderWidth)

            awaitComplete()
        }
    }

    @Test
    fun `getCalibrationData should handle null calibration`() = runTest {
        // Given
        every { settingsDataStore.calibrationData } returns flowOf(null)

        // When
        repository.getCalibrationData().test {
            val data = awaitItem()

            // Then
            assertNull(data)
            awaitComplete()
        }
    }

    @Test
    fun `isCalibrated should return flow from DataStore`() = runTest {
        // Given
        every { settingsDataStore.isCalibrated } returns flowOf(true)

        // When
        repository.isCalibrated().test {
            val calibrated = awaitItem()

            // Then
            assertTrue(calibrated)
            awaitComplete()
        }
    }

    @Test
    fun `isCalibrated should handle false state`() = runTest {
        // Given
        every { settingsDataStore.isCalibrated } returns flowOf(false)

        // When
        repository.isCalibrated().test {
            val calibrated = awaitItem()

            // Then
            assertFalse(calibrated)
            awaitComplete()
        }
    }

    @Test
    fun `updatePixelsPerMeter should validate positive value`() = runTest {
        // Given
        val validValue = 600f
        coEvery { settingsDataStore.updatePixelsPerMeter(any()) } returns Unit

        // When
        val result = repository.updatePixelsPerMeter(validValue)

        // Then
        assertTrue(result.isSuccess)
        coVerify { settingsDataStore.updatePixelsPerMeter(validValue) }
        verify { logger.d(any(), match { it.contains("600") }) }
    }

    @Test
    fun `updatePixelsPerMeter should reject negative value`() = runTest {
        // When
        val result = repository.updatePixelsPerMeter(-100f)

        // Then
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is LiftrError.ValidationError)
        assertTrue(error?.message?.contains("positive") == true)
    }

    @Test
    fun `updatePixelsPerMeter should reject zero value`() = runTest {
        // When
        val result = repository.updatePixelsPerMeter(0f)

        // Then
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is LiftrError.ValidationError)
    }

    @Test
    fun `updatePixelsPerMeter should handle IOException`() = runTest {
        // Given
        coEvery { settingsDataStore.updatePixelsPerMeter(any()) } throws IOException("Write failed")

        // When
        val result = repository.updatePixelsPerMeter(500f)

        // Then
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is LiftrError.DatabaseError)
        verify { logger.e(any(), any<String>(), any()) }
    }

    @Test
    fun `saveAnthropometricData should persist data through DataStore`() = runTest {
        // Given
        val anthropometricData = AnthropometricData.getDefaultRatios(1.75f, 75f)
        coEvery { settingsDataStore.saveAnthropometricData(any()) } returns Unit

        // When
        val result = repository.saveAnthropometricData(anthropometricData)

        // Then
        assertTrue(result.isSuccess)
        coVerify { settingsDataStore.saveAnthropometricData(anthropometricData) }
        verify { logger.d(any(), match { it.contains("Anthropometric data saved") }) }
    }

    @Test
    fun `saveAnthropometricData should handle IOException`() = runTest {
        // Given
        val anthropometricData = AnthropometricData.getDefaultRatios(1.75f, 75f)
        coEvery { settingsDataStore.saveAnthropometricData(any()) } throws IOException("Storage error")

        // When
        val result = repository.saveAnthropometricData(anthropometricData)

        // Then
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is LiftrError.DatabaseError)
        verify { logger.e(any(), any<String>(), any()) }
    }

    @Test
    fun `clearCalibration should clear data through DataStore`() = runTest {
        // Given
        coEvery { settingsDataStore.clearCalibration() } returns Unit

        // When
        val result = repository.clearCalibration()

        // Then
        assertTrue(result.isSuccess)
        coVerify { settingsDataStore.clearCalibration() }
        verify { logger.d(any(), match { it.contains("cleared") }) }
    }

    @Test
    fun `clearCalibration should handle IOException`() = runTest {
        // Given
        coEvery { settingsDataStore.clearCalibration() } throws IOException("Clear failed")

        // When
        val result = repository.clearCalibration()

        // Then
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is LiftrError.DatabaseError)
        verify { logger.e(any(), any<String>(), any()) }
    }

    @Test
    fun `calibration flow should emit updates`() = runTest {
        // Given
        val flow = flowOf(null, testCalibrationData)
        every { settingsDataStore.calibrationData } returns flow

        // When
        repository.getCalibrationData().test {
            // Then
            assertNull(awaitItem())
            assertEquals(testCalibrationData.pixelsPerMeter, awaitItem()?.pixelsPerMeter)
            awaitComplete()
        }
    }

    @Test
    fun `isCalibrated flow should emit updates`() = runTest {
        // Given
        val flow = flowOf(false, true, false)
        every { settingsDataStore.isCalibrated } returns flow

        // When
        repository.isCalibrated().test {
            // Then
            assertFalse(awaitItem())
            assertTrue(awaitItem())
            assertFalse(awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `repository should handle multiple sequential operations`() = runTest {
        // Given
        coEvery { settingsDataStore.saveCalibration(any()) } returns Unit
        coEvery { settingsDataStore.updatePixelsPerMeter(any()) } returns Unit
        coEvery { settingsDataStore.clearCalibration() } returns Unit

        // When/Then
        assertTrue(repository.saveCalibration(testCalibrationData).isSuccess)
        assertTrue(repository.updatePixelsPerMeter(600f).isSuccess)
        assertTrue(repository.clearCalibration().isSuccess)

        coVerify(exactly = 1) { settingsDataStore.saveCalibration(any()) }
        coVerify(exactly = 1) { settingsDataStore.updatePixelsPerMeter(any()) }
        coVerify(exactly = 1) { settingsDataStore.clearCalibration() }
    }
}
