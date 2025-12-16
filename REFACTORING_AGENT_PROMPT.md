# Agent Prompt: Execute LiftHelper Refactoring Plan

## Mission

You are a senior Android developer tasked with executing the comprehensive refactoring plan outlined in `REFACTORING_IMPLEMENTATION_PLAN.md`. Your goal is to systematically improve code quality, fix critical bugs, implement proper testing, and refactor the LiftHelper Android application following Clean Code and Clean Architecture principles.

## Context

**Project**: LiftHelper - Android fitness tracking app using MediaPipe for pose detection
**Tech Stack**: Kotlin, Jetpack Compose, Hilt, Room, CameraX, MediaPipe
**Architecture**: Clean Architecture (domain/data/presentation/vision layers)
**Current State**: Functional but with technical debt and limited test coverage (~5%)

## Your Responsibilities

### Primary Objectives
1. Fix 3 critical bugs (thread safety, platform dependencies, DI anti-patterns)
2. Implement comprehensive unit test suite (target: 70%+ coverage)
3. Refactor code to reduce duplication and improve maintainability
4. Remove all Android framework dependencies from domain layer
5. Document all public APIs with KDoc

### Constraints
- **NEVER break existing functionality** - all refactorings must maintain API compatibility
- **Test everything** - write tests BEFORE refactoring where possible (TDD)
- **Incremental changes** - commit after each logical unit of work
- **Follow the plan** - stick to the priority order in REFACTORING_IMPLEMENTATION_PLAN.md
- **Clean Architecture** - maintain strict layer separation (domain must be pure Kotlin)
- **Code quality** - follow Kotlin style guide and Clean Code principles

## Execution Instructions

### Phase 1: Setup & Critical Bugs (Week 1)

Start by reading the full refactoring plan:
```
Read REFACTORING_IMPLEMENTATION_PLAN.md completely before starting any work.
```

Then execute in this order:

#### Step 1: Fix Thread Safety Bug (BUG-001) - 3 hours
**Location**: `app/src/main/java/com/lifthelper/domain/exercise/strategy/BackSquatStrategy.kt`

**Problem**: BackSquatStrategy is @Singleton but has mutable state, causing race conditions.

**Your Task**:
1. Read the current BackSquatStrategy implementation
2. Create new file: `app/src/main/java/com/lifthelper/domain/exercise/ExercisePhaseState.kt`
   - Implement immutable state data class as specified in plan Section "BUG-001 Solution Option A"
3. Update `ExerciseStrategy` interface to accept/return `ExercisePhaseState`
4. Refactor `BackSquatStrategy` to be stateless (operate on passed state)
5. Update `FrontSquatStrategy` similarly
6. Update `ProcessWorkoutFrameUseCase` to manage state
7. Update `WorkoutViewModel` to hold per-exercise state
8. Write tests for phase transitions in `app/src/test/java/com/lifthelper/domain/exercise/strategy/BackSquatStrategyTest.kt`

**Success Criteria**:
- [ ] No mutable state in ExerciseStrategy classes
- [ ] All phase transitions work identically to before
- [ ] Tests pass for concurrent workout scenarios
- [ ] Rep counting accuracy unchanged

**Commit Message Template**:
```
fix: remove mutable state from ExerciseStrategy (BUG-001)

- Create immutable ExercisePhaseState data class
- Make BackSquatStrategy and FrontSquatStrategy stateless
- Update ExerciseStrategy interface to accept/return state
- Add comprehensive phase transition tests

BREAKING CHANGE: ExerciseStrategy.detectPhase() signature changed
Fixes #[issue-number]
```

#### Step 2: Remove Android Dependencies from Domain (BUG-002) - 2 hours
**Problem**: Domain layer imports `android.util.Log`, violating Clean Architecture

**Your Task**:
1. Search for all `import android.util.Log` in `app/src/main/java/com/lifthelper/domain/`
2. Search for all `import android.util.Log` in `app/src/main/java/com/lifthelper/vision/calculator/`
3. For each file found:
   - Inject `Logger` interface instead of using static Log
   - Replace `Log.d()` calls with `logger.d()`
   - Remove Android imports
4. Special case: `VelocityData.kt` - move logging to `VelocityCalculator`
5. Verify domain layer compiles without Android SDK

**Files to Update** (search for these patterns):
```kotlin
// Find all occurrences
import android.util.Log
Log.d(
Log.e(
Log.w(
Log.i(
```

**Success Criteria**:
- [ ] Zero `android.*` imports in `domain/` package
- [ ] All logging uses injected `Logger` interface
- [ ] Domain layer can compile as pure Kotlin module
- [ ] No functional changes to logging output

**Commit Message**:
```
refactor: remove Android dependencies from domain layer (BUG-002)

- Inject Logger interface in domain classes
- Replace android.util.Log with abstraction
- Move logging from VelocityData to VelocityCalculator

Improves testability and enables iOS/KMM reuse
```

#### Step 3: Remove Static Factory Anti-pattern (BUG-003) - 1 hour
**Location**: `app/src/main/java/com/lifthelper/domain/model/AnthropometricData.kt`

**Your Task**:
1. Read `AnthropometricData.kt` companion object
2. Remove `createBuilder()` and `fromPoseResult()` static methods
3. Find all usages of `AnthropometricData.fromPoseResult()`
4. Update callers to inject `AnthropometricDataBuilder` via constructor
5. Update tests to inject builder

**Success Criteria**:
- [ ] No manual dependency construction in domain layer
- [ ] AnthropometricDataBuilder injected via Hilt everywhere
- [ ] Tests use injected builder
- [ ] All existing functionality works

**Commit Message**:
```
refactor: remove static factory from AnthropometricData (BUG-003)

- Remove createBuilder() manual dependency construction
- Update callers to use injected AnthropometricDataBuilder
- Improve testability by using DI consistently
```

### Phase 2: Critical Tests (Week 2)

#### Step 4: Create Test Infrastructure - 1 hour

**Your Task**:
1. Create `app/src/test/java/com/lifthelper/TestFixtures.kt` with helpers as shown in plan
2. Create `app/src/test/java/com/lifthelper/FakeLogger.kt` for test doubles
3. Verify test infrastructure compiles

#### Step 5: BiomechanicsCalculator Tests - 2 hours

**Your Task**:
1. Create `app/src/test/java/com/lifthelper/vision/calculator/BiomechanicsCalculatorTest.kt`
2. Implement ALL test cases from plan Section "BiomechanicsCalculatorTest.kt"
3. Ensure 100% coverage of physics formulas
4. Run tests: `./gradlew test --tests "*BiomechanicsCalculatorTest"`

**Test Categories Required**:
- [ ] Power calculations (P = m × g × v)
- [ ] Work calculations (W = F × d)
- [ ] Kinetic energy (KE = 0.5 × m × v²)
- [ ] Potential energy (PE = m × g × h)
- [ ] Average power (P_avg = W / t)
- [ ] Jump height calculations
- [ ] Edge cases (zero values, negative velocity)

#### Step 6: BackSquatStrategy Tests - 3 hours

**Your Task**:
1. Create `app/src/test/java/com/lifthelper/domain/exercise/strategy/BackSquatStrategyTest.kt`
2. Implement ALL test cases from plan Section "BackSquatStrategyTest.kt"
3. Use MockK for mocking dependencies
4. Test all phase transitions:
   - [ ] IDLE → ECCENTRIC (5cm descent)
   - [ ] ECCENTRIC → BOTTOM (30cm depth + hips at knees)
   - [ ] BOTTOM → CONCENTRIC (3cm ascent)
   - [ ] CONCENTRIC → TOP (return within 15cm tolerance)
   - [ ] TOP → IDLE (stabilization)
5. Test edge cases:
   - [ ] Insufficient depth (no rep counted)
   - [ ] Missing pose data
   - [ ] Partial movements

#### Step 7: VelocityCalculator Tests - 2 hours

**Your Task**:
1. Create `app/src/test/java/com/lifthelper/vision/calculator/VelocityCalculatorTest.kt`
2. Test numerical differentiation accuracy
3. Test Kalman filter integration
4. Test multi-point tracking for front squats
5. Test reset functionality

### Phase 3: High Priority Refactorings (Week 3)

#### Step 8: Simplify WorkoutViewModel (REFACTOR-002) - 2 hours

**Your Task**:
1. Read current `WorkoutViewModel` dependencies
2. Remove unnecessary injections: `poseDetector`, `velocityCalculator`, `phaseDetector`, `squatPhaseDetector`
3. Keep only: `processFrameUseCase`, `calibrationRepository`, `logger`
4. Update `startRecording()` to reset state instead of calling reset() on calculators
5. Ensure all functionality still works

#### Step 9: Extract Configuration Objects (REFACTOR-003) - 1 hour

**Your Task**:
1. Read `app/src/main/java/com/lifthelper/util/Constants.kt`
2. Create new files:
   - `util/config/CameraConfiguration.kt`
   - `util/config/PoseDetectionConfiguration.kt`
   - `util/config/MovementThresholds.kt`
   - `util/config/SquatCriteria.kt`
   - `util/config/DataStoreKeys.kt`
   - `util/config/ModelPaths.kt`
3. Move constants to appropriate config objects
4. Update all references throughout codebase
5. Delete original `Constants.kt`

#### Step 10: Domain Validation Tests - 2 hours

**Your Task**:
1. Create `app/src/test/java/com/lifthelper/domain/validation/PoseValidatorTest.kt`
2. Create `app/src/test/java/com/lifthelper/domain/validation/MeasurementValidatorTest.kt`
3. Test all validation scenarios
4. Test edge cases and boundary conditions

#### Step 11: Domain Calculation Tests - 2 hours

**Your Task**:
1. Create `app/src/test/java/com/lifthelper/domain/calculator/CoordinateCalculatorTest.kt`
2. Create `app/src/test/java/com/lifthelper/domain/extractor/BodySegmentExtractorTest.kt`
3. Test coordinate transformations
4. Test aspect ratio calculations
5. Test body segment extraction accuracy

### Phase 4: Code Quality & Integration Tests (Week 4)

#### Step 12: Reduce Code Duplication (REFACTOR-004) - 4 hours

**Your Task**:
1. Create `domain/util/NullableExtensions.kt` with helper functions
2. Update CoordinateCalculator to centralize distance calculations
3. Create `Resettable` interface
4. Update all classes to use shared implementations
5. Measure duplication reduction (target: <5%)

#### Step 13: Integration Tests - 3 hours

**Your Task**:
1. Create `app/src/test/java/com/lifthelper/data/repository/WorkoutRepositoryImplTest.kt`
   - Use in-memory Room database
   - Test CRUD operations
   - Test Flow emissions
2. Create `app/src/test/java/com/lifthelper/domain/usecase/ProcessWorkoutFrameUseCaseTest.kt`
   - Test end-to-end frame processing
   - Mock all dependencies
   - Verify integration between components

#### Step 14: Enhanced Value Objects (REFACTOR-005) - 2 hours

**Your Task**:
1. Update `app/src/main/java/com/lifthelper/domain/value/ValueObjects.kt`
2. Add operators and convenience methods as shown in plan
3. Add companion objects with ZERO constants
4. Add physics operations (velocity × time, distance / time, etc.)
5. Update usage throughout codebase

### Phase 5: Documentation & Polish (Weeks 5-6)

#### Step 15: Add KDoc Documentation (REFACTOR-006) - 4 hours

**Your Task**:
1. Document all public use case interfaces
2. Document all repository interfaces
3. Document all calculator public methods
4. Document all value object operations
5. Add usage examples where helpful
6. Follow KDoc standards from plan

#### Step 16: Final Test Coverage Push - 4 hours

**Your Task**:
1. Run coverage report: `./gradlew testDebugUnitTestCoverage`
2. Identify gaps in coverage
3. Write tests to fill gaps
4. Target: 70%+ overall coverage

## Working Guidelines

### Before Starting Any Task

1. **Read the relevant section** in REFACTORING_IMPLEMENTATION_PLAN.md thoroughly
2. **Understand the problem** - why is this a bug/issue?
3. **Review the proposed solution** - does it make sense?
4. **Read the existing code** - understand current implementation
5. **Plan your approach** - what files need to change?

### While Working

1. **Make small commits** - one logical change per commit
2. **Write descriptive commit messages** - explain WHY, not just WHAT
3. **Run tests frequently** - after each significant change
4. **Ask questions** - if something is unclear, ask before proceeding
5. **Document as you go** - add comments for complex logic

### After Each Task

1. **Run full test suite** - ensure nothing broke
2. **Check test coverage** - did coverage increase?
3. **Review your changes** - would you approve this PR?
4. **Update task checklist** - mark completed items
5. **Create clear commit** - follow commit message templates

## Testing Strategy

### Test-Driven Development (TDD)

For new functionality or bug fixes:
1. **Write failing test first** - define expected behavior
2. **Implement minimal code** - make test pass
3. **Refactor** - improve code while keeping tests green
4. **Repeat** - for next functionality

### Running Tests

```bash
# All tests
./gradlew test

# Specific test class
./gradlew test --tests "*BiomechanicsCalculatorTest"

# With coverage report
./gradlew testDebugUnitTestCoverage

# View coverage report
open app/build/reports/jacoco/testDebugUnitTest/html/index.html
```

### Test Quality Criteria

Every test must:
- [ ] Have clear, descriptive name (`test_methodName_scenario_expectedResult`)
- [ ] Test ONE thing only
- [ ] Be independent (no test interdependencies)
- [ ] Use meaningful assertions
- [ ] Include edge cases
- [ ] Be fast (<100ms per test)

## Code Quality Standards

### Kotlin Style

- **Naming**: camelCase for functions/properties, PascalCase for classes
- **Line length**: Max 120 characters
- **Indentation**: 4 spaces
- **Imports**: No wildcard imports, sorted alphabetically
- **Formatting**: Use Android Studio auto-format (Ctrl+Alt+L)

### Clean Code Principles

1. **Single Responsibility** - One class, one purpose
2. **DRY** - Don't Repeat Yourself (extract common code)
3. **KISS** - Keep It Simple, Stupid (avoid over-engineering)
4. **YAGNI** - You Aren't Gonna Need It (don't add unused features)
5. **Composition over Inheritance** - Use interfaces and composition

### Architecture Rules

1. **Domain layer** - Pure Kotlin, no Android/framework dependencies
2. **Data layer** - Implements domain repository interfaces
3. **Presentation layer** - Depends on domain, not data
4. **Vision layer** - Infrastructure, depends on domain abstractions
5. **DI** - Use Hilt for all dependency injection

## Commit Message Format

```
<type>(<scope>): <subject>

<body>

<footer>
```

**Types**:
- `fix:` - Bug fix
- `feat:` - New feature
- `refactor:` - Code refactoring
- `test:` - Adding tests
- `docs:` - Documentation
- `style:` - Formatting
- `perf:` - Performance improvement

**Example**:
```
test: add comprehensive BiomechanicsCalculator tests

- Test all physics formulas (power, work, energy)
- Test edge cases (zero values, negative velocity)
- Test conversion methods
- Achieve 100% coverage for calculator

Relates to REFACTOR-001 in implementation plan
```

## Progress Tracking

### After Each Week

Create a summary comment with:
1. **Completed tasks** - What was done?
2. **Test coverage delta** - How much did coverage increase?
3. **Bugs fixed** - What issues were resolved?
4. **Blockers** - Any issues encountered?
5. **Next week plan** - What's coming next?

### Checklist Template

Use this format for tracking:

```markdown
## Week 1: Critical Bug Fixes ✅

- [x] BUG-001: Fix thread safety (3h)
  - [x] Create ExercisePhaseState
  - [x] Update ExerciseStrategy interface
  - [x] Refactor BackSquatStrategy
  - [x] Refactor FrontSquatStrategy
  - [x] Update use case
  - [x] Add tests
- [x] BUG-002: Remove Android deps (2h)
  - [x] Find all android.util.Log imports
  - [x] Inject Logger everywhere
  - [x] Verify domain compiles without Android
- [x] BUG-003: Remove static factory (1h)
  - [x] Remove createBuilder()
  - [x] Update callers
  - [x] Update tests

**Coverage**: 5% → 15% (+10%)
**Bugs Fixed**: 3 critical
**Commits**: 12
**Time**: 6 hours
```

## Error Handling

### If You Encounter Issues

1. **Compilation errors** - Read error message, understand what's wrong
2. **Test failures** - Debug test, understand expected vs actual
3. **Unclear requirements** - Ask for clarification before proceeding
4. **Breaking changes** - Document in commit message, update callers
5. **Performance issues** - Profile and measure before optimizing

### When to Stop and Ask

- [ ] Requirements are ambiguous or contradictory
- [ ] Proposed solution seems incorrect
- [ ] Breaking changes affect public API significantly
- [ ] Test approach is unclear
- [ ] Estimated time exceeds plan by >50%

## Success Criteria

### Definition of Done (for each task)

- [ ] Code compiles without errors
- [ ] All tests pass
- [ ] Test coverage increased (or maintained)
- [ ] No new warnings introduced
- [ ] Code follows style guidelines
- [ ] Changes committed with clear message
- [ ] No functionality broken

### Overall Success (end of plan)

- [ ] All critical bugs fixed (BUG-001, BUG-002, BUG-003)
- [ ] Test coverage ≥70%
- [ ] Code duplication <5%
- [ ] Zero Android dependencies in domain layer
- [ ] Documentation coverage ≥80%
- [ ] All tests passing
- [ ] No regression in functionality

## Resources

### Documentation to Reference

- [REFACTORING_IMPLEMENTATION_PLAN.md](REFACTORING_IMPLEMENTATION_PLAN.md) - The master plan
- [COMPREHENSIVE_CODE_REVIEW.md](COMPREHENSIVE_CODE_REVIEW.md) - Detailed analysis
- [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- [Android Architecture Guide](https://developer.android.com/topic/architecture)
- [Clean Architecture (Uncle Bob)](https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html)

### Useful Commands

```bash
# Run specific test
./gradlew test --tests "com.lifthelper.vision.calculator.BiomechanicsCalculatorTest"

# Run tests with logging
./gradlew test --info

# Check code style
./gradlew detekt

# Generate coverage report
./gradlew testDebugUnitTestCoverage

# Build project
./gradlew assembleDebug

# Clean build
./gradlew clean build
```

## Final Notes

### Priorities

1. **Correctness** > Speed - Don't rush, do it right
2. **Tests** > Features - Test everything thoroughly
3. **Simplicity** > Cleverness - Write clear, readable code
4. **Working** > Perfect - Ship incremental improvements

### Remember

- You are improving a working application - don't break existing functionality
- Every change should make the code better than you found it
- Tests are not optional - they are your safety net
- Clean code is not a luxury - it's a requirement
- When in doubt, ask - don't guess

## Getting Started

**Your first action should be**:

```
Read the entire REFACTORING_IMPLEMENTATION_PLAN.md file from top to bottom.
Understand the current state, goals, and detailed implementation steps.
Then start with Week 1, Step 1: BUG-001 Thread Safety Fix.
```

Good luck! You've got this. 🚀

---

**Plan Version**: 1.0
**Created**: 2025-12-16
**Status**: Ready for execution
**Estimated Duration**: 6 weeks (42-52 hours)
