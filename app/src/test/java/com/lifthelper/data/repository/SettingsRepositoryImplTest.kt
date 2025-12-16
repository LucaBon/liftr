package com.lifthelper.data.repository

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import com.lifthelper.domain.error.LiftHelperError
import com.lifthelper.domain.logging.Logger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Integration tests for SettingsRepositoryImpl.
 *
 * Tests the repository layer's integration with DataStore for preferences,
 * including Flow-based data streams and error handling.
 */
class SettingsRepositoryImplTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var logger: Logger
    private lateinit var repository: SettingsRepositoryImpl

    @Before
    fun setup() {
        dataStore = mockk()
        logger = mockk(relaxed = true)
        repository = SettingsRepositoryImpl(dataStore, logger)
    }

    @Test
    fun `getBooleanSetting should emit value from DataStore`() = runTest {
        // Given
        val key = "test_bool_key"
        val expectedValue = true
        val preferencesKey = booleanPreferencesKey(key)
        val mockPreferences = mockk<Preferences>()

        every { mockPreferences[preferencesKey] } returns expectedValue
        every { dataStore.data } returns flowOf(mockPreferences)

        // When
        repository.getBooleanSetting(key, false).test {
            // Then
            val emitted = awaitItem()
            assertEquals(expectedValue, emitted)
            awaitComplete()
        }
    }

    @Test
    fun `saveBooleanSetting should return success when save succeeds`() = runTest {
        // Given
        val key = "test_bool_key"
        val value = true
        val mockResult = mockk<Preferences>()

        coEvery {
            dataStore.updateData(any())
        } returns mockResult

        // When
        val result = repository.saveBooleanSetting(key, value)

        // Then
        assertTrue(result.isSuccess)
        coVerify { dataStore.updateData(any()) }
    }

    @Test
    fun `getFloatSetting should emit value from DataStore`() = runTest {
        // Given
        val key = "test_float_key"
        val expectedValue = 123.45f
        val preferencesKey = floatPreferencesKey(key)
        val mockPreferences = mockk<Preferences>()

        every { mockPreferences[preferencesKey] } returns expectedValue
        every { dataStore.data } returns flowOf(mockPreferences)

        // When
        repository.getFloatSetting(key, 0f).test {
            // Then
            val emitted = awaitItem()
            assertEquals(expectedValue, emitted, 0.01f)
            awaitComplete()
        }
    }

    @Test
    fun `saveFloatSetting should return success when save succeeds`() = runTest {
        // Given
        val key = "test_float_key"
        val value = 456.78f
        val mockResult = mockk<Preferences>()

        coEvery { dataStore.updateData(any()) } returns mockResult

        // When
        val result = repository.saveFloatSetting(key, value)

        // Then
        assertTrue(result.isSuccess)
        coVerify { dataStore.updateData(any()) }
    }

    @Test
    fun `getStringSetting should emit value from DataStore`() = runTest {
        // Given
        val key = "test_string_key"
        val expectedValue = "test value"
        val preferencesKey = stringPreferencesKey(key)
        val mockPreferences = mockk<Preferences>()

        every { mockPreferences[preferencesKey] } returns expectedValue
        every { dataStore.data } returns flowOf(mockPreferences)

        // When
        repository.getStringSetting(key, "").test {
            // Then
            val emitted = awaitItem()
            assertEquals(expectedValue, emitted)
            awaitComplete()
        }
    }

    @Test
    fun `saveStringSetting should return success when save succeeds`() = runTest {
        // Given
        val key = "test_string_key"
        val value = "test value"
        val mockResult = mockk<Preferences>()

        coEvery { dataStore.updateData(any()) } returns mockResult

        // When
        val result = repository.saveStringSetting(key, value)

        // Then
        assertTrue(result.isSuccess)
        coVerify { dataStore.updateData(any()) }
    }

    @Test
    fun `clearAllSettings should return success when clear succeeds`() = runTest {
        // Given
        val mockResult = mockk<Preferences>()
        coEvery { dataStore.updateData(any()) } returns mockResult

        // When
        val result = repository.clearAllSettings()

        // Then
        assertTrue(result.isSuccess)
        coVerify { dataStore.updateData(any()) }
    }
}
