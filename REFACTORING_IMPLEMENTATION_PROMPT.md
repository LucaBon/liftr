# Refactoring Implementation Prompt for AI Agent

## Context

You are tasked with implementing the architectural improvements and refactorings outlined in the [COMPREHENSIVE_CODE_REVIEW.md](COMPREHENSIVE_CODE_REVIEW.md) document for the LiftHelper Android application. This is a fitness tracking app that uses computer vision (MediaPipe) to analyze weightlifting exercises.

**Current Architecture:** MVVM with scattered business logic, missing abstraction layers, high coupling
**Target Architecture:** Clean Architecture with clear layer separation, SOLID compliance, testability

## Your Mission

Systematically refactor the LiftHelper codebase following Clean Architecture and Clean Code principles. Work through the priority levels sequentially, ensuring each refactoring is complete and tested before moving to the next.

---

## Phase 1: Foundation - Repository Pattern & Use Cases (Week 1)

### Priority: P0 - CRITICAL
### Estimated Effort: 10 hours
### Dependencies: None

### Task 1.1: Implement Repository Pattern (4 hours)

**Objective:** Decouple ViewModels from DAOs by introducing repository interfaces and implementations.

**Steps:**

1. **Create Repository Interfaces**
   ```
   Create: app/src/main/java/com/lifthelper/domain/repository/WorkoutRepository.kt
   Create: app/src/main/java/com/lifthelper/domain/repository/CalibrationRepository.kt
   Create: app/src/main/java/com/lifthelper/domain/repository/SettingsRepository.kt
   ```

   **Interface Design:**
   - Return `Result<T>` types for operations that can fail
   - Use `Flow<T>` for reactive data streams
   - Keep interfaces in `domain/repository` package (pure Kotlin, no Android deps)

   **Example for WorkoutRepository:**
   ```kotlin
   package com.lifthelper.domain.repository

   import com.lifthelper.domain.model.Workout
   import kotlinx.coroutines.flow.Flow

   interface WorkoutRepository {
       suspend fun saveWorkout(workout: Workout): Result<Long>
       fun getWorkouts(): Flow<List<Workout>>
       suspend fun getWorkoutById(id: Long): Result<Workout>
       suspend fun deleteWorkout(id: Long): Result<Unit>
   }
   ```

2. **Create Domain Models** (if not exist)
   ```
   Create: app/src/main/java/com/lifthelper/domain/model/Workout.kt
   Create: app/src/main/java/com/lifthelper/domain/model/Set.kt
   Create: app/src/main/java/com/lifthelper/domain/model/Rep.kt
   ```

   **Requirements:**
   - Pure Kotlin data classes (no Android dependencies)
   - Separate from database entities
   - Include validation logic if needed

3. **Create Mappers**
   ```
   Create: app/src/main/java/com/lifthelper/data/mapper/WorkoutMapper.kt
   ```

   **Responsibility:** Convert between Entity (database) and Domain models

   ```kotlin
   class WorkoutMapper {
       fun toDomain(entity: WorkoutEntity): Workout
       fun toEntity(domain: Workout): WorkoutEntity
   }
   ```

4. **Implement Repository Classes**
   ```
   Create: app/src/main/java/com/lifthelper/data/repository/WorkoutRepositoryImpl.kt
   Create: app/src/main/java/com/lifthelper/data/repository/CalibrationRepositoryImpl.kt
   Create: app/src/main/java/com/lifthelper/data/repository/SettingsRepositoryImpl.kt
   ```

   **Implementation Requirements:**
   - Use DAOs for data access
   - Use mappers for entity conversion
   - Wrap DAO calls in `runCatching { }` and return `Result<T>`
   - Log errors appropriately
   - Keep implementations in `data/repository` package

5. **Update Dependency Injection**
   ```
   Create: app/src/main/java/com/lifthelper/di/RepositoryModule.kt
   ```

   **DI Setup:**
   ```kotlin
   @Module
   @InstallIn(SingletonComponent::class)
   abstract class RepositoryModule {

       @Binds
       @Singleton
       abstract fun bindWorkoutRepository(
           impl: WorkoutRepositoryImpl
       ): WorkoutRepository

       @Binds
       @Singleton
       abstract fun bindCalibrationRepository(
           impl: CalibrationRepositoryImpl
       ): CalibrationRepository

       @Binds
       @Singleton
       abstract fun bindSettingsRepository(
           impl: SettingsRepositoryImpl
       ): SettingsRepository
   }
   ```

6. **Update ViewModels**
   - Replace all DAO dependencies with Repository interfaces
   - Update all data access calls to use repository methods
   - Handle `Result<T>` returns appropriately

   **Files to Update:**
   - `ui/screens/home/HomeViewModel.kt`
   - `ui/screens/workout/WorkoutViewModel.kt`
   - `ui/screens/history/HistoryScreen.kt` (if using ViewModel)
   - Any other ViewModels accessing DAOs

**Acceptance Criteria:**
- [ ] All repository interfaces created in `domain/repository`
- [ ] All repository implementations created in `data/repository`
- [ ] Mappers created for entity/domain conversion
- [ ] DI module binds interfaces to implementations
- [ ] ViewModels depend only on repository interfaces
- [ ] No ViewModel directly imports or uses DAO classes
- [ ] All data operations return `Result<T>` types
- [ ] Code compiles successfully
- [ ] Existing functionality works unchanged

---

### Task 1.2: Extract Use Case Layer (6 hours)

**Objective:** Move business logic from ViewModels to dedicated Use Case classes following Single Responsibility Principle.

**Steps:**

1. **Create Use Case Base Classes**
   ```
   Create: app/src/main/java/com/lifthelper/domain/usecase/UseCase.kt
   ```

   ```kotlin
   package com.lifthelper.domain.usecase

   abstract class UseCase<in P, out R> {
       abstract suspend operator fun invoke(params: P): R
   }

   abstract class FlowUseCase<in P, out R> {
       abstract operator fun invoke(params: P): Flow<R>
   }
   ```

2. **Extract Workout Processing Use Case**
   ```
   Create: app/src/main/java/com/lifthelper/domain/usecase/ProcessWorkoutFrameUseCase.kt
   ```

   **Extract from:** `ui/screens/workout/WorkoutViewModel.processFrame()`

   **Responsibilities:**
   - Detect pose from camera frame
   - Calculate velocity metrics
   - Detect movement phase
   - Count reps (if recording)
   - Assess calibration quality
   - Return structured result

   **Dependencies to inject:**
   - `PoseDetector` (make interface first)
   - `VelocityCalculator` (make interface first)
   - `MovementPhaseDetector` (make interface first)
   - `PositionCalculator` (make interface first)

   **Result Type:**
   ```kotlin
   sealed class FrameProcessingResult {
       data class Success(
           val poseResult: PoseResult,
           val velocityData: VelocityData,
           val phaseResult: PhaseResult?,
           val calibrationQuality: CalibrationQuality
       ) : FrameProcessingResult()

       data class Error(val message: String, val cause: Throwable?) : FrameProcessingResult()
       object NoPoseDetected : FrameProcessingResult()
   }

   data class PhaseResult(
       val phase: MovementPhase,
       val repCounted: Boolean
   )
   ```

3. **Extract Rep Counting Use Case**
   ```
   Create: app/src/main/java/com/lifthelper/domain/usecase/CountRepsUseCase.kt
   ```

   **Extract from:** WorkoutViewModel rep counting logic (lines 73-116)

   **Input:**
   ```kotlin
   data class RepCountingParams(
       val poseResult: PoseResult,
       val calibrationData: CalibrationData,
       val exerciseType: ExerciseType,
       val currentPhase: MovementPhase
   )
   ```

   **Output:**
   ```kotlin
   data class RepCountingResult(
       val newPhase: MovementPhase,
       val repCompleted: Boolean,
       val totalReps: Int,
       val debugInfo: String
   )
   ```

4. **Extract Save Workout Use Case**
   ```
   Create: app/src/main/java/com/lifthelper/domain/usecase/SaveWorkoutUseCase.kt
   ```

   **Responsibilities:**
   - Validate workout data
   - Save to repository
   - Handle errors
   - Return success/failure result

   **Input:**
   ```kotlin
   data class WorkoutData(
       val exerciseType: ExerciseType,
       val sets: List<SetData>,
       val date: Date,
       val duration: Long
   )
   ```

5. **Extract Load Calibration Use Case**
   ```
   Create: app/src/main/java/com/lifthelper/domain/usecase/LoadCalibrationUseCase.kt
   ```

   **Responsibilities:**
   - Load calibration from repository
   - Validate calibration data
   - Return calibration or default values
   - Emit updates via Flow

6. **Create Abstraction Interfaces for Infrastructure**
   ```
   Create: app/src/main/java/com/lifthelper/domain/vision/IPoseDetector.kt
   Create: app/src/main/java/com/lifthelper/domain/vision/IVelocityCalculator.kt
   Create: app/src/main/java/com/lifthelper/domain/vision/IPositionCalculator.kt
   Create: app/src/main/java/com/lifthelper/domain/vision/IPhaseDetector.kt
   ```

   **Then:**
   - Make existing implementations (`PoseDetector`, etc.) implement these interfaces
   - Update DI to bind interfaces to implementations
   - Update use cases to depend on interfaces

7. **Simplify WorkoutViewModel**
   ```
   Refactor: ui/screens/workout/WorkoutViewModel.kt
   ```

   **New structure:**
   ```kotlin
   @HiltViewModel
   class WorkoutViewModel @Inject constructor(
       private val processFrameUseCase: ProcessWorkoutFrameUseCase,
       private val saveWorkoutUseCase: SaveWorkoutUseCase,
       private val loadCalibrationUseCase: LoadCalibrationUseCase
   ) : ViewModel() {

       private val _uiState = MutableStateFlow(WorkoutState())
       val uiState: StateFlow<WorkoutState> = _uiState.asStateFlow()

       init {
           loadCalibration()
       }

       fun processFrame(imageProxy: ImageProxy) {
           viewModelScope.launch {
               val result = processFrameUseCase(
                   ProcessFrameParams(
                       imageProxy = imageProxy,
                       timestamp = System.currentTimeMillis(),
                       calibrationData = _uiState.value.calibrationData,
                       exerciseType = _uiState.value.selectedExercise,
                       isRecording = _uiState.value.isRecording,
                       currentPhase = _uiState.value.currentPhase
                   )
               )

               handleFrameResult(result)
           }
       }

       private fun handleFrameResult(result: FrameProcessingResult) {
           when (result) {
               is FrameProcessingResult.Success -> updateUIWithSuccess(result)
               is FrameProcessingResult.Error -> showError(result.message)
               is FrameProcessingResult.NoPoseDetected -> incrementMissedFrames()
           }
       }

       // Other simple methods...
   }
   ```

   **Target:** Reduce ViewModel from 189 lines to ~100 lines, zero business logic

8. **Update DI Module**
   ```
   Create: app/src/main/java/com/lifthelper/di/UseCaseModule.kt
   ```

   **Provide all use cases:**
   ```kotlin
   @Module
   @InstallIn(ViewModelComponent::class)
   object UseCaseModule {

       @Provides
       fun provideProcessFrameUseCase(
           poseDetector: IPoseDetector,
           velocityCalculator: IVelocityCalculator,
           phaseDetector: IPhaseDetector,
           positionCalculator: IPositionCalculator
       ): ProcessWorkoutFrameUseCase {
           return ProcessWorkoutFrameUseCase(
               poseDetector,
               velocityCalculator,
               phaseDetector,
               positionCalculator
           )
       }

       // Other use cases...
   }
   ```

**Acceptance Criteria:**
- [ ] All use cases created in `domain/usecase` package
- [ ] Use cases are pure Kotlin (no Android dependencies)
- [ ] Each use case has single, clear responsibility
- [ ] Use cases have proper error handling
- [ ] Infrastructure abstractions (IPoseDetector, etc.) created
- [ ] Existing implementations implement new interfaces
- [ ] WorkoutViewModel simplified to <100 lines
- [ ] WorkoutViewModel has zero business logic (only UI state management)
- [ ] All use cases properly injected via DI
- [ ] Code compiles successfully
- [ ] All existing functionality works unchanged
- [ ] No performance regression

---

## Phase 2: Core Refactoring - Domain Model Cleanup (Week 2)

### Priority: P0 - CRITICAL
### Estimated Effort: 12 hours
### Dependencies: Phase 1 complete

### Task 2.1: Refactor AnthropometricData.fromPoseResult() (8 hours)

**Objective:** Break down the 451-line god method into focused, testable classes following Single Responsibility Principle.

**Steps:**

1. **Create Validation Layer**
   ```
   Create: app/src/main/java/com/lifthelper/domain/validation/PoseValidator.kt
   ```

   **Extract:** Landmark visibility validation logic (lines 115-162 of AnthropometricData.kt)

   **Interface:**
   ```kotlin
   class PoseValidator {
       fun validateCriticalLandmarks(landmarks: List<PoseKeypoint>): ValidationResult
       fun validateHeadLandmarks(landmarks: List<PoseKeypoint>): Boolean
       fun validateAnklesVisible(landmarks: List<PoseKeypoint>): Boolean
   }

   sealed class ValidationResult {
       object Valid : ValidationResult()
       data class Invalid(val reason: String, val missingLandmarks: List<Int>) : ValidationResult()
   }
   ```

   **Responsibilities:**
   - Check if critical landmarks are visible (shoulders, hips, knees)
   - Check if head landmarks are visible (nose or ears)
   - Return detailed validation results

2. **Create Coordinate Calculator**
   ```
   Create: app/src/main/java/com/lifthelper/domain/calculator/CoordinateCalculator.kt
   ```

   **Extract:** Aspect ratio, coordinate normalization, distance calculations

   **Interface:**
   ```kotlin
   class CoordinateCalculator {
       fun calculateAspectRatio(imageWidth: Int, imageHeight: Int): AspectRatioResult
       fun normalizeYAxis(landmarks: List<PoseKeypoint>): List<PoseKeypoint>
       fun detectYAxisInversion(landmarks: List<PoseKeypoint>): Boolean
       fun calculateDistance(
           p1: PoseKeypoint,
           p2: PoseKeypoint,
           aspectRatio: Float,
           isPortrait: Boolean
       ): Float
       fun scaleForAspectRatio(
           distance: Float,
           aspectRatio: Float,
           isPortrait: Boolean,
           isHorizontal: Boolean
       ): Float
   }

   data class AspectRatioResult(
       val ratio: Float,
       val isPortrait: Boolean
   )
   ```

   **Responsibilities:**
   - Calculate image aspect ratios
   - Detect and correct Y-axis inversions
   - Calculate Euclidean distances with aspect ratio correction
   - Scale measurements appropriately

3. **Create Body Segment Extractor**
   ```
   Create: app/src/main/java/com/lifthelper/domain/extractor/BodySegmentExtractor.kt
   ```

   **Extract:** Segment height and width calculations (lines 210-360 of AnthropometricData.kt)

   **Interface:**
   ```kotlin
   class BodySegmentExtractor(
       private val coordinateCalculator: CoordinateCalculator
   ) {
       fun extractHeights(
           landmarks: List<PoseKeypoint>,
           aspectRatio: Float,
           isPortrait: Boolean
       ): BodyHeights

       fun extractWidths(
           landmarks: List<PoseKeypoint>,
           aspectRatio: Float,
           isPortrait: Boolean
       ): BodyWidths

       fun extractLimbLengths(
           landmarks: List<PoseKeypoint>,
           aspectRatio: Float,
           isPortrait: Boolean
       ): LimbLengths
   }

   data class BodyHeights(
       val headTop: Float,
       val headFace: Float,
       val shoulders: Float,
       val hips: Float,
       val knees: Float,
       val ankles: Float?,
       val totalHeight: Float
   )

   data class BodyWidths(
       val shoulders: Float,
       val hips: Float,
       val knees: Float
   )

   data class LimbLengths(
       val upperArm: Float,
       val forearm: Float
   )
   ```

   **Responsibilities:**
   - Extract body part positions from landmarks
   - Calculate segment heights (head, torso, legs)
   - Calculate body widths (shoulders, hips, knees)
   - Calculate limb lengths (arms)
   - Handle missing landmarks gracefully

4. **Create Measurement Validator**
   ```
   Create: app/src/main/java/com/lifthelper/domain/validation/MeasurementValidator.kt
   ```

   **Extract:** Sanity checks and validation (lines 362-390, 415-474 of AnthropometricData.kt)

   **Interface:**
   ```kotlin
   class MeasurementValidator {
       fun validateHipWidth(width: Float, totalHeight: Float, userHeight: Float): ValidationResult
       fun validateKneeWidth(width: Float, totalHeight: Float, userHeight: Float): ValidationResult
       fun validateUpperArmLength(length: Float, totalHeight: Float, userHeight: Float): ValidationResult
       fun validateForearmLength(length: Float, totalHeight: Float, userHeight: Float): ValidationResult
       fun validateBodySizeInFrame(totalHeightNorm: Float): FrameSizeResult
   }

   data class ValidationResult(
       val isValid: Boolean,
       val validatedValue: Float,
       val issue: MeasurementIssue?
   )

   data class FrameSizeResult(
       val percentage: Int,
       val pixels: Int,
       val quality: FrameSizeQuality
   )

   enum class FrameSizeQuality {
       TOO_SMALL,  // <20%
       ACCEPTABLE, // 20-50%
       OPTIMAL     // >50%
   }
   ```

   **Responsibilities:**
   - Validate measurements are within realistic bounds
   - Create MeasurementIssue objects for problems
   - Provide fallback values when needed
   - Assess body size in frame

5. **Create Anthropometric Data Builder**
   ```
   Create: app/src/main/java/com/lifthelper/domain/builder/AnthropometricDataBuilder.kt
   ```

   **Orchestrates all the above components**

   **Interface:**
   ```kotlin
   class AnthropometricDataBuilder @Inject constructor(
       private val poseValidator: PoseValidator,
       private val coordinateCalculator: CoordinateCalculator,
       private val segmentExtractor: BodySegmentExtractor,
       private val measurementValidator: MeasurementValidator,
       private val alignmentChecker: CameraAlignmentChecker
   ) {
       fun build(
           poseResult: PoseResult,
           userHeight: Float,
           userWeight: Float? = null,
           manualShoulderWidth: Float? = null,
           isFrontCamera: Boolean = false
       ): Result<AnthropometricData>
   }
   ```

   **Responsibilities:**
   - Coordinate the extraction pipeline
   - Validate at each step
   - Collect measurement issues
   - Assess overall calibration quality
   - Build final AnthropometricData object
   - Return Result<T> for error handling

6. **Simplify AnthropometricData**
   ```
   Refactor: app/src/main/java/com/lifthelper/domain/model/AnthropometricData.kt
   ```

   **Remove:**
   - Entire `fromPoseResult()` method (move to builder)
   - All Android logging statements
   - All complex calculation logic

   **Keep:**
   - Data class with all fields
   - Companion object with `getDefaultRatios()`
   - Helper methods (getHeadHeight, getSegmentMass, etc.)
   - Call builder from companion object for backward compatibility:

   ```kotlin
   companion object {
       fun fromPoseResult(
           poseResult: PoseResult,
           userHeight: Float,
           userWeight: Float? = null,
           manualShoulderWidth: Float? = null,
           isFrontCamera: Boolean = false
       ): AnthropometricData? {
           // Inject builder here (or use static instance)
           val builder = createBuilder()  // DI in real code
           return builder.build(
               poseResult,
               userHeight,
               userWeight,
               manualShoulderWidth,
               isFrontCamera
           ).getOrNull()
       }
   }
   ```

   **Target:** Reduce from 619 lines to ~150 lines (data + helpers only)

7. **Remove Android Dependencies from Domain**
   ```
   Create: app/src/main/java/com/lifthelper/domain/logging/Logger.kt
   ```

   **Abstract logging:**
   ```kotlin
   interface Logger {
       fun d(tag: String, message: String)
       fun i(tag: String, message: String)
       fun w(tag: String, message: String)
       fun e(tag: String, message: String, throwable: Throwable? = null)
   }
   ```

   **Implement:**
   ```
   Create: app/src/main/java/com/lifthelper/infrastructure/logging/AndroidLogger.kt
   ```

   ```kotlin
   class AndroidLogger @Inject constructor() : Logger {
       override fun d(tag: String, message: String) {
           android.util.Log.d(tag, message)
       }
       // ... other methods
   }
   ```

   **Update all domain classes:**
   - Replace `android.util.Log.d(...)` with `logger.d(...)`
   - Inject Logger interface
   - No direct Android dependencies in domain/

8. **Update DI**
   ```
   Update: app/src/main/java/com/lifthelper/di/AppModule.kt
   ```

   **Add bindings:**
   ```kotlin
   @Binds
   @Singleton
   abstract fun bindLogger(impl: AndroidLogger): Logger

   @Provides
   @Singleton
   fun provideAnthropometricDataBuilder(
       poseValidator: PoseValidator,
       coordinateCalculator: CoordinateCalculator,
       segmentExtractor: BodySegmentExtractor,
       measurementValidator: MeasurementValidator,
       alignmentChecker: CameraAlignmentChecker
   ): AnthropometricDataBuilder {
       return AnthropometricDataBuilder(...)
   }
   ```

9. **Add Unit Tests**
   ```
   Create: app/src/test/java/com/lifthelper/domain/validation/PoseValidatorTest.kt
   Create: app/src/test/java/com/lifthelper/domain/calculator/CoordinateCalculatorTest.kt
   Create: app/src/test/java/com/lifthelper/domain/extractor/BodySegmentExtractorTest.kt
   Create: app/src/test/java/com/lifthelper/domain/validation/MeasurementValidatorTest.kt
   Create: app/src/test/java/com/lifthelper/domain/builder/AnthropometricDataBuilderTest.kt
   ```

   **Test each class in isolation with mock data**

   **Coverage target:** >80% for all new classes

**Acceptance Criteria:**
- [ ] PoseValidator created and tested
- [ ] CoordinateCalculator created and tested
- [ ] BodySegmentExtractor created and tested
- [ ] MeasurementValidator created and tested
- [ ] AnthropometricDataBuilder created and tested
- [ ] AnthropometricData.kt reduced to <200 lines
- [ ] No Android dependencies in domain layer
- [ ] Logger interface created and implemented
- [ ] All classes have single, clear responsibility
- [ ] All classes are testable in isolation
- [ ] Unit tests written with >80% coverage
- [ ] Code compiles successfully
- [ ] Calibration flow works identically to before
- [ ] No functionality regression

---

### Task 2.2: Add Comprehensive Error Handling (4 hours)

**Objective:** Replace scattered try-catch blocks with structured error handling strategy.

**Steps:**

1. **Define Error Types**
   ```
   Create: app/src/main/java/com/lifthelper/domain/error/LiftHelperError.kt
   ```

   ```kotlin
   sealed class LiftHelperError : Exception() {
       data class CameraError(
           override val message: String,
           val errorCode: CameraErrorCode
       ) : LiftHelperError()

       data class PoseDetectionError(
           override val message: String,
           val errorCode: PoseErrorCode
       ) : LiftHelperError()

       data class CalibrationError(
           override val message: String,
           val errorCode: CalibrationErrorCode
       ) : LiftHelperError()

       data class DatabaseError(
           override val message: String,
           override val cause: Throwable?
       ) : LiftHelperError()

       data class ValidationError(
           val field: String,
           override val message: String
       ) : LiftHelperError()

       data class PermissionError(
           val permission: String,
           override val message: String
       ) : LiftHelperError()
   }

   enum class CameraErrorCode {
       PERMISSION_DENIED,
       CAMERA_NOT_AVAILABLE,
       INITIALIZATION_FAILED,
       FOCUS_FAILED
   }

   enum class PoseErrorCode {
       MODEL_NOT_LOADED,
       PROCESSING_FAILED,
       NO_POSE_DETECTED,
       LOW_CONFIDENCE
   }

   enum class CalibrationErrorCode {
       INVALID_POSE,
       BODY_NOT_VISIBLE,
       POOR_ALIGNMENT,
       VALIDATION_FAILED
   }
   ```

2. **Create Result Extensions**
   ```
   Create: app/src/main/java/com/lifthelper/domain/util/ResultExt.kt
   ```

   ```kotlin
   fun <T> Result<T>.mapError(
       transform: (Throwable) -> LiftHelperError
   ): Result<T> {
       return when {
           isSuccess -> this
           else -> Result.failure(transform(exceptionOrNull()!!))
       }
   }

   inline fun <T, R> Result<T>.flatMap(
       transform: (T) -> Result<R>
   ): Result<R> {
       return when {
           isSuccess -> transform(getOrThrow())
           else -> Result.failure(exceptionOrNull()!!)
       }
   }

   fun <T> Result<T>.onError(
       action: (LiftHelperError) -> Unit
   ): Result<T> {
       exceptionOrNull()?.let { error ->
           if (error is LiftHelperError) {
               action(error)
           }
       }
       return this
   }
   ```

3. **Update Repository Error Handling**
   ```
   Refactor: app/src/main/java/com/lifthelper/data/repository/*RepositoryImpl.kt
   ```

   **Pattern:**
   ```kotlin
   override suspend fun saveWorkout(workout: Workout): Result<Long> = runCatching {
       validateWorkout(workout)
       val entity = mapper.toEntity(workout)
       workoutDao.insertWorkout(entity)
   }.mapError { error ->
       when (error) {
           is SQLException -> DatabaseError(
               "Failed to save workout",
               error
           )
           is ValidationError -> error
           else -> DatabaseError(
               "Unexpected error saving workout",
               error
           )
       }
   }.onError { error ->
       logger.e("WorkoutRepository", "Save failed: ${error.message}", error)
   }
   ```

4. **Update Use Case Error Handling**
   ```
   Refactor: app/src/main/java/com/lifthelper/domain/usecase/*UseCase.kt
   ```

   **Pattern:**
   ```kotlin
   override suspend fun invoke(params: ProcessFrameParams): FrameProcessingResult {
       return try {
           val poseResult = poseDetector.detectPose(
               params.imageProxy,
               params.timestamp
           ) ?: return FrameProcessingResult.NoPoseDetected

           // ... processing logic

           FrameProcessingResult.Success(...)
       } catch (e: PoseDetectionError) {
           logger.e(TAG, "Pose detection failed", e)
           FrameProcessingResult.Error(e.message ?: "Unknown error", e)
       } catch (e: Exception) {
           logger.e(TAG, "Unexpected error", e)
           FrameProcessingResult.Error("Unexpected error processing frame", e)
       }
   }
   ```

5. **Add Error State to UI State**
   ```
   Update: app/src/main/java/com/lifthelper/ui/screens/*/State.kt
   ```

   **Add to all UI states:**
   ```kotlin
   data class WorkoutState(
       // ... existing fields
       val error: UiError? = null,
       val isLoading: Boolean = false
   )

   sealed class UiError {
       data class Message(val text: String) : UiError()
       data class Recoverable(
           val text: String,
           val action: ErrorAction
       ) : UiError()
   }

   data class ErrorAction(
       val label: String,
       val onClick: () -> Unit
   )
   ```

6. **Update ViewModels to Handle Errors**
   ```
   Refactor: app/src/main/java/com/lifthelper/ui/screens/*/*.ViewModel.kt
   ```

   **Pattern:**
   ```kotlin
   fun saveWorkout() {
       viewModelScope.launch {
           _uiState.update { it.copy(isLoading = true, error = null) }

           val result = saveWorkoutUseCase(currentWorkout)

           _uiState.update {
               when {
                   result.isSuccess -> it.copy(
                       isLoading = false,
                       savedWorkoutId = result.getOrNull()
                   )
                   else -> {
                       val error = result.exceptionOrNull()
                       it.copy(
                           isLoading = false,
                           error = error?.toUiError()
                       )
                   }
               }
           }
       }
   }

   private fun Throwable.toUiError(): UiError {
       return when (this) {
           is DatabaseError -> UiError.Message(
               "Failed to save workout. Please try again."
           )
           is ValidationError -> UiError.Message(
               "Invalid data: $message"
           )
           else -> UiError.Message(
               "An unexpected error occurred"
           )
       }
   }
   ```

7. **Display Errors in UI**
   ```
   Refactor: app/src/main/java/com/lifthelper/ui/screens/*/*.Screen.kt
   ```

   **Add error handling:**
   ```kotlin
   @Composable
   fun WorkoutScreen(viewModel: WorkoutViewModel) {
       val uiState by viewModel.uiState.collectAsState()
       val snackbarHostState = remember { SnackbarHostState() }

       LaunchedEffect(uiState.error) {
           uiState.error?.let { error ->
               when (error) {
                   is UiError.Message -> {
                       snackbarHostState.showSnackbar(
                           message = error.text,
                           duration = SnackbarDuration.Short
                       )
                       viewModel.clearError()
                   }
                   is UiError.Recoverable -> {
                       val result = snackbarHostState.showSnackbar(
                           message = error.text,
                           actionLabel = error.action.label,
                           duration = SnackbarDuration.Long
                       )
                       if (result == SnackbarResult.ActionPerformed) {
                           error.action.onClick()
                       }
                       viewModel.clearError()
                   }
               }
           }
       }

       Scaffold(
           snackbarHost = { SnackbarHost(snackbarHostState) }
       ) { padding ->
           // ... screen content
       }
   }
   ```

8. **Update Infrastructure Classes**
   ```
   Refactor: app/src/main/java/com/lifthelper/vision/pose/PoseDetector.kt
   Refactor: app/src/main/java/com/lifthelper/vision/camera/CameraManager.kt
   ```

   **Throw specific errors:**
   ```kotlin
   class PoseDetector {
       fun detectPose(...): PoseResult? {
           if (poseLandmarker == null) {
               throw PoseDetectionError(
                   "Pose detector not initialized",
                   PoseErrorCode.MODEL_NOT_LOADED
               )
           }

           try {
               // ... detection logic
           } catch (e: Exception) {
               throw PoseDetectionError(
                   "Pose detection failed: ${e.message}",
                   PoseErrorCode.PROCESSING_FAILED
               )
           }
       }
   }
   ```

**Acceptance Criteria:**
- [ ] LiftHelperError sealed class created with all error types
- [ ] Result extensions created and tested
- [ ] All repositories use Result<T> with proper error mapping
- [ ] All use cases have try-catch with specific error types
- [ ] UI states include error fields
- [ ] ViewModels handle errors and update UI state
- [ ] All screens display errors to user (Snackbar or Dialog)
- [ ] No silent failures in the app
- [ ] Errors are logged appropriately
- [ ] User-friendly error messages
- [ ] Code compiles successfully
- [ ] All error scenarios tested manually

---

## Phase 3: Code Quality Improvements (Week 3)

### Priority: P1 - HIGH
### Estimated Effort: 7 hours
### Dependencies: Phase 1 & 2 complete

### Task 3.1: Implement Value Objects (2 hours)

**Objective:** Replace primitive types with type-safe value objects to prevent bugs and improve code clarity.

**Steps:**

1. **Create Value Object Base**
   ```
   Create: app/src/main/java/com/lifthelper/domain/value/ValueObjects.kt
   ```

   ```kotlin
   @JvmInline
   value class Kilograms(val value: Float) {
       init {
           require(value >= 0) { "Weight cannot be negative: $value" }
       }

       operator fun plus(other: Kilograms) = Kilograms(value + other.value)
       operator fun minus(other: Kilograms) = Kilograms(value - other.value)
       operator fun times(scalar: Float) = Kilograms(value * scalar)
       operator fun div(scalar: Float) = Kilograms(value / scalar)
   }

   @JvmInline
   value class Meters(val value: Float) {
       init {
           require(value >= 0) { "Distance cannot be negative: $value" }
       }

       operator fun plus(other: Meters) = Meters(value + other.value)
       operator fun minus(other: Meters) = Meters(value - other.value)
       operator fun times(scalar: Float) = Meters(value * scalar)
       operator fun div(scalar: Float) = Meters(value / scalar)
   }

   @JvmInline
   value class MetersPerSecond(val value: Float) {
       fun abs() = MetersPerSecond(kotlin.math.abs(value))
   }

   @JvmInline
   value class Watts(val value: Float) {
       init {
           require(value >= 0) { "Power cannot be negative: $value" }
       }
   }

   @JvmInline
   value class Joules(val value: Float) {
       init {
           require(value >= 0) { "Energy cannot be negative: $value" }
       }
   }

   @JvmInline
   value class RepCount(val value: Int) {
       init {
           require(value >= 0) { "Rep count cannot be negative: $value" }
       }

       operator fun inc() = RepCount(value + 1)
       operator fun dec() = RepCount(value - 1)
   }

   @JvmInline
   value class Percentage(val value: Float) {
       init {
           require(value in 0f..100f) { "Percentage must be 0-100: $value" }
       }
   }
   ```

2. **Update Domain Models**
   ```
   Refactor: app/src/main/java/com/lifthelper/domain/model/*.kt
   ```

   **Replace primitive types:**
   ```kotlin
   // Before
   data class VelocityData(
       val velocity: Float,
       val power: Float
   )

   // After
   data class VelocityData(
       val velocity: MetersPerSecond,
       val power: Watts
   )

   // Before
   data class WorkoutState(
       val weight: Float,
       val repCount: Int
   )

   // After
   data class WorkoutState(
       val weight: Kilograms,
       val repCount: RepCount
   )
   ```

3. **Update Calculators**
   ```
   Refactor: app/src/main/java/com/lifthelper/vision/calculator/*.kt
   ```

   **Use value objects in calculations:**
   ```kotlin
   // Before
   fun calculatePower(weight: Float, velocity: Float): Float {
       return weight * 9.81f * abs(velocity)
   }

   // After
   fun calculatePower(weight: Kilograms, velocity: MetersPerSecond): Watts {
       val force = weight.value * 9.81f
       return Watts(force * velocity.abs().value)
   }
   ```

4. **Update UI State**
   ```
   Refactor: app/src/main/java/com/lifthelper/ui/screens/*/State.kt
   ```

   **Convert for display:**
   ```kotlin
   @Composable
   fun WeightDisplay(weight: Kilograms) {
       Text("Weight: ${weight.value}kg")
   }

   @Composable
   fun VelocityDisplay(velocity: MetersPerSecond) {
       Text("Velocity: ${"%.2f".format(velocity.value)} m/s")
   }
   ```

**Acceptance Criteria:**
- [ ] All value object classes created
- [ ] Domain models use value objects instead of primitives
- [ ] Calculators use value objects
- [ ] UI state uses value objects
- [ ] Validation happens at construction time
- [ ] Cannot accidentally mix units (compile-time safety)
- [ ] Code compiles successfully
- [ ] All tests updated and passing
- [ ] No functionality regression

---

### Task 3.2: Extract Exercise Strategy Pattern (5 hours)

**Objective:** Replace hardcoded exercise logic with extensible strategy pattern to enable easy addition of new exercises.

**Steps:**

1. **Define Strategy Interface**
   ```
   Create: app/src/main/java/com/lifthelper/domain/exercise/ExerciseStrategy.kt
   ```

   ```kotlin
   interface ExerciseStrategy {
       val exerciseType: ExerciseType

       fun detectPhase(
           poseResult: PoseResult,
           calibrationData: CalibrationData,
           currentPhase: MovementPhase
       ): PhaseTransitionResult

       fun calculateMetrics(
           poseResult: PoseResult,
           velocityData: VelocityData,
           weight: Kilograms
       ): ExerciseMetrics

       fun validateForm(
           poseResult: PoseResult,
           calibrationData: CalibrationData
       ): FormValidationResult

       fun getRequiredLandmarks(): List<Int>

       fun getCriteria(): ExerciseCriteria
   }

   data class PhaseTransitionResult(
       val newPhase: MovementPhase,
       val repCompleted: Boolean,
       val debugInfo: String
   )

   data class ExerciseMetrics(
       val displacement: Meters,
       val peakVelocity: MetersPerSecond,
       val avgPower: Watts,
       val peakPower: Watts,
       val work: Joules
   )

   data class FormValidationResult(
       val isValid: Boolean,
       val issues: List<FormIssue>
   )

   data class FormIssue(
       val severity: Severity,
       val message: String,
       val suggestion: String
   )

   enum class Severity {
       INFO,
       WARNING,
       CRITICAL
   }

   data class ExerciseCriteria(
       val minDescentDepth: Meters,
       val returnTolerance: Meters,
       val minVelocity: MetersPerSecond,
       val targetBodyParts: List<Int>
   )
   ```

2. **Implement Back Squat Strategy**
   ```
   Create: app/src/main/java/com/lifthelper/domain/exercise/strategy/BackSquatStrategy.kt
   ```

   **Extract from:** SquatPhaseDetector

   ```kotlin
   class BackSquatStrategy @Inject constructor(
       private val positionCalculator: IPositionCalculator,
       private val logger: Logger
   ) : ExerciseStrategy {

       override val exerciseType = ExerciseType.BACK_SQUAT

       private val criteria = ExerciseCriteria(
           minDescentDepth = Meters(0.30f),  // 30cm
           returnTolerance = Meters(0.15f),  // 15cm
           minVelocity = MetersPerSecond(0.1f),
           targetBodyParts = listOf(
               PoseResult.HEAD,
               PoseResult.LEFT_SHOULDER,
               PoseResult.RIGHT_SHOULDER,
               PoseResult.LEFT_HIP,
               PoseResult.RIGHT_HIP,
               PoseResult.LEFT_KNEE,
               PoseResult.RIGHT_KNEE
           )
       )

       override fun detectPhase(
           poseResult: PoseResult,
           calibrationData: CalibrationData,
           currentPhase: MovementPhase
       ): PhaseTransitionResult {
           // Extract logic from SquatPhaseDetector
           // Use state machine pattern
       }

       override fun calculateMetrics(
           poseResult: PoseResult,
           velocityData: VelocityData,
           weight: Kilograms
       ): ExerciseMetrics {
           // Calculate squat-specific metrics
       }

       override fun validateForm(
           poseResult: PoseResult,
           calibrationData: CalibrationData
       ): FormValidationResult {
           val issues = mutableListOf<FormIssue>()

           // Check squat depth
           val depth = calculateSquatDepth(poseResult, calibrationData)
           if (depth < criteria.minDescentDepth) {
               issues.add(FormIssue(
                   severity = Severity.WARNING,
                   message = "Squat depth insufficient",
                   suggestion = "Lower hips below knees"
               ))
           }

           // Check bar path
           // Check knee alignment
           // etc.

           return FormValidationResult(
               isValid = issues.none { it.severity == Severity.CRITICAL },
               issues = issues
           )
       }

       override fun getRequiredLandmarks() = criteria.targetBodyParts
       override fun getCriteria() = criteria
   }
   ```

3. **Implement Front Squat Strategy**
   ```
   Create: app/src/main/java/com/lifthelper/domain/exercise/strategy/FrontSquatStrategy.kt
   ```

   **Differences from back squat:**
   - Different bar position (shoulders vs upper back)
   - More upright torso required
   - Different form validation criteria

4. **Create Strategy Factory**
   ```
   Create: app/src/main/java/com/lifthelper/domain/exercise/ExerciseStrategyFactory.kt
   ```

   ```kotlin
   class ExerciseStrategyFactory @Inject constructor(
       private val backSquatStrategy: BackSquatStrategy,
       private val frontSquatStrategy: FrontSquatStrategy
       // Add more as implemented
   ) {
       fun getStrategy(exerciseType: ExerciseType): ExerciseStrategy {
           return when (exerciseType) {
               ExerciseType.BACK_SQUAT -> backSquatStrategy
               ExerciseType.FRONT_SQUAT -> frontSquatStrategy
               else -> throw UnsupportedExerciseException(
                   "Exercise $exerciseType not yet implemented"
               )
           }
       }

       fun getAllStrategies(): List<ExerciseStrategy> {
           return listOf(backSquatStrategy, frontSquatStrategy)
       }
   }

   class UnsupportedExerciseException(message: String) : Exception(message)
   ```

5. **Update Use Cases**
   ```
   Refactor: app/src/main/java/com/lifthelper/domain/usecase/ProcessWorkoutFrameUseCase.kt
   ```

   **Use strategy:**
   ```kotlin
   class ProcessWorkoutFrameUseCase @Inject constructor(
       private val poseDetector: IPoseDetector,
       private val velocityCalculator: IVelocityCalculator,
       private val strategyFactory: ExerciseStrategyFactory
   ) {
       suspend operator fun invoke(params: ProcessFrameParams): FrameProcessingResult {
           val poseResult = poseDetector.detectPose(...)
               ?: return FrameProcessingResult.NoPoseDetected

           val velocityData = velocityCalculator.calculate(...)

           val strategy = strategyFactory.getStrategy(params.exerciseType)

           val phaseResult = if (params.isRecording) {
               strategy.detectPhase(
                   poseResult,
                   params.calibrationData,
                   params.currentPhase
               )
           } else null

           val metrics = strategy.calculateMetrics(
               poseResult,
               velocityData,
               params.weight
           )

           val formValidation = strategy.validateForm(
               poseResult,
               params.calibrationData
           )

           return FrameProcessingResult.Success(
               poseResult = poseResult,
               velocityData = velocityData,
               phaseResult = phaseResult,
               metrics = metrics,
               formValidation = formValidation
           )
       }
   }
   ```

6. **Remove Old Phase Detectors**
   ```
   Delete: app/src/main/java/com/lifthelper/vision/tracking/SquatPhaseDetector.kt
   Delete: app/src/main/java/com/lifthelper/vision/tracking/MovementPhaseDetector.kt
   ```

   **Logic now in strategies**

7. **Update DI**
   ```
   Update: app/src/main/java/com/lifthelper/di/UseCaseModule.kt
   ```

   **Provide strategies:**
   ```kotlin
   @Provides
   @Singleton
   fun provideBackSquatStrategy(
       positionCalculator: IPositionCalculator,
       logger: Logger
   ): BackSquatStrategy {
       return BackSquatStrategy(positionCalculator, logger)
   }

   // Other strategies...

   @Provides
   @Singleton
   fun provideExerciseStrategyFactory(
       backSquat: BackSquatStrategy,
       frontSquat: FrontSquatStrategy
   ): ExerciseStrategyFactory {
       return ExerciseStrategyFactory(backSquat, frontSquat)
   }
   ```

**Acceptance Criteria:**
- [ ] ExerciseStrategy interface created
- [ ] BackSquatStrategy implemented
- [ ] FrontSquatStrategy implemented
- [ ] ExerciseStrategyFactory created
- [ ] Use cases updated to use strategies
- [ ] Old phase detector classes removed
- [ ] DI configured properly
- [ ] Can add new exercise by creating new strategy class
- [ ] No changes needed to ViewModels for new exercises
- [ ] Code compiles successfully
- [ ] All existing functionality works
- [ ] Form validation provides useful feedback

---

## Phase 4: Testing & Documentation (Week 4-5)

### Priority: P2 - MEDIUM
### Estimated Effort: 10 hours
### Dependencies: Phase 1, 2, 3 complete

### Task 4.1: Add Comprehensive Unit Tests (6 hours)

**Objective:** Achieve >70% test coverage for domain and data layers.

**Steps:**

1. **Test Value Objects**
   ```
   Create: app/src/test/java/com/lifthelper/domain/value/ValueObjectsTest.kt
   ```

   **Test:**
   - Valid construction
   - Invalid construction (negative values)
   - Arithmetic operations
   - Comparison operations

2. **Test Domain Models**
   ```
   Create: app/src/test/java/com/lifthelper/domain/model/*.kt
   ```

   **Test:**
   - Data class equality
   - Validation methods
   - Helper methods (getHeadHeight, getSegmentMass, etc.)

3. **Test Calculators**
   ```
   Create: app/src/test/java/com/lifthelper/domain/calculator/*.kt
   ```

   **Test:**
   - Biomechanics formulas (power, work, energy)
   - Edge cases (zero velocity, negative values)
   - Precision (compare to known values)

4. **Test Validators**
   ```
   Create: app/src/test/java/com/lifthelper/domain/validation/*.kt
   ```

   **Test:**
   - Valid pose detection
   - Invalid pose detection (missing landmarks)
   - Measurement validation (in range, out of range)
   - Edge cases

5. **Test Use Cases**
   ```
   Create: app/src/test/java/com/lifthelper/domain/usecase/*.kt
   ```

   **Test:**
   - Success scenarios
   - Error scenarios
   - Edge cases
   - Mock all dependencies

6. **Test Repositories**
   ```
   Create: app/src/test/java/com/lifthelper/data/repository/*.kt
   ```

   **Test:**
   - CRUD operations
   - Error handling
   - Mapping logic
   - Use in-memory database for integration tests

7. **Test Exercise Strategies**
   ```
   Create: app/src/test/java/com/lifthelper/domain/exercise/strategy/*.kt
   ```

   **Test:**
   - Phase transitions
   - Rep counting logic
   - Form validation
   - Metrics calculation

**Acceptance Criteria:**
- [ ] >70% code coverage for domain layer
- [ ] >70% code coverage for data layer
- [ ] All tests pass
- [ ] Tests are fast (<1s each)
- [ ] Tests are isolated (no shared state)
- [ ] Clear test names (should_X_when_Y pattern)
- [ ] Use test fixtures for common data

---

### Task 4.2: Add KDoc Documentation (4 hours)

**Objective:** Document all public API with KDoc comments.

**Steps:**

1. **Document Domain Models**
   ```
   Refactor: app/src/main/java/com/lifthelper/domain/model/*.kt
   ```

   **Add KDoc:**
   ```kotlin
   /**
    * Represents a single keypoint from pose detection.
    *
    * Coordinates are normalized to [0, 1] range where:
    * - x: 0 = left edge, 1 = right edge
    * - y: 0 = top edge, 1 = bottom edge
    * - z: depth relative to hips (negative = closer to camera)
    *
    * @property x Horizontal position (0-1)
    * @property y Vertical position (0-1)
    * @property z Depth position (arbitrary units)
    * @property visibility Confidence score (0-1)
    */
   data class PoseKeypoint(
       val x: Float,
       val y: Float,
       val z: Float,
       val visibility: Float
   )
   ```

2. **Document Use Cases**
   ```
   Refactor: app/src/main/java/com/lifthelper/domain/usecase/*.kt
   ```

   **Add KDoc:**
   ```kotlin
   /**
    * Processes a camera frame to extract pose data and calculate exercise metrics.
    *
    * This use case orchestrates:
    * 1. Pose detection from image
    * 2. Velocity calculation
    * 3. Movement phase detection (if recording)
    * 4. Calibration quality assessment
    *
    * @param params Frame processing parameters including image, calibration, and exercise type
    * @return [FrameProcessingResult] containing pose data, metrics, and any errors
    *
    * @throws PoseDetectionError if pose detection fails critically
    */
   class ProcessWorkoutFrameUseCase { ... }
   ```

3. **Document Repository Interfaces**
   ```
   Refactor: app/src/main/java/com/lifthelper/domain/repository/*.kt
   ```

4. **Document Exercise Strategies**
   ```
   Refactor: app/src/main/java/com/lifthelper/domain/exercise/*.kt
   ```

5. **Document Calculators**
   ```
   Refactor: app/src/main/java/com/lifthelper/domain/calculator/*.kt
   ```

**Acceptance Criteria:**
- [ ] All public classes documented
- [ ] All public methods documented
- [ ] All parameters documented with @param
- [ ] All return values documented with @return
- [ ] All exceptions documented with @throws
- [ ] Examples provided for complex APIs
- [ ] Documentation is accurate and helpful
- [ ] KDoc generates clean HTML documentation

---

## Additional Guidelines

### General Principles

1. **Test-Driven Development**
   - Write failing test first
   - Implement minimal code to pass
   - Refactor while keeping tests green

2. **Commit Frequently**
   - Commit after each completed task
   - Use descriptive commit messages:
     - `feat: implement WorkoutRepository interface`
     - `refactor: extract PoseValidator from AnthropometricData`
     - `test: add unit tests for BiomechanicsCalculator`
     - `docs: add KDoc to domain models`

3. **No Breaking Changes**
   - All refactorings must maintain existing functionality
   - Run app after each major change
   - Manually test critical flows (calibration, workout recording)

4. **Code Style**
   - Follow Kotlin coding conventions
   - Use meaningful variable names
   - Keep functions small (<20 lines)
   - Keep classes focused (Single Responsibility)

5. **Performance**
   - No performance regressions allowed
   - Profile critical paths if concerns arise
   - Keep frame processing under 33ms (30 FPS target)

### Error Handling Pattern

```kotlin
// Use cases
suspend fun invoke(params: Params): Result<Output> = runCatching {
    // Business logic
}.mapError { e ->
    when (e) {
        is SpecificException -> DomainError(...)
        else -> DomainError.Unknown(e)
    }
}

// Repositories
suspend fun save(data: Data): Result<Long> = runCatching {
    dao.insert(mapper.toEntity(data))
}.mapError { e ->
    when (e) {
        is SQLException -> DatabaseError(...)
        else -> DatabaseError.Unknown(e)
    }
}

// ViewModels
fun performAction() {
    viewModelScope.launch {
        _state.update { it.copy(isLoading = true) }

        useCase(params)
            .onSuccess { result ->
                _state.update { it.copy(data = result, isLoading = false) }
            }
            .onFailure { error ->
                _state.update { it.copy(error = error.toUiError(), isLoading = false) }
            }
    }
}
```

### Dependency Injection Pattern

```kotlin
// Interface in domain
interface Repository {
    suspend fun getData(): Result<Data>
}

// Implementation in data
class RepositoryImpl @Inject constructor(
    private val dao: Dao,
    private val mapper: Mapper
) : Repository {
    override suspend fun getData(): Result<Data> = ...
}

// DI Module
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindRepository(impl: RepositoryImpl): Repository
}

// Usage in ViewModel
@HiltViewModel
class MyViewModel @Inject constructor(
    private val repository: Repository  // Interface, not implementation
) : ViewModel()
```

### Testing Pattern

```kotlin
class CalculatorTest {
    private lateinit var calculator: Calculator

    @Before
    fun setup() {
        calculator = Calculator()
    }

    @Test
    fun `should calculate power correctly when given weight and velocity`() {
        // Given
        val weight = Kilograms(100f)
        val velocity = MetersPerSecond(1f)

        // When
        val power = calculator.calculatePower(weight, velocity)

        // Then
        assertEquals(Watts(981f), power)
    }

    @Test
    fun `should throw exception when given negative weight`() {
        assertThrows<IllegalArgumentException> {
            Kilograms(-10f)
        }
    }
}
```

## Success Criteria

### Phase 1 Complete When:
- [ ] All repository interfaces and implementations created
- [ ] All use cases extracted and implemented
- [ ] ViewModels simplified to <100 lines
- [ ] No business logic in ViewModels
- [ ] Code compiles and runs
- [ ] All functionality works as before

### Phase 2 Complete When:
- [ ] AnthropometricData reduced to <200 lines
- [ ] 5+ new focused classes created
- [ ] No Android dependencies in domain layer
- [ ] Comprehensive error handling implemented
- [ ] All errors displayed to user
- [ ] Code compiles and runs
- [ ] Calibration works identically to before

### Phase 3 Complete When:
- [ ] Value objects implemented throughout
- [ ] Exercise strategy pattern implemented
- [ ] Can add new exercise without modifying existing code
- [ ] Form validation provides useful feedback
- [ ] Code compiles and runs

### Phase 4 Complete When:
- [ ] >70% test coverage for domain/data layers
- [ ] All public API documented with KDoc
- [ ] Tests pass consistently
- [ ] Documentation is clear and helpful

### Overall Success Criteria:
- [ ] All phases completed
- [ ] Code quality significantly improved
- [ ] Architecture follows Clean Architecture principles
- [ ] SOLID principles adhered to
- [ ] No functionality regressions
- [ ] Performance maintained or improved
- [ ] Codebase is more maintainable
- [ ] iOS migration path is clear

---

## Reporting

After completing each task, provide a summary with:

1. **What was done:**
   - Files created
   - Files modified
   - Lines of code changed

2. **Key decisions:**
   - Design choices made
   - Trade-offs considered
   - Deviations from plan (if any)

3. **Testing:**
   - Tests added
   - Coverage achieved
   - Manual testing performed

4. **Issues encountered:**
   - Challenges faced
   - How they were resolved

5. **Next steps:**
   - What's ready to start
   - Any blockers

---

## Timeline

| Phase | Tasks | Duration | Milestone |
|-------|-------|----------|-----------|
| Phase 1 | Repository + Use Cases | Week 1 | Foundation complete |
| Phase 2 | AnthropometricData + Errors | Week 2 | Core refactoring done |
| Phase 3 | Value Objects + Strategy | Week 3 | Quality improved |
| Phase 4 | Tests + Documentation | Week 4-5 | Production ready |

**Total:** 4-5 weeks of focused refactoring

---

## Questions to Ask Before Starting

1. Should we create feature branches for each phase or work on a single refactoring branch?
2. Are there any critical deadlines that might affect the timeline?
3. Should we prioritize certain exercises over others for the strategy pattern?
4. What's the minimum acceptable test coverage percentage?
5. Are there any specific coding standards or style guides to follow beyond Kotlin conventions?

---

## Final Notes

This refactoring is substantial but structured to minimize risk:

- Each phase builds on previous phases
- Each task is independent and testable
- Existing functionality is preserved
- Changes are backward compatible where possible
- Rollback points exist at each phase boundary

**Remember:** The goal is not just to change code, but to make it more:
- **Maintainable** - easier to understand and modify
- **Testable** - can verify correctness automatically
- **Extensible** - can add features without major changes
- **Portable** - can share logic with iOS version
- **Reliable** - handles errors gracefully

Good luck with the refactoring!
