# Feature 03: Bench Press & Deadlift Strategies

## Summary

Implement ExerciseStrategy for Bench Press and Deadlift — the two most popular gym
exercises after squats. The Strategy pattern is already in place; this feature adds
two new strategy classes, exercise-specific thresholds, form validation rules, and
landmark requirements.

## Motivation

Back Squat and Front Squat cover lower-body pushing. Bench Press covers upper-body
pushing. Deadlift covers the posterior chain pull. Together, these four exercises
represent the core of strength training and competitive powerlifting (SBD total).

ExerciseType already declares BENCH_PRESS and DEADLIFT — they're just not implemented.

## Biomechanical Analysis

### Bench Press

**Camera angle**: Side view (same as squat)

**Key landmarks**:
- Wrists (bar position) — primary tracking point
- Elbows — for flare angle and lockout detection
- Shoulders — for bar path reference (should press from mid-chest to over shoulders)

**Movement phases**:
```
IDLE → ECCENTRIC (bar descending) → BOTTOM (bar at chest) → CONCENTRIC (pressing up) → TOP (lockout)
```

**Phase detection logic**:
- Bar position = average of LEFT_WRIST and RIGHT_WRIST Y coordinates
- IDLE → ECCENTRIC: wrists move downward > 5cm from starting position
- ECCENTRIC → BOTTOM: wrist velocity approaches 0, wrists at lowest point
- BOTTOM → CONCENTRIC: wrists move upward > 3cm from lowest point
- CONCENTRIC → TOP: wrists return within 10cm of starting position AND elbow angle > 160°

**Key metrics**:
- Bar path linearity (deviation from vertical line)
- Lockout detection (elbow extension angle)
- Touch point consistency (where bar meets chest)
- Eccentric/concentric tempo

**Form validation**:
| Check | Landmarks | Condition | Severity |
|-------|-----------|-----------|----------|
| Elbow flare | Shoulders, Elbows, Wrists | Elbow-shoulder angle > 80° | WARNING |
| Bar drift | Wrists over time | Horizontal drift > 10cm from start | WARNING |
| Uneven press | Left vs Right wrist Y | Asymmetry > 3cm | WARNING |
| Incomplete lockout | Elbows | Elbow angle < 150° at TOP | INFO |
| Excessive arch | Hips, Shoulders | Hip lift off bench (hips Y much higher than expected) | WARNING |

**Minimum velocity at 1RM**: 0.17 m/s (Gonzalez-Badillo, 2010)

### Deadlift

**Camera angle**: Side view

**Key landmarks**:
- Hips — primary tracking point (hip hinge is the core movement)
- Shoulders — for back angle and lockout
- Knees — for knee lockout timing
- Ankles — for bar-over-midfoot tracking

**Movement phases**:
```
IDLE → CONCENTRIC (lifting) → TOP (lockout) → ECCENTRIC (lowering) → IDLE
```

Note: Deadlift starts from the floor (concentric first), unlike squat/bench.
The phase order is reversed compared to squats.

**Phase detection logic**:
- Bar position = hips Y (primary), shoulders Y (secondary)
- IDLE → CONCENTRIC: hips move upward > 5cm from starting position
- CONCENTRIC → TOP: hips and shoulders reach standing height AND knees extended AND hips extended (full lockout)
- TOP → ECCENTRIC: hips move downward > 5cm from lockout
- ECCENTRIC → IDLE: hips return to starting position (bar on floor)

**Lockout detection** (critical for competition):
- Hip extension: hip angle > 170° (hips pushed through)
- Knee extension: knee angle > 170°
- Shoulder position: shoulders behind or directly over bar (no forward lean)

**Form validation**:
| Check | Landmarks | Condition | Severity |
|-------|-----------|-----------|----------|
| Back rounding | Shoulders, Hips, Knees | Torso angle changes >15° during lift | CRITICAL |
| Hitching | Hips velocity | Hips stop and restart during concentric | WARNING |
| Lockout incomplete | Hips, Knees, Shoulders | Not fully extended at TOP | INFO |
| Bar drift | Hips X vs Ankles X | Bar drifts forward away from shins | WARNING |
| Knee lockout timing | Knees vs Hips | Knees lock before hips are through | INFO |

**Minimum velocity at 1RM**: 0.15 m/s (Gonzalez-Badillo, 2010)

## Implementation Plan

### Step 1: Exercise Thresholds

Add to `ExerciseThresholds.kt`:

```kotlin
object BenchPress {
    val MIN_DESCENT_DEPTH = Meters(0.15f)              // 15cm bar descent
    val RETURN_TOLERANCE = Meters(0.10f)               // 10cm from start = lockout
    val MIN_VELOCITY = MetersPerSecond(0.05f)
    const val IDLE_TO_ECCENTRIC_THRESHOLD = 0.05f      // 5cm
    const val BOTTOM_TO_CONCENTRIC_THRESHOLD = 0.03f   // 3cm
    const val TOP_STABILIZATION_THRESHOLD = 0.02f      // 2cm
    const val ELBOW_FLARE_MAX_ANGLE = 80f              // degrees
    const val BAR_DRIFT_MAX = 0.10f                    // 10cm horizontal
    const val ASYMMETRY_MAX = 0.03f                    // 3cm left-right
    const val LOCKOUT_MIN_ANGLE = 150f                 // degrees
}

object Deadlift {
    val MIN_LIFT_HEIGHT = Meters(0.40f)                // 40cm minimum pull
    val RETURN_TOLERANCE = Meters(0.15f)               // 15cm from lockout
    val MIN_VELOCITY = MetersPerSecond(0.05f)
    const val IDLE_TO_CONCENTRIC_THRESHOLD = 0.05f     // 5cm
    const val TOP_TO_ECCENTRIC_THRESHOLD = 0.05f       // 5cm
    const val LOCKOUT_HIP_ANGLE = 170f                 // degrees
    const val LOCKOUT_KNEE_ANGLE = 170f                // degrees
    const val BACK_ROUNDING_THRESHOLD = 15f            // degrees change
    const val BAR_DRIFT_MAX = 0.08f                    // 8cm from midfoot
    const val HITCH_VELOCITY_THRESHOLD = 0.02f         // near-zero velocity during concentric
}
```

### Step 2: ExercisePhaseState Extension

The current ExercisePhaseState tracks head, shoulders, hips, knees. For bench press
we also need wrist and elbow tracking. Two approaches:

**Option A (Recommended)**: Add optional fields to ExercisePhaseState:
```kotlin
data class ExercisePhaseState(
    // ... existing fields ...
    val currentWristsY: Float? = null,
    val currentElbowsY: Float? = null,
    val currentElbowAngle: Float? = null,
    val startingWristsY: Float? = null,
    val lowestWristsY: Float? = null,       // bench press: lowest bar point
    val highestHipsY: Float? = null,        // deadlift: highest hip point (lockout)
    val startingTorsoAngle: Float? = null   // deadlift: initial back angle
)
```

All new fields are nullable and defaulted to null, so existing squat strategies
are completely unaffected. The copy() helper methods already exist for current fields;
add similar ones for the new fields.

**Option B**: Create exercise-specific state subclasses. Rejected because:
- The Strategy interface uses ExercisePhaseState directly
- Generics would complicate the pipeline
- Extra nullable fields are cheap and simple

### Step 3: BenchPressStrategy

File: `domain/exercise/strategy/BenchPressStrategy.kt`

```kotlin
class BenchPressStrategy : ExerciseStrategy {
    override val exerciseType = ExerciseType.BENCH_PRESS

    override fun getRequiredLandmarks(): List<Int> = listOf(
        PoseResult.LEFT_SHOULDER, PoseResult.RIGHT_SHOULDER,
        PoseResult.LEFT_ELBOW, PoseResult.RIGHT_ELBOW,
        PoseResult.LEFT_WRIST, PoseResult.RIGHT_WRIST,
        PoseResult.LEFT_HIP, PoseResult.RIGHT_HIP
    )

    override fun getCriteria(): ExerciseCriteria = ExerciseCriteria(
        minDescentDepth = ExerciseThresholds.BenchPress.MIN_DESCENT_DEPTH,
        returnTolerance = ExerciseThresholds.BenchPress.RETURN_TOLERANCE,
        minVelocity = ExerciseThresholds.BenchPress.MIN_VELOCITY,
        targetBodyParts = getRequiredLandmarks()
    )

    override fun detectPhase(
        poseResult: PoseResult,
        calibrationData: CalibrationData,
        currentState: ExercisePhaseState
    ): PhaseTransitionResult {
        // Bar position = average wrist Y
        val wristsY = averageY(poseResult, LEFT_WRIST, RIGHT_WRIST)
        val elbowAngle = calculateElbowAngle(poseResult)

        val updatedState = currentState.withCurrentPositions(
            // ... update wrists, elbows, etc.
        )

        return when (currentState.currentPhase) {
            MovementPhase.IDLE -> detectIdleToEccentric(updatedState, wristsY)
            MovementPhase.ECCENTRIC -> detectEccentricToBottom(updatedState, wristsY)
            MovementPhase.BOTTOM -> detectBottomToConcentric(updatedState, wristsY)
            MovementPhase.CONCENTRIC -> detectConcentricToTop(updatedState, wristsY, elbowAngle)
            MovementPhase.TOP -> PhaseTransitionResult(
                newState = updatedState.withCurrentPhase(MovementPhase.IDLE),
                repCompleted = true
            )
        }
    }

    override fun validateForm(
        poseResult: PoseResult,
        calibrationData: CalibrationData
    ): FormValidationResult {
        val issues = mutableListOf<FormIssue>()

        // Check elbow flare
        checkElbowFlare(poseResult)?.let { issues.add(it) }

        // Check bar drift
        checkBarDrift(poseResult, calibrationData)?.let { issues.add(it) }

        // Check asymmetry
        checkPressAsymmetry(poseResult, calibrationData)?.let { issues.add(it) }

        return FormValidationResult(
            isValid = issues.none { it.severity == Severity.CRITICAL },
            issues = issues
        )
    }

    // ... helper methods for angle calculation, form checks
}
```

**Elbow angle calculation**: Use 3-point angle between SHOULDER, ELBOW, WRIST
landmarks. When the arm is fully extended, this angle approaches 180°.

```kotlin
private fun calculateElbowAngle(poseResult: PoseResult): Float {
    val shoulder = averagePoint(poseResult, LEFT_SHOULDER, RIGHT_SHOULDER)
    val elbow = averagePoint(poseResult, LEFT_ELBOW, RIGHT_ELBOW)
    val wrist = averagePoint(poseResult, LEFT_WRIST, RIGHT_WRIST)
    return CoordinateCalculator.angleBetweenPoints(shoulder, elbow, wrist)
}
```

### Step 4: DeadliftStrategy

File: `domain/exercise/strategy/DeadliftStrategy.kt`

```kotlin
class DeadliftStrategy : ExerciseStrategy {
    override val exerciseType = ExerciseType.DEADLIFT

    override fun getRequiredLandmarks(): List<Int> = listOf(
        PoseResult.NOSE,
        PoseResult.LEFT_SHOULDER, PoseResult.RIGHT_SHOULDER,
        PoseResult.LEFT_HIP, PoseResult.RIGHT_HIP,
        PoseResult.LEFT_KNEE, PoseResult.RIGHT_KNEE,
        PoseResult.LEFT_ANKLE, PoseResult.RIGHT_ANKLE
    )

    override fun getCriteria(): ExerciseCriteria = ExerciseCriteria(
        minDescentDepth = ExerciseThresholds.Deadlift.MIN_LIFT_HEIGHT,
        returnTolerance = ExerciseThresholds.Deadlift.RETURN_TOLERANCE,
        minVelocity = ExerciseThresholds.Deadlift.MIN_VELOCITY,
        targetBodyParts = getRequiredLandmarks()
    )

    override fun detectPhase(
        poseResult: PoseResult,
        calibrationData: CalibrationData,
        currentState: ExercisePhaseState
    ): PhaseTransitionResult {
        val hipsY = averageY(poseResult, LEFT_HIP, RIGHT_HIP)
        val shouldersY = averageY(poseResult, LEFT_SHOULDER, RIGHT_SHOULDER)
        val kneesY = averageY(poseResult, LEFT_KNEE, RIGHT_KNEE)

        // Deadlift phase order: IDLE → CONCENTRIC → TOP → ECCENTRIC → IDLE
        return when (currentState.currentPhase) {
            MovementPhase.IDLE -> detectIdleToConcentric(currentState, hipsY)
            MovementPhase.CONCENTRIC -> detectConcentricToTop(currentState, hipsY, shouldersY, kneesY)
            MovementPhase.TOP -> {
                // Rep completed at lockout
                PhaseTransitionResult(
                    newState = currentState.withCurrentPhase(MovementPhase.ECCENTRIC),
                    repCompleted = true
                )
            }
            MovementPhase.ECCENTRIC -> detectEccentricToIdle(currentState, hipsY)
            MovementPhase.BOTTOM -> {
                // Deadlift doesn't use BOTTOM phase — skip to IDLE
                PhaseTransitionResult(
                    newState = currentState.withCurrentPhase(MovementPhase.IDLE),
                    repCompleted = false
                )
            }
        }
    }

    override fun validateForm(
        poseResult: PoseResult,
        calibrationData: CalibrationData
    ): FormValidationResult {
        val issues = mutableListOf<FormIssue>()

        // Check back rounding (CRITICAL)
        checkBackRounding(poseResult, calibrationData)?.let { issues.add(it) }

        // Check hitching
        checkHitching(poseResult)?.let { issues.add(it) }

        // Check lockout completeness
        checkLockout(poseResult)?.let { issues.add(it) }

        // Check bar path (drift from midfoot)
        checkBarDrift(poseResult, calibrationData)?.let { issues.add(it) }

        return FormValidationResult(
            isValid = issues.none { it.severity == Severity.CRITICAL },
            issues = issues
        )
    }
}
```

**Back rounding detection**:
```kotlin
private fun checkBackRounding(
    poseResult: PoseResult,
    calibrationData: CalibrationData
): FormIssue? {
    // Calculate torso angle: angle between shoulder-hip line and vertical
    val shoulderY = averageY(poseResult, LEFT_SHOULDER, RIGHT_SHOULDER)
    val shoulderX = averageX(poseResult, LEFT_SHOULDER, RIGHT_SHOULDER)
    val hipY = averageY(poseResult, LEFT_HIP, RIGHT_HIP)
    val hipX = averageX(poseResult, LEFT_HIP, RIGHT_HIP)

    val torsoAngle = atan2(abs(shoulderX - hipX), abs(shoulderY - hipY))
        .toDegrees()

    // Compare with starting angle — if it increases >15° during the lift,
    // the back is rounding
    // (startingTorsoAngle is set when CONCENTRIC phase begins)
    val startAngle = currentState.startingTorsoAngle ?: return null
    val angleChange = torsoAngle - startAngle

    return if (angleChange > ExerciseThresholds.Deadlift.BACK_ROUNDING_THRESHOLD) {
        FormIssue(
            severity = Severity.CRITICAL,
            message = "Back rounding detected",
            suggestion = "Keep chest up and back flat"
        )
    } else null
}
```

**Hitching detection** (bar stops and re-starts during concentric):
```kotlin
// Track hip velocity during concentric phase
// If velocity drops below HITCH_VELOCITY_THRESHOLD and then increases again,
// that's a hitch. Uses a simple state: wasSlowing + isAccelerating
```

### Step 5: VelocityCalculator Updates

The VelocityCalculator currently tracks shoulders, hips, and head. For bench press,
it needs to track wrists as the primary bar position indicator.

Add to `PositionCalculator`:
```kotlin
fun getWristPosition(poseResult: PoseResult): Float? {
    val leftWrist = poseResult.landmarks.getOrNull(PoseResult.LEFT_WRIST)
    val rightWrist = poseResult.landmarks.getOrNull(PoseResult.RIGHT_WRIST)
    if (leftWrist == null || rightWrist == null) return null
    if (leftWrist.visibility < VISIBILITY_THRESHOLD ||
        rightWrist.visibility < VISIBILITY_THRESHOLD) return null
    return (leftWrist.y + rightWrist.y) / 2f
}
```

Update `VelocityCalculator.calculate()` to use wrist position for BENCH_PRESS
exercise type, and hip position for DEADLIFT.

### Step 6: Register in ExerciseStrategyFactory

Update the factory to return BenchPressStrategy and DeadliftStrategy for
their respective ExerciseType values. No other changes needed — the pipeline
(ProcessWorkoutFrameUseCase) already uses the factory generically.

### Step 7: Coordinate Calculator Extensions

File: `domain/calculator/CoordinateCalculator.kt` (modify existing)

Add utility methods needed by the new strategies:

```kotlin
fun angleBetweenPoints(a: Point, b: Point, c: Point): Float {
    // Angle at point b, formed by segments ba and bc
    val ba = Point(a.x - b.x, a.y - b.y)
    val bc = Point(c.x - b.x, c.y - b.y)
    val dot = ba.x * bc.x + ba.y * bc.y
    val magBA = sqrt(ba.x * ba.x + ba.y * ba.y)
    val magBC = sqrt(bc.x * bc.x + bc.y * bc.y)
    if (magBA == 0f || magBC == 0f) return 0f
    val cosAngle = (dot / (magBA * magBC)).coerceIn(-1f, 1f)
    return acos(cosAngle).toDegrees()
}

fun torsoAngle(shoulderX: Float, shoulderY: Float, hipX: Float, hipY: Float): Float {
    // Angle of torso from vertical
    return atan2(abs(shoulderX - hipX), abs(shoulderY - hipY)).toDegrees()
}
```

## Files to Create

| File | Layer | Purpose |
|------|-------|---------|
| `domain/exercise/strategy/BenchPressStrategy.kt` | Domain | Bench press phase detection + form validation |
| `domain/exercise/strategy/DeadliftStrategy.kt` | Domain | Deadlift phase detection + form validation |

## Files to Modify

| File | Change |
|------|--------|
| `domain/exercise/ExerciseThresholds.kt` | Add BenchPress and Deadlift threshold objects |
| `domain/exercise/ExercisePhaseState.kt` | Add wrist/elbow/torsoAngle optional fields + copy helpers |
| `domain/exercise/ExerciseStrategyFactory.kt` | Register new strategies for BENCH_PRESS, DEADLIFT |
| `domain/calculator/CoordinateCalculator.kt` | Add angleBetweenPoints(), torsoAngle() |
| `vision/calculator/PositionCalculator.kt` | Add getWristPosition(), getElbowPosition() |
| `vision/calculator/VelocityCalculator.kt` | Use wrists for bench, hips for deadlift |
| `res/values/strings.xml` | Form issue messages for bench/deadlift |

## Testing Plan

| Test | Type | What |
|------|------|------|
| `BenchPressStrategyTest` | Unit | Phase transitions with synthetic wrist data |
| `BenchPressStrategyIntegrationTest` | Integration | Full pipeline with realistic landmark sequences |
| `DeadliftStrategyTest` | Unit | Phase transitions, lockout detection |
| `DeadliftStrategyIntegrationTest` | Integration | Full pipeline including back rounding scenarios |
| `CoordinateCalculatorAngleTest` | Unit | angleBetweenPoints edge cases (0°, 90°, 180°, collinear) |
| `BenchPressFormValidationTest` | Unit | Elbow flare, bar drift, asymmetry detection |
| `DeadliftFormValidationTest` | Unit | Back rounding, hitching, lockout checks |

### Test Fixture Approach

Follow the existing `PoseResultTestFixtures.kt` pattern. Create synthetic landmark
sequences that simulate:

**Bench press**:
- Clean rep (bar descends to chest, presses to lockout)
- Flared elbows (shoulder-elbow angle > 80°)
- Asymmetric press (left wrist 4cm higher than right)
- Incomplete lockout (elbow angle 140° at top)

**Deadlift**:
- Clean conventional pull (hips and shoulders rise together)
- Back rounding (shoulder-hip angle increases >15° during pull)
- Hitching (hips stall and restart)
- Incomplete lockout (hips not fully extended)
- Touch-and-go reps (brief pause at bottom)

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Bench press: wrists occluded by plates in side view | Use elbow position as fallback when wrist visibility < threshold |
| Deadlift: conventional vs sumo have very different mechanics | Start with conventional only; detect stance width later to switch rules |
| Back rounding false positives | Use angle *change* not absolute angle; require >15° deviation from start |
| Hitching detection too sensitive | Require velocity to drop below threshold for >3 consecutive frames |
| Adding fields to ExercisePhaseState bloats it | Fields are nullable, cost is negligible; revisit if it exceeds 20 fields |

## Future Extensions

- **Sumo Deadlift variant** — detect wide stance via ankle X distance, adjust form rules
- **Paused Bench** — detect and time the pause at chest (for competition training)
- **Board Press** — detect partial ROM intentionally (overload technique)
- **Deficit Deadlift** — standing on elevated surface, lower starting position
