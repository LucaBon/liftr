package com.lifthelper.data.repository

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.lifthelper.data.local.preferences.SettingsDataStore
import com.lifthelper.domain.error.LiftHelperError
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.model.AnthropometricData
import com.lifthelper.domain.model.CalibrationData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Integration tests for CalibrationRepositoryImpl.
 *
 * Tests the repository layer's integration with DataStore,
 * including Flow-based data streams and error handling.
 */
class CalibrationRepositoryImplTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var dataStore: SettingsDataStore
    private lateinit var logger: Logger
    private lateinit var repository: CalibrationRepositoryImpl

    private val testCalibrationData = CalibrationData(
        pixelsPerMeter = 1000f,
        userHeight = 1.75f,
        shoulderWidth = 0.45f,
        anthropometricData = null
    )

    @Before
    fun setup() {
        dataStore = mockk()
        logger = mockk(relaxed = true)
        repository = CalibrationRepositoryImpl(dataStore, logger)
    }

    @Test
    fun `getCalibrationData should emit calibration data from DataStore`() = runTest {
        // Given
        every { dataStore.calibrationData } returns flowOf(testCalibrationData)

        // When
        repository.getCalibrationData().test {
            // Then
            val emitted = awaitItem()
            assertEquals(testCalibrationData, emitted)
            assertEquals(1000f, emitted?.pixelsPerMeter)
            assertEquals(1.75f, emitted?.userHeight)
            awaitComplete()
        }
    }

    @Test
    fun `getCalibrationData should emit null when no calibration exists`() = runTest {
        // Given
        every { dataStore.calibrationData } returns flowOf(null)

        // When
        repository.getCalibrationData().test {
            // Then
            val emitted = awaitItem()
            assertNull(emitted)
            awaitComplete()
        }
    }

    @Test
    fun `isCalibrated should emit true when calibrated`() = runTest {
        // Given
        every { dataStore.isCalibrated } returns flowOf(true)

        // When
        repository.isCalibrated().test {
            // Then
            val emitted = awaitItem()
            assertTrue(emitted)
            awaitComplete()
        }
    }

    @Test
    fun `isCalibrated should emit false when not calibrated`() = runTest {
        // Given
        every { dataStore.isCalibrated } returns flowOf(false)

        // When
        repository.isCalibrated().test {
            // Then
            val emitted = awaitItem()
            assertFalse(emitted)
            awaitComplete()
        }
    }

    @Test
    fun `saveCalibration should return success when save succeeds`() = runTest {
        // Given
        coEvery { dataStore.saveCalibration(testCalibrationData) } returns Unit

        // When
        val result = repository.saveCalibration(testCalibrationData)

        // Then
        assertTrue(result.isSuccess)
        coVerify { dataStore.saveCalibration(testCalibrationData) }
    }

    @Test
    fun `saveCalibration should return error when save fails`() = runTest {
        // Given
        val exception = RuntimeException("DataStore error")
        coEvery { dataStore.saveCalibration(testCalibrationData) } throws exception

        // When
        val result = repository.saveCalibration(testCalibrationData)

        // Then
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is LiftHelperError.DatabaseError)
    }

    @Test
    fun `updatePixelsPerMeter should return success when update succeeds`() = runTest {
        // Given
        val newPixelsPerMeter = 1200f
        coEvery { dataStore.updatePixelsPerMeter(newPixelsPerMeter) } returns Unit

        // When
        val result = repository.updatePixelsPerMeter(newPixelsPerMeter)

        // Then
        assertTrue(result.isSuccess)
        coVerify { dataStore.updatePixelsPerMeter(newPixelsPerMeter) }
    }

    @Test
    fun `updatePixelsPerMeter should return error when update fails`() = runTest {
        // Given
        val newPixelsPerMeter = 1200f
        val exception = RuntimeException("Update failed")
        coEvery { dataStore.updatePixelsPerMeter(newPixelsPerMeter) } throws exception

        // When
        val result = repository.updatePixelsPerMeter(newPixelsPerMeter)

        // Then
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is LiftHelperError.DatabaseError)
    }

    @Test
    fun `saveAnthropometricData should return success when save succeeds`() = runTest {
        // Given
        val anthropometricData = AnthropometricData.getDefaultRatios(1.75f)
        coEvery { dataStore.saveAnthropometricData(anthropometricData) } returns Unit

        // When
        val result = repository.saveAnthropometricData(anthropometricData)

        // Then
        assertTrue(result.isSuccess)
        coVerify { dataStore.saveAnthropometricData(anthropometricData) }
    }

    @Test
    fun `saveAnthropometricData should return error when save fails`() = runTest {
        // Given
        val anthropometricData = AnthropometricData.getDefaultRatios(1.75f)
        val exception = RuntimeException("Save failed")
        coEvery { dataStore.saveAnthropometricData(anthropometricData) } throws exception

        // When
        val result = repository.saveAnthropometricData(anthropometricData)

        // Then
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is LiftHelperError.DatabaseError)
    }

    @Test
    fun `clearCalibration should return success when clear succeeds`() = runTest {
        // Given
        coEvery { dataStore.clearCalibration() } returns Unit

        // When
        val result = repository.clearCalibration()

        // Then
        assertTrue(result.isSuccess)
        coVerify { dataStore.clearCalibration() }
    }

    @Test
    fun `clearCalibration should return error when clear fails`() = runTest {
        // Given
        val exception = RuntimeException("Clear failed")
        coEvery { dataStore.clearCalibration() } throws exception

        // When
        val result = repository.clearCalibration()

        // Then
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is LiftHelperError.DatabaseError)
    }
}
