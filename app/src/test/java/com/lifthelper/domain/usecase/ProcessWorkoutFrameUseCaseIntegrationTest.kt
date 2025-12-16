package com.lifthelper.domain.usecase

import androidx.camera.core.ImageProxy
import com.lifthelper.domain.exercise.ExerciseStrategy
import com.lifthelper.domain.exercise.ExerciseStrategyFactory
import com.lifthelper.domain.exercise.FormValidationResult
import com.lifthelper.domain.exercise.ExerciseMetrics
import com.lifthelper.domain.exercise.PhaseTransitionResult
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.model.AnthropometricData
import com.lifthelper.domain.model.CalibrationData
import com.lifthelper.domain.model.CalibrationQuality
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.PoseKeypoint
import com.lifthelper.domain.model.PoseResult
import com.lifthelper.domain.model.VelocityData
import com.lifthelper.domain.value.Joules
import com.lifthelper.domain.value.Kilograms
import com.lifthelper.domain.value.Meters
import com.lifthelper.domain.value.MetersPerSecond
import com.lifthelper.domain.value.Watts
import com.lifthelper.domain.vision.IPoseDetector
import com.lifthelper.domain.vision.IPositionCalculator
import com.lifthelper.domain.vision.IVelocityCalculator
import com.lifthelper.vision.tracking.MovementPhase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for ProcessWorkoutFrameUseCase.
 *
 * Tests the complete frame processing workflow including pose detection,
 * velocity calculation, phase detection, and form validation.
 */
class ProcessWorkoutFrameUseCaseIntegrationTest {

    private lateinit var poseDetector: IPoseDetector
    private lateinit var velocityCalculator: IVelocityCalculator
    private lateinit var positionCalculator: IPositionCalculator
    private lateinit var strategyFactory: ExerciseStrategyFactory
    private lateinit var logger: Logger
    private lateinit var useCase: ProcessWorkoutFrameUseCase

    private val calibrationData = CalibrationData(
        pixelsPerMeter = 500f,
        userHeight = 1.75f,
        shoulderWidth = 0.45f,
        anthropometricData = AnthropometricData.getDefaultRatios(1.75f, 75f),
        timestamp = System.currentTimeMillis()
    )

    private val poseResult = PoseResult(
        landmarks = List(33) { PoseKeypoint(0f, 0f, 0f, 1f) },
        timestamp = System.currentTimeMillis()
    )

    private val velocityData = VelocityData(
        instantaneous = MetersPerSecond(0.5f),
        average = MetersPerSecond(0.4f),
        timestamp = System.currentTimeMillis(),
        position = Meters(1.0f)
    )

    @Before
    fun setup() {
        poseDetector = mockk()
        velocityCalculator = mockk()
        positionCalculator = mockk()
        strategyFactory = mockk()
        logger = mockk(relaxed = true)

        useCase = ProcessWorkoutFrameUseCase(
            poseDetector,
            velocityCalculator,
            positionCalculator,
            strategyFactory,
            logger
        )
    }

    @Test
    fun `successful frame processing should return Success result with all data`() = runTest {
        // Given
        val imageProxy = mockk<ImageProxy>(relaxed = true)
        val timestamp = System.currentTimeMillis()
        val strategy = mockk<ExerciseStrategy>()

        coEvery { poseDetector.detectPose(imageProxy, timestamp) } returns poseResult
        every { velocityCalculator.calculate(any(), any(), any(), any()) } returns velocityData
        every { strategyFactory.getStrategy(ExerciseType.BACK_SQUAT) } returns strategy
        every { strategy.detectPhase(any(), any(), any()) } returns PhaseTransitionResult(
            newPhase = MovementPhase.ECCENTRIC,
            repCompleted = false,
            debugInfo = "Test"
        )
        every { strategy.validateForm(any(), any()) } returns FormValidationResult(
            isValid = true,
            issues = emptyList()
        )
        every { strategy.calculateMetrics(any(), any(), any()) } returns ExerciseMetrics(
            displacement = Meters(0.5f),
            peakVelocity = MetersPerSecond(0.5f),
            avgPower = Watts(500f),
            peakPower = Watts(700f),
            work = Joules(250f)
        )
        every { positionCalculator.getCalibrationQuality(any(), any()) } returns CalibrationQuality.OPTIMAL

        val params = ProcessWorkoutFrameUseCase.Params(
            imageProxy = imageProxy,
            timestamp = timestamp,
            calibrationData = calibrationData,
            exerciseType = ExerciseType.BACK_SQUAT,
            isRecording = true,
            lastPhase = MovementPhase.IDLE,
            currentRepCount = 0,
            weight = Kilograms(100f)
        )

        // When
        val result = useCase(params)

        // Then
        assertTrue(result is FrameProcessingResult.Success)
        val successResult = result as FrameProcessingResult.Success
        assertEquals(poseResult, successResult.poseResult)
        assertEquals(velocityData, successResult.velocityData)
        assertNotNull(successResult.phaseResult)
        assertEquals(MovementPhase.ECCENTRIC, successResult.phaseResult?.phase)
        assertEquals(0, successResult.phaseResult?.repCount)
        assertNotNull(successResult.exerciseMetrics)
        assertTrue(successResult.formValidation.isValid)
        assertEquals(CalibrationQuality.OPTIMAL, successResult.calibrationQuality)
    }

    @Test
    fun `no pose detected should return NoPoseDetected result`() = runTest {
        // Given
        val imageProxy = mockk<ImageProxy>(relaxed = true)
        val timestamp = System.currentTimeMillis()

        coEvery { poseDetector.detectPose(imageProxy, timestamp) } returns null

        val params = ProcessWorkoutFrameUseCase.Params(
            imageProxy = imageProxy,
            timestamp = timestamp,
            calibrationData = calibrationData,
            exerciseType = ExerciseType.BACK_SQUAT,
            isRecording = false,
            lastPhase = MovementPhase.IDLE,
            currentRepCount = 0
        )

        // When
        val result = useCase(params)

        // Then
        assertTrue(result is FrameProcessingResult.NoPoseDetected)
        verify { imageProxy.close() }
    }

    @Test
    fun `no calibration data should return NoCalibration result`() = runTest {
        // Given
        val imageProxy = mockk<ImageProxy>(relaxed = true)
        val timestamp = System.currentTimeMillis()

        coEvery { poseDetector.detectPose(imageProxy, timestamp) } returns poseResult

        val params = ProcessWorkoutFrameUseCase.Params(
            imageProxy = imageProxy,
            timestamp = timestamp,
            calibrationData = null,
            exerciseType = ExerciseType.BACK_SQUAT,
            isRecording = false,
            lastPhase = MovementPhase.IDLE,
            currentRepCount = 0
        )

        // When
        val result = useCase(params)

        // Then
        assertTrue(result is FrameProcessingResult.NoCalibration)
        verify { imageProxy.close() }
    }

    @Test
    fun `recording disabled should not detect phase or count reps`() = runTest {
        // Given
        val imageProxy = mockk<ImageProxy>(relaxed = true)
        val timestamp = System.currentTimeMillis()
        val strategy = mockk<ExerciseStrategy>()

        coEvery { poseDetector.detectPose(imageProxy, timestamp) } returns poseResult
        every { velocityCalculator.calculate(any(), any(), any(), any()) } returns velocityData
        every { strategyFactory.getStrategy(ExerciseType.BACK_SQUAT) } returns strategy
        every { strategy.validateForm(any(), any()) } returns FormValidationResult(
            isValid = true,
            issues = emptyList()
        )
        every { strategy.calculateMetrics(any(), any(), any()) } returns ExerciseMetrics(
            displacement = Meters(0.5f),
            peakVelocity = MetersPerSecond(0.5f),
            avgPower = Watts(500f),
            peakPower = Watts(700f),
            work = Joules(250f)
        )
        every { positionCalculator.getCalibrationQuality(any(), any()) } returns CalibrationQuality.OPTIMAL

        val params = ProcessWorkoutFrameUseCase.Params(
            imageProxy = imageProxy,
            timestamp = timestamp,
            calibrationData = calibrationData,
            exerciseType = ExerciseType.BACK_SQUAT,
            isRecording = false, // Not recording
            lastPhase = MovementPhase.IDLE,
            currentRepCount = 5
        )

        // When
        val result = useCase(params)

        // Then
        assertTrue(result is FrameProcessingResult.Success)
        val successResult = result as FrameProcessingResult.Success
        assertNull(successResult.phaseResult) // Phase result should be null when not recording
    }

    @Test
    fun `rep completion should increment rep count`() = runTest {
        // Given
        val imageProxy = mockk<ImageProxy>(relaxed = true)
        val timestamp = System.currentTimeMillis()
        val strategy = mockk<ExerciseStrategy>()

        coEvery { poseDetector.detectPose(imageProxy, timestamp) } returns poseResult
        every { velocityCalculator.calculate(any(), any(), any(), any()) } returns velocityData
        every { strategyFactory.getStrategy(ExerciseType.BACK_SQUAT) } returns strategy
        every { strategy.detectPhase(any(), any(), any()) } returns PhaseTransitionResult(
            newPhase = MovementPhase.TOP,
            repCompleted = true, // Rep completed
            debugInfo = "Rep completed"
        )
        every { strategy.validateForm(any(), any()) } returns FormValidationResult(
            isValid = true,
            issues = emptyList()
        )
        every { strategy.calculateMetrics(any(), any(), any()) } returns ExerciseMetrics(
            displacement = Meters(0.5f),
            peakVelocity = MetersPerSecond(0.5f),
            avgPower = Watts(500f),
            peakPower = Watts(700f),
            work = Joules(250f)
        )
        every { positionCalculator.getCalibrationQuality(any(), any()) } returns CalibrationQuality.OPTIMAL

        val params = ProcessWorkoutFrameUseCase.Params(
            imageProxy = imageProxy,
            timestamp = timestamp,
            calibrationData = calibrationData,
            exerciseType = ExerciseType.BACK_SQUAT,
            isRecording = true,
            lastPhase = MovementPhase.CONCENTRIC,
            currentRepCount = 5
        )

        // When
        val result = useCase(params)

        // Then
        assertTrue(result is FrameProcessingResult.Success)
        val successResult = result as FrameProcessingResult.Success
        assertNotNull(successResult.phaseResult)
        assertEquals(6, successResult.phaseResult?.repCount) // Should be incremented
        assertTrue(successResult.phaseResult?.repCounted == true)
        assertEquals(MovementPhase.TOP, successResult.phaseResult?.phase)
    }

    @Test
    fun `different exercise types should use different strategies`() = runTest {
        // Given
        val imageProxy = mockk<ImageProxy>(relaxed = true)
        val timestamp = System.currentTimeMillis()
        val frontSquatStrategy = mockk<ExerciseStrategy>()

        coEvery { poseDetector.detectPose(imageProxy, timestamp) } returns poseResult
        every { velocityCalculator.calculate(any(), any(), any(), any()) } returns velocityData
        every { strategyFactory.getStrategy(ExerciseType.FRONT_SQUAT) } returns frontSquatStrategy
        every { frontSquatStrategy.detectPhase(any(), any(), any()) } returns PhaseTransitionResult(
            newPhase = MovementPhase.ECCENTRIC,
            repCompleted = false,
            debugInfo = "Front squat"
        )
        every { frontSquatStrategy.validateForm(any(), any()) } returns FormValidationResult(
            isValid = true,
            issues = emptyList()
        )
        every { frontSquatStrategy.calculateMetrics(any(), any(), any()) } returns ExerciseMetrics(
            displacement = Meters(0.5f),
            peakVelocity = MetersPerSecond(0.5f),
            avgPower = Watts(500f),
            peakPower = Watts(700f),
            work = Joules(250f)
        )
        every { positionCalculator.getCalibrationQuality(any(), any()) } returns CalibrationQuality.OPTIMAL

        val params = ProcessWorkoutFrameUseCase.Params(
            imageProxy = imageProxy,
            timestamp = timestamp,
            calibrationData = calibrationData,
            exerciseType = ExerciseType.FRONT_SQUAT,
            isRecording = true,
            lastPhase = MovementPhase.IDLE,
            currentRepCount = 0
        )

        // When
        val result = useCase(params)

        // Then
        assertTrue(result is FrameProcessingResult.Success)
        verify { strategyFactory.getStrategy(ExerciseType.FRONT_SQUAT) }
    }

    @Test
    fun `imageProxy should always be closed even on error`() = runTest {
        // Given
        val imageProxy = mockk<ImageProxy>(relaxed = true)
        val timestamp = System.currentTimeMillis()

        coEvery { poseDetector.detectPose(imageProxy, timestamp) } throws RuntimeException("Test error")

        val params = ProcessWorkoutFrameUseCase.Params(
            imageProxy = imageProxy,
            timestamp = timestamp,
            calibrationData = calibrationData,
            exerciseType = ExerciseType.BACK_SQUAT,
            isRecording = false,
            lastPhase = MovementPhase.IDLE,
            currentRepCount = 0
        )

        // When
        val result = useCase(params)

        // Then
        assertTrue(result is FrameProcessingResult.Error)
        verify { imageProxy.close() } // Should be closed even on error
    }

    @Test
    fun `null velocity data should still process successfully`() = runTest {
        // Given
        val imageProxy = mockk<ImageProxy>(relaxed = true)
        val timestamp = System.currentTimeMillis()
        val strategy = mockk<ExerciseStrategy>()

        coEvery { poseDetector.detectPose(imageProxy, timestamp) } returns poseResult
        every { velocityCalculator.calculate(any(), any(), any(), any()) } returns null // No velocity data
        every { strategyFactory.getStrategy(ExerciseType.BACK_SQUAT) } returns strategy
        every { strategy.detectPhase(any(), any(), any()) } returns PhaseTransitionResult(
            newPhase = MovementPhase.ECCENTRIC,
            repCompleted = false,
            debugInfo = "Test"
        )
        every { strategy.validateForm(any(), any()) } returns FormValidationResult(
            isValid = true,
            issues = emptyList()
        )
        every { positionCalculator.getCalibrationQuality(any(), any()) } returns CalibrationQuality.OPTIMAL

        val params = ProcessWorkoutFrameUseCase.Params(
            imageProxy = imageProxy,
            timestamp = timestamp,
            calibrationData = calibrationData,
            exerciseType = ExerciseType.BACK_SQUAT,
            isRecording = true,
            lastPhase = MovementPhase.IDLE,
            currentRepCount = 0
        )

        // When
        val result = useCase(params)

        // Then
        assertTrue(result is FrameProcessingResult.Success)
        val successResult = result as FrameProcessingResult.Success
        assertNull(successResult.velocityData)
        assertNull(successResult.exerciseMetrics) // Metrics can't be calculated without velocity
    }
}
