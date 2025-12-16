# Orientation and Camera Configuration Test Plan

## Overview
This document provides a comprehensive test plan for verifying the app's functionality across all supported device orientations and camera configurations.

## Supported Configurations

The app supports **4 orientation/camera combinations**:

1. **Portrait + Back Camera**
2. **Portrait + Front Camera** (mirrored)
3. **Landscape + Back Camera**
4. **Landscape + Front Camera** (mirrored)

### Blocked Orientations

The following orientations are **explicitly blocked** to reduce complexity and improve user experience:
- ❌ Portrait Inverted (phone upside-down)
- ❌ Landscape Inverted

**Rationale**: Inverted orientations provide no practical benefit for exercise tracking and would double the test matrix without adding value.

**Implementation**: [MainActivity.kt:45-78](app/src/main/java/com/lifthelper/MainActivity.kt#L45-L78)

---

## Test Matrix

### 1. Body Measurement Calibration

Test anthropometric measurements in all 4 configurations:

| Configuration | Test ID | Camera | Orientation | Expected Result |
|--------------|---------|---------|-------------|-----------------|
| Config 1 | BMC-1 | Back | Portrait | Accurate measurements |
| Config 2 | BMC-2 | Front | Portrait | Accurate measurements (mirrored) |
| Config 3 | BMC-3 | Back | Landscape | Accurate measurements |
| Config 4 | BMC-4 | Front | Landscape | Accurate measurements (mirrored) |

**Test Procedure**:
1. Launch anthropometric calibration screen
2. Switch to specified camera
3. Rotate device to specified orientation
4. Stand 2.5-3m from camera, full body visible
5. Verify measurements are captured accurately
6. Check alignment feedback is correct

**Critical Checks**:
- ✅ Shoulder width: 35-50 cm (typical adult range)
- ✅ Hip width: 28-40 cm
- ✅ Height: matches actual height (±5%)
- ✅ Aspect ratio correctly applied
- ✅ Camera alignment detection works
- ✅ Pose overlay renders correctly

**Automated Tests**: `AnthropometricDataTest.kt`
```bash
./gradlew :app:testDebugUnitTest --tests "*AnthropometricDataTest*"
```

---

### 2. Exercise Tracking - Front Squat

Test front squat rep counting in all 4 configurations:

| Configuration | Test ID | Camera | Orientation | Expected Result |
|--------------|---------|---------|-------------|-----------------|
| Config 1 | FS-1 | Back | Portrait | Rep counting works |
| Config 2 | FS-2 | Front | Portrait | Rep counting works |
| Config 3 | FS-3 | Back | Landscape | Rep counting works |
| Config 4 | FS-4 | Front | Landscape | Rep counting works |

**Test Procedure**:
1. Complete body measurement calibration first
2. Navigate to workout screen
3. Select "Front Squat" exercise
4. Switch to specified camera
5. Rotate device to specified orientation
6. Position 2-3m from camera, side view preferred
7. Perform 5 front squats
8. Verify rep counter increments correctly

**Critical Checks**:
- ✅ Rep counter increments on each full rep
- ✅ Depth detection works (hip below knee)
- ✅ Standing position detected correctly
- ✅ Pose overlay shows correct joint positions
- ✅ Feedback messages appear correctly

---

### 3. Cross-Configuration Consistency

Test that measurements remain consistent across different configurations:

| Test ID | Description | Success Criteria |
|---------|-------------|------------------|
| CC-1 | Shoulder width variance | Variance ≤ 3% across all 4 configs |
| CC-2 | Hip width variance | Variance ≤ 3% across all 4 configs |
| CC-3 | Height variance | Variance ≤ 5% across all 4 configs |
| CC-4 | Rep count consistency | Same reps detected in all configs |

**Test Procedure**:
1. Measure body in all 4 configurations
2. Record measurements for each
3. Calculate variance: `max - min / average`
4. Verify variance within acceptable range

**Automated Tests**:
```bash
./gradlew :app:testDebugUnitTest --tests "*testMeasurementsConsistentAcrossOrientations"
```

---

### 4. Orientation Transition Tests

Test behavior when rotating device during active session:

| Test ID | Scenario | Expected Behavior |
|---------|----------|-------------------|
| OT-1 | Portrait → Landscape during calibration | UI adapts, measurements continue |
| OT-2 | Landscape → Portrait during calibration | UI adapts, measurements continue |
| OT-3 | Portrait → Landscape during workout | UI adapts, rep counting continues |
| OT-4 | Landscape → Portrait during workout | UI adapts, rep counting continues |
| OT-5 | Attempt Portrait Inverted | Orientation blocked, stays in Portrait |
| OT-6 | Attempt Landscape Inverted | Orientation blocked, stays in Landscape |

**Test Procedure**:
1. Start calibration or workout
2. Rotate device to new orientation
3. Verify UI adapts smoothly
4. Verify tracking continues without reset
5. Verify no crashes or visual glitches

---

### 5. Camera Switching Tests

Test camera switching in different orientations:

| Test ID | Initial State | Action | Expected Result |
|---------|--------------|---------|-----------------|
| CS-1 | Portrait, Back | Switch camera | Front camera, Portrait |
| CS-2 | Portrait, Front | Switch camera | Back camera, Portrait |
| CS-3 | Landscape, Back | Switch camera | Front camera, Landscape |
| CS-4 | Landscape, Front | Switch camera | Back camera, Landscape |

**Test Procedure**:
1. Start in specified orientation/camera
2. Tap camera switch button
3. Verify camera switches smoothly
4. Verify mirroring correct for front camera
5. Verify pose detection continues

---

### 6. Aspect Ratio and Coordinate Transform Tests

Verify correct aspect ratio handling in all configurations:

| Test ID | Configuration | Critical Check |
|---------|--------------|----------------|
| AR-1 | Portrait, Back | X coordinates scaled by aspect ratio |
| AR-2 | Portrait, Front | X coordinates scaled + mirrored |
| AR-3 | Landscape, Back | Y coordinates scaled by aspect ratio |
| AR-4 | Landscape, Front | Y coordinates scaled + mirrored |

**Implementation References**:
- Aspect ratio calculation: [AnthropometricData.kt:99-107](app/src/main/java/com/lifthelper/domain/model/AnthropometricData.kt#L99-L107)
- Distance calculation: [AnthropometricData.kt:193-207](app/src/main/java/com/lifthelper/domain/model/AnthropometricData.kt#L193-L207)
- Alignment checking: [CameraAlignmentChecker.kt:76-88](app/src/main/java/com/lifthelper/vision/calculator/CameraAlignmentChecker.kt#L76-L88)

**Automated Tests**:
```bash
./gradlew :app:testDebugUnitTest --tests "*CameraAlignmentCheckerTest*"
```

---

## Test Execution Strategy

### Phase 1: Automated Unit Tests
Run all existing unit tests to verify coordinate transforms and measurements:

```bash
# Run all tests
./gradlew :app:testDebugUnitTest

# Critical tests only
./gradlew :app:testDebugUnitTest --tests "*testMeasurementsConsistentAcrossOrientations"
./gradlew :app:testDebugUnitTest --tests "*CameraAlignmentCheckerTest*"

# View results
open app/build/reports/tests/testDebugUnitTest/index.html
```

**Expected Results**:
- ✅ All tests pass
- ✅ Shoulder width variance < 3% across orientations
- ✅ Hip width variance < 3% across orientations
- ✅ Alignment detection consistent across orientations

### Phase 2: Manual Device Testing

Perform manual testing on physical device for each configuration:

**Test Sequence**:
1. Body Measurement Calibration (4 configs)
2. Front Squat Exercise (4 configs)
3. Orientation Transitions (6 scenarios)
4. Camera Switching (4 scenarios)

**Test Device Requirements**:
- Android device with both front and back cameras
- Sufficient space (3m) to step back from device
- Good lighting conditions

**Recording Results**:
Use the test result template below for each configuration.

---

## Test Result Template

```markdown
### Configuration: [Portrait/Landscape] + [Back/Front] Camera

**Date**: YYYY-MM-DD
**Tester**: [Name]
**Device**: [Model]
**Android Version**: [X.X]

#### Body Measurement Calibration
- [ ] Calibration completes successfully
- [ ] Shoulder width: _____ cm (expected: 35-50 cm)
- [ ] Hip width: _____ cm (expected: 28-40 cm)
- [ ] Height: _____ cm (expected: ±5% of actual)
- [ ] Alignment feedback works correctly
- [ ] Pose overlay renders correctly

#### Front Squat Exercise
- [ ] Rep counting works
- [ ] Performed: 5 reps | Detected: _____ reps
- [ ] Depth detection accurate
- [ ] Feedback messages correct
- [ ] No visual glitches

#### Issues Encountered
[List any issues, bugs, or unexpected behavior]

#### Screenshots
[Attach screenshots if needed]
```

---

## Known Implementations

### Orientation Detection
- File: [OrientationUtils.kt](app/src/main/java/com/lifthelper/util/OrientationUtils.kt)
- Supports: `PORTRAIT`, `LANDSCAPE`
- Usage: `rememberOrientation()` in Compose screens

### Orientation Restrictions
- File: [MainActivity.kt](app/src/main/java/com/lifthelper/MainActivity.kt)
- Blocks inverted orientations programmatically
- Allows smooth transitions between standard orientations

### Camera Configuration
- File: [CameraManager.kt](app/src/main/java/com/lifthelper/vision/camera/CameraManager.kt)
- 16:9 aspect ratio for all configurations
- Handles display rotation correctly
- Supports front/back camera switching

### Coordinate Transforms
- File: [AnthropometricData.kt](app/src/main/java/com/lifthelper/domain/model/AnthropometricData.kt)
- Aspect ratio-aware distance calculations
- Orientation-specific scaling (X in portrait, Y in landscape)
- Consistent across all 4 configurations

---

## Success Criteria

The implementation is considered successful when:

1. **All automated tests pass** (100% pass rate)
2. **Measurements are consistent** across all 4 configurations (variance ≤ 3%)
3. **Rep counting works** in all 4 configurations
4. **Orientation transitions** are smooth without crashes
5. **Camera switching** works in all orientations
6. **Inverted orientations** are blocked as expected
7. **No visual glitches** in UI or pose overlay

---

## Rollback Plan

If critical issues are found:

1. **Revert orientation restrictions**: Set `android:screenOrientation="unspecified"` in AndroidManifest.xml
2. **Remove orientation listener**: Comment out `setupOrientationListener()` in MainActivity
3. **Test with all orientations**: Expand test matrix to include inverted orientations
4. **Fix underlying issues**: Address root causes before re-applying restrictions

---

## Next Steps

1. ✅ Run automated unit tests
2. ⏸ Perform manual device testing (4 configs × 2 test types = 8 manual tests)
3. ⏸ Document results using test result template
4. ⏸ Fix any issues found
5. ⏸ Verify fixes don't break other configurations
6. ⏸ Mark feature as complete
