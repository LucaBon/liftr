package com.liftr.ui.screens.onboarding

import com.liftr.domain.repository.SettingsKeys
import com.liftr.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var viewModel: OnboardingViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        settingsRepository = FakeSettingsRepository()
        viewModel = OnboardingViewModel(settingsRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `markOnboardingComplete writes true to ONBOARDING_COMPLETED key`() = runTest(dispatcher) {
        viewModel.markOnboardingComplete()
        advanceUntilIdle()

        assertEquals(1, settingsRepository.writes.size)
        val write = settingsRepository.writes.first()
        assertEquals(SettingsKeys.ONBOARDING_COMPLETED, write.first)
        assertTrue(write.second)
    }

    private class FakeSettingsRepository : SettingsRepository {
        val writes = mutableListOf<Pair<String, Boolean>>()

        override fun getStringSetting(key: String, defaultValue: String): Flow<String> = flowOf(defaultValue)
        override fun getBooleanSetting(key: String, defaultValue: Boolean): Flow<Boolean> = flowOf(defaultValue)
        override fun getFloatSetting(key: String, defaultValue: Float): Flow<Float> = flowOf(defaultValue)

        override suspend fun saveStringSetting(key: String, value: String): Result<Unit> = Result.success(Unit)
        override suspend fun saveBooleanSetting(key: String, value: Boolean): Result<Unit> {
            writes.add(key to value)
            return Result.success(Unit)
        }
        override suspend fun saveFloatSetting(key: String, value: Float): Result<Unit> = Result.success(Unit)

        override suspend fun clearAllSettings(): Result<Unit> = Result.success(Unit)
    }
}
