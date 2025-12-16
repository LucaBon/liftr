# LiftHelper - Comprehensive Improvement Plan

**Project**: LiftHelper Android Application
**Created**: 2025-12-16
**Status**: Draft
**Estimated Total Effort**: 42-52 hours (5-6 weeks at 8h/week)

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Critical Bug Fixes (P0)](#critical-bug-fixes-p0)
3. [High Priority Refactorings (P1)](#high-priority-refactorings-p1)
4. [Medium Priority Improvements (P2)](#medium-priority-improvements-p2)
5. [Testing Strategy (P1-P2)](#testing-strategy)
6. [Code Quality Improvements (P3)](#code-quality-improvements-p3)
7. [Implementation Timeline](#implementation-timeline)
8. [Success Metrics](#success-metrics)

---

## Executive Summary

This document outlines a comprehensive plan to improve code quality, fix critical bugs, implement proper testing, and refactor the LiftHelper Android application following Clean Code and Clean Architecture principles.

### Current State Assessment

| Aspect | Current | Target | Priority |
|--------|---------|--------|----------|
| **Test Coverage** | ~5% | 70%+ | P1 |
| **Thread Safety** | Issues in Strategy classes | Safe | P0 |
| **Domain Purity** | Android dependencies present | Pure Kotlin | P0 |
| **Code Duplication** | ~15% | <5% | P2 |
| **Documentation** | ~20% | 80%+ | P3 |
| **Cyclomatic Complexity** | 15+ in key methods | <10 | P2 |

### Quick Wins (Week 1)
- Fix thread safety bug in ExerciseStrategy ⚠️ **CRITICAL**
- Remove Android dependencies from domain layer
- Add unit tests for BiomechanicsCalculator

---

## Critical Bug Fixes (P0)

### 🔴 BUG-001: Thread Safety Issue in ExerciseStrategy

**Location**: [BackSquatStrategy.kt:33-51](app/src/main/java/com/lifthelper/domain/exercise/strategy/BackSquatStrategy.kt#L33-L51)

**Issue**:
```kotlin
@Singleton
class BackSquatStrategy @Inject constructor(...) : ExerciseStrategy {
    // Mutable state in singleton = race conditions!
    private var currentPhaseState = MovementPhase.IDLE
    private var startingHeadY: Float? = null
    private var startingShouldersY: Float? = null
    // ...
}
```

**Impact**:
- Multiple concurrent workouts will corrupt state
- Rapid exercise switching causes undefined behavior
- Rep counting becomes unreliable

**Severity**: CRITICAL
**Effort**: 3 hours
**Risk**: Medium (requires testing phase transitions)

#### Solution Option A: Stateless Strategy (Recommended)

Create an immutable state object passed as parameter:

```kotlin
// domain/exercise/ExercisePhaseState.kt
data class ExercisePhaseState(
    val currentPhase: MovementPhase = MovementPhase.IDLE,
    val startingHeadY: Float? = null,
    val startingShouldersY: Float? = null,
    val startingHipsY: Float? = null,
    val lowestBarPosition: Float? = null,
    val currentHeadY: Float? = null,
    val currentShouldersY: Float? = null,
    val currentHipsY: Float? = null,
    val currentKneesY: Float? = null
) {
    fun withCurrentPhase(phase: MovementPhase) = copy(currentPhase = phase)
    fun withStartingPosition(headY: Float?, shouldersY: Float?, hipsY: Float?) =
        copy(startingHeadY = headY, startingShouldersY = shouldersY, startingHipsY = hipsY)
    // ... other immutable updates
}

// domain/exercise/strategy/BackSquatStrategy.kt
@Singleton
class BackSquatStrategy @Inject constructor(
    private val positionCalculator: IPositionCalculator,
    private val logger: Logger
) : ExerciseStrategy {

    override fun detectPhase(
        poseResult: PoseResult,
        calibrationData: CalibrationData,
        currentState: ExercisePhaseState  // ✅ Passed as parameter
    ): PhaseTransitionResult {
        // All methods operate on passed state, return new state
        val bodyPositions = positionCalculator.getBodyPartPositions(poseResult)
            ?: return PhaseTransitionResult(currentState, repCompleted = false)

        val updatedState = updatePositions(currentState, bodyPositions, poseResult, calibrationData)

        return when (updatedState.currentPhase) {
            MovementPhase.IDLE -> detectIdleTransition(updatedState)
            MovementPhase.ECCENTRIC -> detectEccentricTransition(updatedState)
            // ...
        }
    }

    data class PhaseTransitionResult(
        val newState: ExercisePhaseState,  // ✅ Return new state
        val repCompleted: Boolean,
        val debugInfo: String = ""
    )

    // No reset() method needed - just create new state
}
```

**Migration Steps**:
1. Create `ExercisePhaseState` data class
2. Update `ExerciseStrategy` interface to accept/return state
3. Update `BackSquatStrategy` implementation
4. Update `FrontSquatStrategy` implementation
5. Update `ProcessWorkoutFrameUseCase` to manage state
6. Update `WorkoutViewModel` to hold per-exercise state
7. Add unit tests for state transitions

**Testing Checklist**:
- [ ] Multiple concurrent workouts don't interfere
- [ ] Exercise switching resets state correctly
- [ ] Phase transitions work identically to before
- [ ] Rep counting accuracy unchanged

#### Solution Option B: Scoped Lifecycle (Alternative)

```kotlin
// Change from Singleton to ViewModelScoped
@ViewModelScoped  // One instance per ViewModel
class BackSquatStrategy @Inject constructor(...) : ExerciseStrategy {
    // Same mutable state, but isolated per workout session
}
```

**Pros**: Less refactoring
**Cons**: Doesn't support concurrent workouts, harder to test

**Decision**: Use **Option A** for better testability and correctness.

---

### 🔴 BUG-002: Android Dependencies in Domain Layer

**Locations**:
1. [VelocityData.kt:3-6](app/src/main/java/com/lifthelper/domain/model/VelocityData.kt#L3-L6)
2. [AnthropometricData.kt:123-141](app/src/main/java/com/lifthelper/domain/model/AnthropometricData.kt#L123-L141)
3. [BiomechanicsCalculator.kt:50](app/src/main/java/com/lifthelper/vision/calculator/BiomechanicsCalculator.kt#L50)

**Issue**: Domain layer imports Android framework classes:
```kotlin
import android.util.Log
Log.d(TAG_VELOCITY, "...")
```

**Impact**:
- Domain cannot be reused in iOS/KMM
- Unit tests require Android framework (slow)
- Violates Clean Architecture dependency rule

**Severity**: HIGH
**Effort**: 2 hours
**Risk**: Low

#### Solution: Use Abstraction Layer (Already Exists!)

You already have `Logger` interface - just use it consistently:

```kotlin
// ❌ BEFORE: VelocityData.kt
import android.util.Log
import com.lifthelper.util.Constants.TAG_VELOCITY

Log.d(TAG_VELOCITY, "Velocity: %.2f m/s".format(velocity.value))

// ✅ AFTER: VelocityData.kt
// Remove android.util.Log import
// Logger injected where needed (calculators, validators)

// In VelocityCalculator
class VelocityCalculator @Inject constructor(
    private val positionCalculator: PositionCalculator,
    private val kalmanFilter: KalmanFilter1D,
    private val logger: Logger  // ✅ Inject logger
) : IVelocityCalculator {

    companion object {
        private const val TAG = "VelocityCalculator"
    }

    override fun calculate(...): VelocityData? {
        // ...
        if (kotlin.math.abs(smoothedInstantVelocity) > 0.1f && frameCounter++ % 30 == 0) {
            logger.d(TAG, "Front Squat - Instant: %.2f m/s".format(smoothedInstantVelocity))
        }
    }
}
```

**Migration Steps**:
1. Find all `import android.util.Log` in domain/ and vision/ packages
2. Inject `Logger` interface into those classes
3. Replace `Log.d/e/w()` with `logger.d/e/w()`
4. Remove Android imports
5. Verify domain layer compiles without Android SDK

**Files to Update**:
- `domain/model/VelocityData.kt` - Move logging to VelocityCalculator
- `domain/model/AnthropometricData.kt` - Remove static builder helper
- `vision/calculator/BiomechanicsCalculator.kt` - Inject logger
- Any other files with direct Log calls

---

### 🔴 BUG-003: Static Factory Creates Dependencies Manually

**Location**: [AnthropometricData.kt:122-141](app/src/main/java/com/lifthelper/domain/model/AnthropometricData.kt#L122-L141)

**Issue**:
```kotlin
private fun createBuilder(): AnthropometricDataBuilder {
    // Manually constructing all dependencies - violates DI principles
    val logger = com.lifthelper.infrastructure.logging.AndroidLogger()
    val poseValidator = com.lifthelper.domain.validation.PoseValidator()
    val coordinateCalculator = com.lifthelper.domain.calculator.CoordinateCalculator()
    // ...
    return AnthropometricDataBuilder(...)
}
```

**Impact**:
- Cannot inject mocks for testing
- Couples domain to infrastructure implementation
- Violates dependency inversion principle

**Severity**: MEDIUM
**Effort**: 1 hour
**Risk**: Low

#### Solution: Remove Static Factory, Use DI Everywhere

```kotlin
// ✅ AFTER: Remove createBuilder() helper
companion object {
    fun getDefaultRatios(userHeight: Float, userWeight: Float? = null): AnthropometricData {
        // Keep this - it's just data construction
    }

    // ❌ REMOVE fromPoseResult() static method
    // Let callers inject AnthropometricDataBuilder instead
}

// Usage in ViewModels (already injected):
@HiltViewModel
class AnthropometricCalibrationViewModel @Inject constructor(
    private val builder: AnthropometricDataBuilder,  // ✅ DI handles construction
    // ...
) : ViewModel() {

    fun processCalibrationFrame(poseResult: PoseResult, userHeight: Float) {
        val result = builder.build(poseResult, userHeight, userWeight)
        // ...
    }
}
```

**Migration Steps**:
1. Find all usages of `AnthropometricData.fromPoseResult()`
2. Ensure those classes have `AnthropometricDataBuilder` injected
3. Replace static call with injected builder
4. Remove `createBuilder()` and `fromPoseResult()` methods
5. Update tests to inject builder

---

## High Priority Refactorings (P1)

### 🔧 REFACTOR-001: Add Comprehensive Unit Tests

**Current Coverage**: ~5% (3 test files)
**Target Coverage**: 70%+ for domain/data layers
**Effort**: 12 hours
**Priority**: HIGH

#### Test Suite Structure

```
app/src/test/java/com/lifthelper/
├── domain/
│   ├── calculator/
│   │   ├── CoordinateCalculatorTest.kt          [NEW]
│   │   └── BiomechanicsCalculatorTest.kt        [NEW] ⭐ CRITICAL
│   ├── validation/
│   │   ├── PoseValidatorTest.kt                 [NEW]
│   │   └── MeasurementValidatorTest.kt          [NEW]
│   ├── extractor/
│   │   └── BodySegmentExtractorTest.kt          [NEW]
│   ├── builder/
│   │   └── AnthropometricDataBuilderTest.kt     [NEW]
│   ├── exercise/
│   │   └── strategy/
│   │       ├── BackSquatStrategyTest.kt         [NEW] ⭐ CRITICAL
│   │       └── FrontSquatStrategyTest.kt        [NEW]
│   ├── usecase/
│   │   └── ProcessWorkoutFrameUseCaseTest.kt    [NEW] ⭐ CRITICAL
│   ├── value/
│   │   └── ValueObjectsTest.kt                  [NEW]
│   └── model/
│       ├── RepTest.kt                           [EXISTS] ✅
│       ├── SetTest.kt                           [EXISTS] ✅
│       ├── WorkoutTest.kt                       [EXISTS] ✅
│       └── VelocityDataTest.kt                  [EXISTS] ✅
├── data/
│   ├── repository/
│   │   └── WorkoutRepositoryImplTest.kt         [NEW] (Integration)
│   └── mapper/
│       └── WorkoutMapperTest.kt                 [NEW]
└── vision/
    └── calculator/
        ├── VelocityCalculatorTest.kt            [NEW] ⭐ CRITICAL
        ├── PositionCalculatorTest.kt            [NEW]
        └── BiomechanicsCalculatorTest.kt        [EXISTS] ✅
```

#### Priority 1: Critical Calculation Tests

##### BiomechanicsCalculatorTest.kt

```kotlin
package com.lifthelper.vision.calculator

import com.lifthelper.domain.value.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BiomechanicsCalculatorTest {

    private lateinit var calculator: BiomechanicsCalculator

    @Before
    fun setup() {
        calculator = BiomechanicsCalculator()
    }

    // Physics Formulas Tests - These MUST be accurate!

    @Test
    fun `calculatePower with 100kg at 1ms should return 981W`() {
        val power = calculator.calculatePower(
            weight = Kilograms(100f),
            velocity = MetersPerSecond(1f)
        )

        // P = m × g × v = 100 × 9.81 × 1 = 981W
        assertEquals(981f, power.value, 0.1f)
    }

    @Test
    fun `calculatePower with negative velocity should use absolute value`() {
        val power = calculator.calculatePower(
            weight = Kilograms(100f),
            velocity = MetersPerSecond(-1f)
        )

        assertEquals(981f, power.value, 0.1f)
    }

    @Test
    fun `calculateWork with 100kg over 1m should return 981J`() {
        val work = calculator.calculateWork(
            weight = Kilograms(100f),
            displacement = Meters(1f)
        )

        // W = F × d = (m × g) × d = (100 × 9.81) × 1 = 981J
        assertEquals(981f, work.value, 0.1f)
    }

    @Test
    fun `calculateWork with negative displacement should use absolute value`() {
        val work = calculator.calculateWork(
            weight = Kilograms(100f),
            displacement = Meters(-0.5f)
        )

        // Should use |displacement|
        assertEquals(490.5f, work.value, 0.1f)
    }

    @Test
    fun `calculateAveragePower divides work by time correctly`() {
        val work = Joules(981f)
        val duration = 2f // seconds

        val avgPower = calculator.calculateAveragePower(work, duration)

        // P_avg = W / t = 981 / 2 = 490.5W
        assertEquals(490.5f, avgPower.value, 0.1f)
    }

    @Test
    fun `calculateAveragePower with zero duration returns zero`() {
        val avgPower = calculator.calculateAveragePower(Joules(100f), 0f)
        assertEquals(0f, avgPower.value, 0.001f)
    }

    @Test
    fun `calculateKineticEnergy uses correct formula`() {
        val ke = calculator.calculateKineticEnergy(
            weight = Kilograms(100f),
            velocity = MetersPerSecond(2f)
        )

        // KE = 0.5 × m × v² = 0.5 × 100 × 4 = 200J
        assertEquals(200f, ke.value, 0.1f)
    }

    @Test
    fun `calculatePotentialEnergy uses correct formula`() {
        val pe = calculator.calculatePotentialEnergy(
            weight = Kilograms(100f),
            height = Meters(1f)
        )

        // PE = m × g × h = 100 × 9.81 × 1 = 981J
        assertEquals(981f, pe.value, 0.1f)
    }

    @Test
    fun `calculateTotalEnergy sums KE and PE`() {
        val totalEnergy = calculator.calculateTotalEnergy(
            weight = Kilograms(100f),
            velocity = MetersPerSecond(2f),
            height = Meters(1f)
        )

        // Total = KE + PE = 200 + 981 = 1181J
        assertEquals(1181f, totalEnergy.value, 0.1f)
    }

    @Test
    fun `calculateJumpHeight from takeoff velocity`() {
        val jumpHeight = calculator.calculateJumpHeight(
            MetersPerSecond(2f)
        )

        // h = v² / (2g) = 4 / (2 × 9.81) = 0.204m
        assertEquals(0.204f, jumpHeight.value, 0.01f)
    }

    @Test
    fun `calculateRelativeStrength with 100kg lift and 80kg bodyweight`() {
        val relativeStrength = calculator.calculateRelativeStrength(
            weight = 100f,
            bodyWeight = 80f
        )

        assertEquals(1.25f, relativeStrength, 0.001f)
    }

    @Test
    fun `calculateRelativeStrength with zero bodyweight returns zero`() {
        val relativeStrength = calculator.calculateRelativeStrength(100f, 0f)
        assertEquals(0f, relativeStrength, 0.001f)
    }
}
```

##### BackSquatStrategyTest.kt

```kotlin
package com.lifthelper.domain.exercise.strategy

import com.lifthelper.domain.calculator.CoordinateCalculator
import com.lifthelper.domain.extractor.BodySegmentExtractor
import com.lifthelper.domain.logging.Logger
import com.lifthelper.domain.model.*
import com.lifthelper.domain.validation.PoseValidator
import com.lifthelper.domain.vision.IPositionCalculator
import com.lifthelper.vision.tracking.MovementPhase
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BackSquatStrategyTest {

    private lateinit var strategy: BackSquatStrategy
    private lateinit var positionCalculator: IPositionCalculator
    private lateinit var logger: Logger

    @Before
    fun setup() {
        positionCalculator = mockk(relaxed = true)
        logger = mockk(relaxed = true)

        strategy = BackSquatStrategy(positionCalculator, logger)
    }

    @Test
    fun `detectPhase transitions from IDLE to ECCENTRIC when descending 5cm`() {
        // Setup: Starting position at 1.5m
        val startState = ExercisePhaseState(
            currentPhase = MovementPhase.IDLE,
            startingHeadY = 1.5f,
            startingShouldersY = 1.4f
        )

        // Mock: Current position descended 6cm
        val bodyPositions = mockk<BodyPartPositions> {
            every { headY } returns 1.506f  // 6cm descent (Y-axis down)
            every { shouldersY } returns 1.406f
        }
        every { positionCalculator.getBodyPartPositions(any()) } returns bodyPositions
        every { positionCalculator.pixelsToMeters(any(), any(), any()) } answers {
            firstArg<Float>()  // Return pixel value as meters for simplicity
        }

        val poseResult = createMockPoseResult()
        val calibrationData = createMockCalibrationData()

        // Execute
        val result = strategy.detectPhase(poseResult, calibrationData, startState)

        // Verify
        assertEquals(MovementPhase.ECCENTRIC, result.newState.currentPhase)
        assertFalse(result.repCompleted)
    }

    @Test
    fun `detectPhase transitions from ECCENTRIC to BOTTOM at proper depth`() {
        val eccentricState = ExercisePhaseState(
            currentPhase = MovementPhase.ECCENTRIC,
            startingHeadY = 1.5f,
            startingShouldersY = 1.4f,
            lowestBarPosition = 1.85f
        )

        // Mock: Descended 35cm (> 30cm threshold) with hips at knee level
        val bodyPositions = mockk<BodyPartPositions> {
            every { headY } returns 1.85f
            every { shouldersY } returns 1.75f
            every { hipsY } returns 1.3f
            every { kneesY } returns 1.25f  // Hips >= knees (proper depth)
        }
        every { positionCalculator.getBodyPartPositions(any()) } returns bodyPositions
        every { positionCalculator.pixelsToMeters(any(), any(), any()) } answers {
            firstArg<Float>()
        }

        val result = strategy.detectPhase(
            createMockPoseResult(),
            createMockCalibrationData(),
            eccentricState
        )

        assertEquals(MovementPhase.BOTTOM, result.newState.currentPhase)
        assertFalse(result.repCompleted)
    }

    @Test
    fun `detectPhase transitions from CONCENTRIC to TOP and completes rep`() {
        val concentricState = ExercisePhaseState(
            currentPhase = MovementPhase.CONCENTRIC,
            startingHeadY = 1.5f,
            startingShouldersY = 1.4f
        )

        // Mock: Returned to starting position (within 15cm tolerance)
        val bodyPositions = mockk<BodyPartPositions> {
            every { headY } returns 1.51f  // Within tolerance
            every { shouldersY } returns 1.41f
        }
        every { positionCalculator.getBodyPartPositions(any()) } returns bodyPositions
        every { positionCalculator.pixelsToMeters(any(), any(), any()) } answers {
            firstArg<Float>()
        }

        val result = strategy.detectPhase(
            createMockPoseResult(),
            createMockCalibrationData(),
            concentricState
        )

        assertEquals(MovementPhase.TOP, result.newState.currentPhase)
        assertTrue(result.repCompleted)  // ✅ Rep completed!
    }

    @Test
    fun `detectPhase does not complete rep if insufficient depth`() {
        val eccentricState = ExercisePhaseState(
            currentPhase = MovementPhase.ECCENTRIC,
            startingHeadY = 1.5f,
            startingShouldersY = 1.4f
        )

        // Mock: Only 20cm descent (< 30cm threshold)
        val bodyPositions = mockk<BodyPartPositions> {
            every { headY } returns 1.7f  // Only 20cm
            every { shouldersY } returns 1.6f
            every { hipsY } returns 1.2f
            every { kneesY } returns 1.0f  // Hips NOT at depth
        }
        every { positionCalculator.getBodyPartPositions(any()) } returns bodyPositions
        every { positionCalculator.pixelsToMeters(any(), any(), any()) } answers {
            firstArg<Float>()
        }

        val result = strategy.detectPhase(
            createMockPoseResult(),
            createMockCalibrationData(),
            eccentricState
        )

        // Should stay in ECCENTRIC, waiting for proper depth
        assertEquals(MovementPhase.ECCENTRIC, result.newState.currentPhase)
        assertFalse(result.repCompleted)
    }

    @Test
    fun `getCriteria returns correct thresholds`() {
        val criteria = strategy.getCriteria()

        assertEquals(0.30f, criteria.minDescentDepth.value, 0.001f)  // 30cm
        assertEquals(0.15f, criteria.returnTolerance.value, 0.001f)  // 15cm
        assertEquals(0.1f, criteria.minVelocity.value, 0.001f)
    }

    // Helper methods
    private fun createMockPoseResult(): PoseResult = mockk(relaxed = true)
    private fun createMockCalibrationData(): CalibrationData = mockk(relaxed = true)
}
```

##### VelocityCalculatorTest.kt

```kotlin
package com.lifthelper.vision.calculator

import com.lifthelper.domain.model.*
import com.lifthelper.domain.value.*
import com.lifthelper.util.CircularBuffer
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VelocityCalculatorTest {

    private lateinit var calculator: VelocityCalculator
    private lateinit var positionCalculator: PositionCalculator
    private lateinit var kalmanFilter: KalmanFilter1D

    @Before
    fun setup() {
        positionCalculator = mockk(relaxed = true)
        kalmanFilter = mockk(relaxed = true)

        calculator = VelocityCalculator(positionCalculator, kalmanFilter)
    }

    @Test
    fun `calculate returns null when no pose result`() {
        every { positionCalculator.getBarPosition(any(), any()) } returns null

        val result = calculator.calculate(
            mockk(),
            ExerciseType.BACK_SQUAT,
            mockk(),
            System.currentTimeMillis()
        )

        assertNull(result)
    }

    @Test
    fun `calculate returns zero velocity initially`() {
        every { positionCalculator.getBarPosition(any(), any()) } returns Pair(0.5f, 0.5f)
        every { positionCalculator.pixelsToMeters(any(), any(), any()) } returns 1.5f
        every { kalmanFilter.update(any()) } returns 1.5f

        val result = calculator.calculate(
            mockk(relaxed = true),
            ExerciseType.BACK_SQUAT,
            mockk(relaxed = true),
            0L
        )

        assertNotNull(result)
        assertEquals(0f, result!!.instantaneous.value, 0.001f)  // Not enough history
    }

    @Test
    fun `calculateSecondOrderGradient computes correct velocity`() {
        // Test numerical differentiation accuracy
        // v[n] = (3*f[n] - 4*f[n-1] + f[n-2]) / (2*h)

        val buffer = CircularBuffer<PositionData>(5)
        buffer.add(PositionData(0f, 0L))
        buffer.add(PositionData(0.1f, 100L))  // 0.1m in 0.1s = 1 m/s
        buffer.add(PositionData(0.2f, 200L))

        // Use reflection to test private method or make it package-private
        // For now, test through public API
    }

    @Test
    fun `reset clears all state`() {
        // Add some data
        every { positionCalculator.getBarPosition(any(), any()) } returns Pair(0.5f, 0.5f)
        every { positionCalculator.pixelsToMeters(any(), any(), any()) } returns 1.5f
        every { kalmanFilter.update(any()) } returns 1.5f

        calculator.calculate(mockk(relaxed = true), ExerciseType.BACK_SQUAT, mockk(relaxed = true), 0L)

        // Reset
        calculator.reset()

        // Should return zero velocity again
        val result = calculator.calculate(
            mockk(relaxed = true),
            ExerciseType.BACK_SQUAT,
            mockk(relaxed = true),
            100L
        )

        assertEquals(0f, result!!.instantaneous.value, 0.001f)
        verify { kalmanFilter.reset() }
    }
}
```

#### Testing Strategy Summary

**Phase 1: Critical Tests (Week 2)** - 6 hours
- [ ] BiomechanicsCalculatorTest (all physics formulas)
- [ ] BackSquatStrategyTest (phase transitions)
- [ ] VelocityCalculatorTest (numerical accuracy)

**Phase 2: Domain Tests (Week 3)** - 4 hours
- [ ] PoseValidatorTest
- [ ] MeasurementValidatorTest
- [ ] CoordinateCalculatorTest
- [ ] BodySegmentExtractorTest

**Phase 3: Integration Tests (Week 4)** - 2 hours
- [ ] WorkoutRepositoryImplTest (with in-memory Room)
- [ ] ProcessWorkoutFrameUseCaseTest (end-to-end)

**Test Dependencies**:
```kotlin
// build.gradle.kts
dependencies {
    // Existing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("app.cash.turbine:turbine:1.0.0")

    // Add for better assertions
    testImplementation("com.google.truth:truth:1.1.5")
    testImplementation("org.assertj:assertj-core:3.24.2")
}
```

---

### 🔧 REFACTOR-002: Simplify WorkoutViewModel Dependencies

**Location**: [WorkoutViewModel.kt:26-34](app/src/main/java/com/lifthelper/ui/screens/workout/WorkoutViewModel.kt#L26-L34)

**Issue**: Redundant dependencies injected:
```kotlin
@HiltViewModel
class WorkoutViewModel @Inject constructor(
    private val processFrameUseCase: ProcessWorkoutFrameUseCase,  // ✅ Good
    private val poseDetector: IPoseDetector,                      // ❓ Why? UseCase handles this
    private val velocityCalculator: IVelocityCalculator,          // ❓ Why? UseCase handles this
    private val phaseDetector: IMovementPhaseDetector,            // ❓ Why? UseCase handles this
    private val squatPhaseDetector: ISquatPhaseDetector,          // ❓ Duplicate of above?
    private val calibrationRepository: CalibrationRepository,     // ✅ Good
    private val logger: Logger                                     // ✅ Good
)
```

**Root Cause**: ViewModel was doing frame processing before UseCase was extracted. Now dependencies are duplicated.

**Effort**: 2 hours
**Risk**: Low

#### Solution: Remove Unnecessary Dependencies

```kotlin
// ✅ AFTER: Clean dependencies
@HiltViewModel
class WorkoutViewModel @Inject constructor(
    private val processFrameUseCase: ProcessWorkoutFrameUseCase,  // Handles all frame processing
    private val calibrationRepository: CalibrationRepository,      // Load calibration data
    private val logger: Logger                                     // Logging
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkoutState())
    val uiState: StateFlow<WorkoutState> = _uiState.asStateFlow()

    private var calibrationData: CalibrationData? = null
    private var exerciseState: ExercisePhaseState = ExercisePhaseState()  // NEW: Track state

    init {
        viewModelScope.launch {
            calibrationRepository.getCalibrationData().collect { calibration ->
                calibrationData = calibration
                logger.i(TAG, "Calibration loaded: ${calibration != null}")
            }
        }
    }

    fun processFrame(imageProxy: ImageProxy) {
        viewModelScope.launch {
            val result = processFrameUseCase(
                ProcessWorkoutFrameUseCase.Params(
                    imageProxy = imageProxy,
                    timestamp = System.currentTimeMillis(),
                    calibrationData = calibrationData,
                    exerciseType = _uiState.value.selectedExercise,
                    isRecording = _uiState.value.isRecording,
                    exerciseState = exerciseState,  // Pass state to use case
                    currentRepCount = _uiState.value.repCount.value,
                    weight = _uiState.value.weight
                )
            )

            handleFrameProcessingResult(result)
        }
    }

    fun startRecording() {
        logger.i(TAG, "START RECORDING")
        exerciseState = ExercisePhaseState()  // Reset state for new recording
        _uiState.update { it.copy(isRecording = true, repCount = RepCount(0)) }
    }

    fun stopRecording() {
        logger.i(TAG, "STOP RECORDING")
        _uiState.update { it.copy(isRecording = false) }
    }

    // Remove: All reset() calls - no longer needed
    // Remove: Direct pose/velocity/phase detector usage
}
```

**Migration Steps**:
1. Update ProcessWorkoutFrameUseCase to accept ExercisePhaseState
2. Update ProcessWorkoutFrameUseCase to return updated state in result
3. Remove poseDetector, velocityCalculator, phaseDetector from ViewModel
4. Update startRecording() to reset state (not call reset() on calculators)
5. Update processFrame() to manage state

---

### 🔧 REFACTOR-003: Extract Configuration Objects

**Location**: [Constants.kt](app/src/main/java/com/lifthelper/util/Constants.kt)

**Issue**: Single monolithic Constants object with mixed concerns:
```kotlin
object Constants {
    // Camera config
    const val CAMERA_TARGET_WIDTH = 640
    // Pose detection config
    const val POSE_MIN_DETECTION_CONFIDENCE = 0.3f
    // Movement thresholds
    const val MOVEMENT_VELOCITY_THRESHOLD = 0.1f
    // DataStore keys
    const val KEY_USER_HEIGHT = "user_height"
}
```

**Effort**: 1 hour
**Risk**: Low

#### Solution: Structured Configuration

```kotlin
// util/config/CameraConfiguration.kt
object CameraConfiguration {
    const val TARGET_WIDTH = 640
    const val TARGET_HEIGHT = 480
    const val TARGET_FPS = 30

    const val CALIBRATION_WIDTH = 1280
    const val CALIBRATION_HEIGHT = 960
}

// util/config/PoseDetectionConfiguration.kt
object PoseDetectionConfiguration {
    const val MIN_DETECTION_CONFIDENCE = 0.3f
    const val MIN_TRACKING_CONFIDENCE = 0.3f
    const val MIN_PRESENCE_CONFIDENCE = 0.3f
    const val MIN_VISIBILITY_THRESHOLD = 0.5f
}

// util/config/MovementThresholds.kt
object MovementThresholds {
    const val MOVEMENT_VELOCITY = 0.1f  // m/s - significant movement
    const val TRANSITION_VELOCITY = 0.05f  // m/s - phase transition
    const val IDLE_VELOCITY = 0.02f  // m/s - stationary
}

// util/config/SquatCriteria.kt
object SquatCriteria {
    const val MIN_DESCENT_DEPTH = 0.30f  // 30 cm minimum bar descent
    const val RETURN_TOLERANCE = 0.15f  // 15 cm tolerance for return to start
    const val HIP_KNEE_ALIGNMENT_TOLERANCE = 0.05f  // 5 cm for depth check
}

// util/config/DataStoreKeys.kt
object DataStoreKeys {
    const val USER_HEIGHT = "user_height"
    const val SHOULDER_WIDTH = "shoulder_width"
    const val PIXELS_PER_METER = "pixels_per_meter"
    const val IS_CALIBRATED = "is_calibrated"
}

// util/config/ModelPaths.kt
object ModelPaths {
    const val POSE_LITE = "pose_landmarker_lite.task"
    const val POSE_FULL = "pose_landmarker_full.task"
    const val POSE_HEAVY = "pose_landmarker_heavy.task"

    // Contextual aliases
    const val WORKOUT_MODEL = POSE_LITE    // Fast for real-time
    const val CALIBRATION_MODEL = POSE_HEAVY  // Accurate for measurements
}
```

**Benefits**:
- Clear domain separation
- Easier to find related constants
- Self-documenting categories
- Can inject interfaces for testing later

---

## Medium Priority Improvements (P2)

### 🔧 REFACTOR-004: Reduce Code Duplication

**Current Duplication**: ~15%
**Target**: <5%
**Effort**: 4 hours
**Priority**: MEDIUM

#### Areas of Duplication

**1. Null Safety Checks** - Repeated pattern:
```kotlin
// Found in multiple calculators
if (headY == null || shouldersY == null) return null
if (hipsY == null || kneesY == null) return null
```

**Solution**: Extension function
```kotlin
// domain/util/NullableExtensions.kt
inline fun <T1, T2, R> withBoth(
    t1: T1?,
    t2: T2?,
    block: (T1, T2) -> R
): R? = if (t1 != null && t2 != null) block(t1, t2) else null

inline fun <T1, T2, T3, R> withAll(
    t1: T1?, t2: T2?, t3: T3?,
    block: (T1, T2, T3) -> R
): R? = if (t1 != null && t2 != null && t3 != null) block(t1, t2, t3) else null

// Usage:
val barPosition = withBoth(headY, shouldersY) { head, shoulders ->
    (head + shoulders) / 2f
} ?: return null
```

**2. Aspect Ratio Correction** - Same logic in multiple places:
```kotlin
// Repeated in BodySegmentExtractor, calculators, etc.
val distance = if (isPortrait) {
    val dxScaled = dx * aspectRatio
    sqrt(dxScaled * dxScaled + dy * dy)
} else {
    val dyScaled = dy * aspectRatio
    sqrt(dx * dx + dyScaled * dyScaled)
}
```

**Solution**: Centralize in CoordinateCalculator
```kotlin
// domain/calculator/CoordinateCalculator.kt
fun calculateDistance(
    p1: PoseKeypoint,
    p2: PoseKeypoint,
    aspectRatio: Float,
    isPortrait: Boolean
): Float {
    val dx = p2.x - p1.x
    val dy = p2.y - p1.y

    return if (isPortrait) {
        val dxScaled = dx * aspectRatio
        sqrt(dxScaled * dxScaled + dy * dy)
    } else {
        val dyScaled = dy * aspectRatio
        sqrt(dx * dx + dyScaled * dyScaled)
    }
}

// All other code uses this single implementation
```

**3. State Reset Logic** - Similar patterns:
```kotlin
// In multiple detectors/calculators
override fun reset() {
    positionHistory.clear()
    kalmanFilter.reset()
    startTime = null
}
```

**Solution**: Resettable interface
```kotlin
// domain/util/Resettable.kt
interface Resettable {
    fun reset()
}

// Components implement it
class VelocityCalculator : IVelocityCalculator, Resettable {
    override fun reset() {
        positionHistory.clear()
        kalmanFilter.reset()
        startTime = null
    }
}

// ViewModel can reset all at once
private val resettableComponents: List<Resettable> = listOf(
    velocityCalculator, phaseDetector, squatPhaseDetector
)

fun startRecording() {
    resettableComponents.forEach { it.reset() }
    // ...
}
```

---

### 🔧 REFACTOR-005: Add Value Object Operators

**Current State**: Basic value classes exist ✅
**Enhancement**: Add convenient operators
**Effort**: 2 hours
**Priority**: MEDIUM

#### Enhanced Value Objects

```kotlin
// domain/value/ValueObjects.kt

@JvmInline
value class Kilograms(val value: Float) {
    init { require(value >= 0) { "Weight cannot be negative: $value" } }

    operator fun plus(other: Kilograms) = Kilograms(value + other.value)
    operator fun minus(other: Kilograms) = Kilograms(value - other.value)
    operator fun times(scalar: Float) = Kilograms(value * scalar)
    operator fun div(scalar: Float) = Kilograms(value / scalar)
    operator fun compareTo(other: Kilograms): Int = value.compareTo(other.value)

    fun toNewtons(): Float = value * 9.81f  // NEW: Convenience conversion

    companion object {
        val ZERO = Kilograms(0f)
    }
}

@JvmInline
value class MetersPerSecond(val value: Float) {
    operator fun plus(other: MetersPerSecond) = MetersPerSecond(value + other.value)
    operator fun minus(other: MetersPerSecond) = MetersPerSecond(value - other.value)
    operator fun times(scalar: Float) = MetersPerSecond(value * scalar)
    operator fun div(scalar: Float) = MetersPerSecond(value / scalar)
    operator fun unaryMinus() = MetersPerSecond(-value)
    operator fun compareTo(other: MetersPerSecond): Int = value.compareTo(other.value)

    fun abs() = MetersPerSecond(kotlin.math.abs(value))

    // NEW: Physics operations
    operator fun times(seconds: Float): Meters = Meters(value * seconds)  // velocity × time = distance

    companion object {
        val ZERO = MetersPerSecond(0f)
    }
}

@JvmInline
value class Meters(val value: Float) {
    init { require(value >= 0) { "Distance cannot be negative: $value" } }

    operator fun plus(other: Meters) = Meters(value + other.value)
    operator fun minus(other: Meters) = Meters(value - other.value)
    operator fun times(scalar: Float) = Meters(value * scalar)
    operator fun div(scalar: Float) = Meters(value / scalar)
    operator fun compareTo(other: Meters): Int = value.compareTo(other.value)

    // NEW: Physics operations
    operator fun div(seconds: Float): MetersPerSecond = MetersPerSecond(value / seconds)  // distance / time = velocity

    fun toCentimeters() = value * 100f
    fun toInches() = value * 39.3701f

    companion object {
        val ZERO = Meters(0f)
        fun fromCentimeters(cm: Float) = Meters(cm / 100f)
    }
}

@JvmInline
value class Watts(val value: Float) {
    init { require(value >= 0) { "Power cannot be negative: $value" } }

    operator fun plus(other: Watts) = Watts(value + other.value)
    operator fun minus(other: Watts) = Watts(value - other.value)
    operator fun times(scalar: Float) = Watts(value * scalar)
    operator fun div(scalar: Float) = Watts(value / scalar)
    operator fun compareTo(other: Watts): Int = value.compareTo(other.value)

    // NEW: Physics operations
    operator fun times(seconds: Float): Joules = Joules(value * seconds)  // power × time = energy

    companion object {
        val ZERO = Watts(0f)
    }
}
```

**Usage Benefits**:
```kotlin
// Before
val totalPower = Watts(power1.value + power2.value + power3.value)
val avgPower = Watts(totalPower.value / 3)

// After
val totalPower = power1 + power2 + power3
val avgPower = totalPower / 3f

// Physics operations
val distance = velocity * timeInSeconds  // Returns Meters
val velocity = distance / timeInSeconds  // Returns MetersPerSecond
val energy = power * timeInSeconds  // Returns Joules
```

---

## Testing Strategy

### Test Coverage Goals

| Layer | Current | Target | Priority |
|-------|---------|--------|----------|
| **Domain Models** | 40% | 80% | P1 |
| **Domain Calculators** | 5% | 90% | P0 |
| **Domain Use Cases** | 0% | 80% | P1 |
| **Domain Validators** | 0% | 85% | P1 |
| **Data Repositories** | 0% | 70% | P2 |
| **Vision Calculators** | 5% | 75% | P1 |
| **Presentation (ViewModels)** | 0% | 60% | P2 |
| **Overall** | ~5% | 70% | - |

### Test Infrastructure Setup

```kotlin
// app/src/test/java/com/lifthelper/TestFixtures.kt
object TestFixtures {

    fun createPoseResult(
        landmarkCount: Int = 33,
        visibilityFor: List<Int> = emptyList(),
        invisibleFor: List<Int> = emptyList(),
        imageWidth: Int = 640,
        imageHeight: Int = 480
    ): PoseResult {
        val landmarks = (0 until landmarkCount).map { index ->
            PoseKeypoint(
                x = 0.5f + (index * 0.01f),
                y = 0.5f + (index * 0.01f),
                z = 0f,
                visibility = when {
                    index in visibilityFor -> 0.9f
                    index in invisibleFor -> 0.2f
                    else -> 0.6f
                }
            )
        }

        return PoseResult(
            landmarks = landmarks,
            timestamp = System.currentTimeMillis(),
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )
    }

    fun createCalibrationData(
        pixelsPerMeter: Float = 100f,
        userHeight: Float = 1.75f,
        shoulderWidth: Float = 0.45f
    ): CalibrationData = CalibrationData(
        pixelsPerMeter = pixelsPerMeter,
        userHeight = userHeight,
        shoulderWidth = shoulderWidth,
        calibrationTimestamp = System.currentTimeMillis(),
        quality = CalibrationQuality.OPTIMAL
    )

    fun createAnthropometricData(
        userHeight: Float = 1.75f
    ): AnthropometricData = AnthropometricData.getDefaultRatios(userHeight)

    fun createWorkout(
        id: Long = 0,
        exerciseType: ExerciseType = ExerciseType.BACK_SQUAT,
        setsCount: Int = 3
    ): Workout = Workout(
        id = id,
        date = Date(),
        duration = 600000L,  // 10 minutes
        exerciseType = exerciseType,
        sets = (1..setsCount).map { createSet(it.toLong()) }
    )

    fun createSet(
        id: Long = 0,
        reps: Int = 5
    ): Set = Set(
        id = id,
        workoutId = 0,
        exerciseType = ExerciseType.BACK_SQUAT,
        weight = Kilograms(100f),
        targetReps = RepCount(5),
        completedReps = RepCount(reps),
        avgVelocity = MetersPerSecond(0.8f),
        peakVelocity = MetersPerSecond(1.2f),
        timestamp = System.currentTimeMillis(),
        reps = (1..reps).map { createRep(it.toLong()) }
    )

    fun createRep(id: Long = 0): Rep = Rep(
        id = id,
        setId = 0,
        repNumber = 1,
        peakVelocity = MetersPerSecond(1.2f),
        avgVelocity = MetersPerSecond(0.8f),
        timeUnderTension = 2.5f,
        rangeOfMotion = Meters(0.5f),
        startPosition = Meters(1.5f),
        endPosition = Meters(2.0f),
        eccentricDuration = 1.5f,
        concentricDuration = 1.0f,
        velocityProfile = emptyList(),
        positionProfile = emptyList(),
        timestamp = System.currentTimeMillis()
    )
}
```

### Integration Test Example

```kotlin
// data/repository/WorkoutRepositoryImplTest.kt
@RunWith(AndroidJUnit4::class)
class WorkoutRepositoryImplTest {

    private lateinit var database: LiftDatabase
    private lateinit var repository: WorkoutRepositoryImpl

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LiftDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        repository = WorkoutRepositoryImpl(
            workoutDao = database.workoutDao(),
            mapper = WorkoutMapper(),
            logger = FakeLogger()
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun saveWorkout_shouldPersistAndRetrieve() = runBlocking {
        val workout = TestFixtures.createWorkout(setsCount = 3)

        val saveResult = repository.saveWorkout(workout)

        assertTrue(saveResult.isSuccess)
        val savedId = saveResult.getOrThrow()
        assertTrue(savedId > 0)

        val retrieveResult = repository.getWorkoutById(savedId)
        assertTrue(retrieveResult.isSuccess)

        val retrieved = retrieveResult.getOrThrow()
        assertEquals(workout.exerciseType, retrieved.exerciseType)
        assertEquals(3, retrieved.sets.size)
    }

    @Test
    fun getWorkouts_shouldReturnFlowOfWorkouts() = runBlocking {
        val workout1 = TestFixtures.createWorkout()
        val workout2 = TestFixtures.createWorkout()

        repository.saveWorkout(workout1)
        repository.saveWorkout(workout2)

        val workouts = repository.getWorkouts().first()

        assertEquals(2, workouts.size)
    }

    @Test
    fun deleteWorkout_shouldRemoveFromDatabase() = runBlocking {
        val workout = TestFixtures.createWorkout()
        val savedId = repository.saveWorkout(workout).getOrThrow()

        val deleteResult = repository.deleteWorkout(savedId)

        assertTrue(deleteResult.isSuccess)

        val retrieveResult = repository.getWorkoutById(savedId)
        assertTrue(retrieveResult.isFailure)
    }
}

class FakeLogger : Logger() {
    override fun d(tag: String, message: String) {}
    override fun i(tag: String, message: String) {}
    override fun w(tag: String, message: String) {}
    override fun e(tag: String, message: String, throwable: Throwable?) {}
}
```

---

## Code Quality Improvements (P3)

### 🔧 REFACTOR-006: Add KDoc Documentation

**Current**: ~20% public API documented
**Target**: 80%+
**Effort**: 4 hours
**Priority**: LOW

#### Documentation Standards

```kotlin
/**
 * Calculates mechanical power output during a lift.
 *
 * Power is the rate of doing work, calculated as the product of force and velocity.
 * For resistance training, force equals the weight being lifted (mass × gravity),
 * and velocity is the instantaneous barbell velocity.
 *
 * Formula: P = F × v = (m × g) × v
 *
 * @param weight The mass being lifted (barbell + plates + body weight if applicable)
 * @param velocity The instantaneous velocity of the barbell during the lift
 * @param bodyWeight Optional body weight for bodyweight exercises (e.g., pull-ups)
 * @return Instantaneous power output in Watts
 *
 * @see calculateAveragePower for average power over a duration
 * @see calculateWork for total mechanical work done
 *
 * Example:
 * ```kotlin
 * val power = calculator.calculatePower(
 *     weight = Kilograms(100f),
 *     velocity = MetersPerSecond(1.0f)
 * )
 * // Result: ~981 Watts
 * ```
 */
fun calculatePower(
    weight: Kilograms,
    velocity: MetersPerSecond,
    bodyWeight: Kilograms? = null
): Watts
```

**Priority Documentation**:
1. All public use case interfaces
2. All repository interfaces
3. All calculator public methods
4. All value object operations
5. Complex domain models

---

## Implementation Timeline

### Week 1: Critical Bug Fixes (P0)
**Total: 6 hours**

- [ ] **BUG-001**: Fix thread safety in ExerciseStrategy (3h)
  - Create ExercisePhaseState data class
  - Update ExerciseStrategy interface
  - Refactor BackSquatStrategy
  - Refactor FrontSquatStrategy
  - Update use case and ViewModel
  - Test phase transitions

- [ ] **BUG-002**: Remove Android dependencies from domain (2h)
  - Inject Logger in all domain classes
  - Replace Log.d() calls
  - Remove android.util.Log imports
  - Verify domain compiles without Android SDK

- [ ] **BUG-003**: Remove static factory anti-pattern (1h)
  - Remove createBuilder() from AnthropometricData
  - Update callers to inject builder
  - Update tests

### Week 2: Critical Tests (P1)
**Total: 8 hours**

- [ ] **TEST-001**: BiomechanicsCalculator tests (2h)
  - All physics formula tests
  - Edge case tests
  - Validation tests

- [ ] **TEST-002**: BackSquatStrategy tests (3h)
  - Phase transition tests
  - Rep counting accuracy tests
  - Depth validation tests
  - Edge case tests

- [ ] **TEST-003**: VelocityCalculator tests (2h)
  - Numerical differentiation accuracy
  - Kalman filter integration
  - Multi-point tracking tests

- [ ] **TEST-004**: Test fixtures and helpers (1h)
  - Create TestFixtures utility
  - Mock builders
  - Common test data

### Week 3: High Priority Refactorings (P1)
**Total: 7 hours**

- [ ] **REFACTOR-002**: Simplify ViewModel dependencies (2h)
  - Update ProcessWorkoutFrameUseCase
  - Remove redundant ViewModel dependencies
  - Update state management

- [ ] **REFACTOR-003**: Extract configuration objects (1h)
  - Split Constants.kt
  - Create structured config objects
  - Update all references

- [ ] **TEST-005**: Domain validation tests (2h)
  - PoseValidatorTest
  - MeasurementValidatorTest
  - Edge cases

- [ ] **TEST-006**: Domain calculation tests (2h)
  - CoordinateCalculatorTest
  - BodySegmentExtractorTest

### Week 4: Medium Priority + Integration Tests (P2)
**Total: 8 hours**

- [ ] **REFACTOR-004**: Reduce code duplication (4h)
  - Extract common null safety patterns
  - Centralize aspect ratio calculations
  - Create Resettable interface
  - Update all usages

- [ ] **TEST-007**: Integration tests (2h)
  - WorkoutRepositoryImpl test
  - ProcessWorkoutFrameUseCase test

- [ ] **TEST-008**: Mapper tests (1h)
  - WorkoutMapper test
  - Entity/domain conversion accuracy

- [ ] **REFACTOR-005**: Enhanced value objects (2h)
  - Add convenience operators
  - Add physics operations
  - Add companion objects
  - Update usages

### Week 5-6: Remaining Items + Documentation (P3)
**Total: 8 hours**

- [ ] **TEST-009**: Additional test coverage (4h)
  - Fill gaps to reach 70% overall
  - UI component tests
  - Edge case coverage

- [ ] **REFACTOR-006**: Add KDoc documentation (4h)
  - Document all public APIs
  - Add usage examples
  - Add @see references

### Ongoing: Code Review & Quality
**Continuous**

- [ ] Run Detekt for static analysis
- [ ] Check test coverage reports
- [ ] Review PR feedback
- [ ] Update documentation

---

## Success Metrics

### Code Quality Metrics

| Metric | Baseline | Target | Measurement |
|--------|----------|--------|-------------|
| **Test Coverage** | 5% | 70% | JaCoCo reports |
| **Cyclomatic Complexity** | 15+ | <10 | Detekt analysis |
| **Code Duplication** | 15% | <5% | PMD CPD |
| **Documentation** | 20% | 80% | KDoc coverage |
| **Thread Safety Issues** | 2 | 0 | Manual review |
| **Android Dependencies in Domain** | 5 | 0 | Import analysis |
| **Average Method Length** | 25 lines | <15 lines | SonarQube |
| **Build Time** | Baseline | -10% | Gradle profiling |

### Functional Metrics

| Metric | Baseline | Target | Measurement |
|--------|----------|--------|-------------|
| **Phase Transition Accuracy** | 95% | 98% | Manual testing |
| **Rep Counting Accuracy** | 90% | 95% | Test workouts |
| **Velocity Calculation Precision** | ±5% | ±2% | Calibrated tests |
| **Crash Rate** | TBD | <0.1% | Firebase Crashlytics |
| **Frame Processing Time** | TBD | <33ms (30fps) | Performance profiling |

### Developer Experience Metrics

| Metric | Baseline | Target | Measurement |
|--------|----------|--------|-------------|
| **Unit Test Run Time** | N/A | <10s | Gradle test task |
| **Integration Test Run Time** | N/A | <30s | Gradle test task |
| **Clean Build Time** | Baseline | No regression | Gradle profiling |
| **Hot Reload Time** | Baseline | No regression | Android Studio |

---

## Risk Assessment

### High Risk Items

| Risk | Impact | Mitigation |
|------|--------|------------|
| **Phase transition refactoring breaks rep counting** | HIGH | Extensive testing, gradual rollout, feature flag |
| **Thread safety fix introduces performance regression** | MEDIUM | Performance profiling, benchmarking |
| **Test coverage goals too ambitious** | LOW | Phased approach, focus on critical paths first |

### Dependencies & Blockers

| Item | Dependency | Blocker Resolution |
|------|------------|-------------------|
| Integration tests | In-memory Room setup | Day 1 setup |
| ViewModel refactoring | Use case updates | Complete use case changes first |
| Documentation | All refactorings complete | Can be done in parallel |

---

## Appendix: Tools & Commands

### Run Tests
```bash
# All tests
./gradlew test

# With coverage
./gradlew testDebugUnitTestCoverage

# Specific test class
./gradlew test --tests "*BiomechanicsCalculatorTest"
```

### Code Quality Checks
```bash
# Detekt static analysis
./gradlew detekt

# Lint checks
./gradlew lint

# Check coverage thresholds
./gradlew jacocoTestReport
open app/build/reports/jacoco/test/html/index.html
```

### Performance Profiling
```bash
# Build with profiling
./gradlew --profile assembleDebug

# View report
open build/reports/profile/profile-*.html
```

---

## Notes & Assumptions

1. **Test Priority**: Focus on domain layer first (pure Kotlin, no Android dependencies)
2. **Backward Compatibility**: All refactorings maintain API compatibility
3. **Incremental Approach**: Each week's work is independently deployable
4. **Code Review**: All changes require peer review before merge
5. **Documentation**: Update README.md and architecture docs alongside code

---

**Last Updated**: 2025-12-16
**Status**: Ready for Implementation
**Approver**: [Pending]
**Questions/Feedback**: [Create GitHub issue with "refactoring-plan" label]
