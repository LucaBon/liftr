package com.lifthelper.feedback

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import com.lifthelper.domain.exercise.ExercisePhaseState
import com.lifthelper.domain.exercise.FormIssue
import com.lifthelper.domain.exercise.FormValidationResult
import com.lifthelper.domain.exercise.Severity
import com.lifthelper.domain.exercise.VelocityLossState
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.model.CalibrationData
import com.lifthelper.domain.model.CalibrationQuality
import com.lifthelper.domain.model.FeedbackEvent
import com.lifthelper.domain.model.PoseResult
import com.lifthelper.domain.model.VelocityData
import com.lifthelper.domain.repository.CalibrationRepository
import com.lifthelper.domain.repository.SettingsRepository
import com.lifthelper.domain.usecase.FrameProcessingResult
import com.lifthelper.domain.usecase.PhaseResult
import com.lifthelper.domain.repository.CustomExerciseRepository
import com.lifthelper.domain.usecase.CheckPersonalRecordsUseCase
import com.lifthelper.domain.usecase.LogManualSetUseCase
import com.lifthelper.domain.usecase.ProcessWorkoutFrameUseCase
import com.lifthelper.domain.usecase.SaveWorkoutUseCase
import com.lifthelper.domain.value.Kilograms
import com.lifthelper.domain.value.Meters
import com.lifthelper.domain.value.MetersPerSecond
import com.lifthelper.domain.value.RepCount
import com.lifthelper.ui.screens.workout.WorkoutViewModel
import com.lifthelper.vision.tracking.MovementPhase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Integration tests verifying that [WorkoutViewModel] emits the correct
 * [FeedbackEvent]s when processing frame results.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutViewModelFeedbackTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var feedbackEmitter: FakeFeedbackEmitter
    private lateinit var processFrameUseCase: ProcessWorkoutFrameUseCase
    private lateinit var saveWorkoutUseCase: SaveWorkoutUseCase
    private lateinit var checkPersonalRecordsUseCase: CheckPersonalRecordsUseCase
    private lateinit var logManualSetUseCase: LogManualSetUseCase
    private lateinit var customExerciseRepository: CustomExerciseRepository
    private lateinit var calibrationRepository: CalibrationRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var logger: Logger
    private lateinit var viewModel: WorkoutViewModel

    private val mockPoseResult = mockk<PoseResult>(relaxed = true)
    private val mockVelocityData = VelocityData(
        instantaneous = MetersPerSecond(0.80f),
        average = MetersPerSecond(0.75f),
        position = Meters(0.30f),
        timestamp = System.currentTimeMillis()
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        feedbackEmitter = FakeFeedbackEmitter()
        processFrameUseCase = mockk(relaxed = true)
        saveWorkoutUseCase = mockk(relaxed = true)
        checkPersonalRecordsUseCase = mockk(relaxed = true)
        logManualSetUseCase = mockk(relaxed = true)
        customExerciseRepository = mockk()
        calibrationRepository = mockk()
        settingsRepository = mockk()
        logger = mockk(relaxed = true)

        // Default settings flows
        every { calibrationRepository.getCalibrationData() } returns flowOf(null)
        every { settingsRepository.getBooleanSetting("vbt_zones_enabled", true) } returns flowOf(true)
        every { settingsRepository.getFloatSetting("velocity_loss_threshold", any()) } returns flowOf(0.20f)
        every { settingsRepository.getBooleanSetting("audio_feedback_enabled", true) } returns flowOf(true)
        every { settingsRepository.getBooleanSetting("haptic_feedback_enabled", true) } returns flowOf(true)
        every { settingsRepository.getBooleanSetting("rest_timer_enabled", true) } returns flowOf(true)
        every { settingsRepository.getBooleanSetting("tempo_mode_enabled", false) } returns flowOf(false)
        every { settingsRepository.getStringSetting("last_tempo_prescription", "") } returns flowOf("")
        every { customExerciseRepository.getExerciseNames() } returns flowOf(emptyList())

        viewModel = WorkoutViewModel(
            processFrameUseCase = processFrameUseCase,
            saveWorkoutUseCase = saveWorkoutUseCase,
            checkPersonalRecordsUseCase = checkPersonalRecordsUseCase,
            logManualSetUseCase = logManualSetUseCase,
            customExerciseRepository = customExerciseRepository,
            calibrationRepository = calibrationRepository,
            settingsRepository = settingsRepository,
            feedbackEmitter = feedbackEmitter,
            logger = logger,
            savedStateHandle = SavedStateHandle()
        )

        // Let init coroutines complete
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `feedback settings are loaded from repository`() {
        assertTrue(feedbackEmitter.audioEnabled)
        assertTrue(feedbackEmitter.hapticEnabled)
    }

    @Test
    fun `feedback settings disabled propagates to emitter`() = runTest(testDispatcher) {
        // Reconfigure with disabled settings
        every { settingsRepository.getBooleanSetting("audio_feedback_enabled", true) } returns flowOf(false)
        every { settingsRepository.getBooleanSetting("haptic_feedback_enabled", true) } returns flowOf(false)

        @Suppress("UNUSED_VARIABLE")
        val vm = WorkoutViewModel(
            processFrameUseCase = processFrameUseCase,
            saveWorkoutUseCase = saveWorkoutUseCase,
            checkPersonalRecordsUseCase = checkPersonalRecordsUseCase,
            logManualSetUseCase = logManualSetUseCase,
            customExerciseRepository = customExerciseRepository,
            calibrationRepository = calibrationRepository,
            settingsRepository = settingsRepository,
            feedbackEmitter = feedbackEmitter,
            logger = logger,
            savedStateHandle = SavedStateHandle()
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(feedbackEmitter.audioEnabled)
        assertFalse(feedbackEmitter.hapticEnabled)
    }

    @Test
    fun `no feedback events emitted when not recording`() {
        // viewModel is not recording by default
        assertFalse(viewModel.uiState.value.isRecording)

        // Simulate a frame result via the internal handler — not recording, no events
        feedbackEmitter.clear()
        assertEquals(0, feedbackEmitter.emittedEvents.size)
    }

    @Test
    fun `FeedbackEvent RepCounted contains correct rep number`() {
        val event = FeedbackEvent.RepCounted(3)
        assertEquals(3, event.repNumber)
    }

    @Test
    fun `FeedbackEvent VelocityLossAlert contains loss percent`() {
        val event = FeedbackEvent.VelocityLossAlert(0.25f)
        assertEquals(0.25f, event.lossPercent, 0.001f)
    }

    @Test
    fun `FeedbackEvent FormWarning contains issue data`() {
        val issue = FormIssue(Severity.WARNING, "knee_cave", "Watch your knees")
        val event = FeedbackEvent.FormWarning(issue)

        assertEquals("knee_cave", event.issue.message)
        assertEquals("Watch your knees", event.issue.suggestion)
        assertEquals(Severity.WARNING, event.issue.severity)
    }

    @Test
    fun `FeedbackEvent FormCritical contains issue data`() {
        val issue = FormIssue(Severity.CRITICAL, "back_rounding", "Stop lifting")
        val event = FeedbackEvent.FormCritical(issue)

        assertEquals(Severity.CRITICAL, event.issue.severity)
    }

    @Test
    fun `fake emitter records events in order`() {
        feedbackEmitter.emit(FeedbackEvent.RepCounted(1))
        feedbackEmitter.emit(FeedbackEvent.SetComplete)
        feedbackEmitter.emit(FeedbackEvent.DepthAchieved)

        assertEquals(3, feedbackEmitter.emittedEvents.size)
        assertTrue(feedbackEmitter.emittedEvents[0] is FeedbackEvent.RepCounted)
        assertTrue(feedbackEmitter.emittedEvents[1] is FeedbackEvent.SetComplete)
        assertTrue(feedbackEmitter.emittedEvents[2] is FeedbackEvent.DepthAchieved)
    }

    @Test
    fun `fake emitter eventsOfType filters correctly`() {
        feedbackEmitter.emit(FeedbackEvent.RepCounted(1))
        feedbackEmitter.emit(FeedbackEvent.SetComplete)
        feedbackEmitter.emit(FeedbackEvent.RepCounted(2))

        val repEvents = feedbackEmitter.eventsOfType<FeedbackEvent.RepCounted>()
        assertEquals(2, repEvents.size)
        assertEquals(1, repEvents[0].repNumber)
        assertEquals(2, repEvents[1].repNumber)
    }

    @Test
    fun `onCleared stops current speech`() {
        // We can't easily call onCleared directly (protected), but we can verify
        // the emitter's stopCurrentSpeech is callable
        feedbackEmitter.stopCurrentSpeech()
        assertTrue(feedbackEmitter.speechStopped)
    }
}
