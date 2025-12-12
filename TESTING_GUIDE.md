# Body Measurement Testing Guide

This document describes the comprehensive test suite for anthropometric body measurements across all device orientations and camera configurations.

## Test Overview

The test suite validates that body measurements (shoulder width, hip width, knee width, etc.) are **accurate and consistent** across:

- ✅ **Portrait orientation** (phone held vertically)
- ✅ **Landscape orientation** (phone held horizontally)
- ✅ **Front camera** (selfie mode)
- ✅ **Back camera** (standard mode)
- ✅ **Different resolutions** (low-res 640x480, high-res 1920x1080, etc.)
- ✅ **Edge cases** (tilted camera, side view, partial body visible)

## Test Files

### 1. **PoseResultTestFixtures.kt**
Location: `app/src/test/java/com/lifthelper/domain/model/PoseResultTestFixtures.kt`

**Purpose:** Provides factory methods to create realistic `PoseResult` test data

**Key Functions:**
- `createIdealFrontalPose()` - Perfect frontal stance with all landmarks visible
- `createPortraitPose()` / `createLandscapePose()` - Common orientation presets
- `createTiltedPose()` - Camera tilted at specified angle
- `createSideViewPose()` - Person turned sideways (narrow shoulders/hips)
- `createPartialBodyPose()` - Only upper body visible (lower body missing)

**Landmark Configuration:**
- 33 MediaPipe pose landmarks (nose, shoulders, hips, knees, ankles, etc.)
- Realistic body proportions based on typical human anthropometry:
  - Shoulder width: ~23% of height (~40cm for 175cm person)
  - Hip width: ~18% of height (~31cm)
  - Knee spacing: ~10% of height (~17cm)

**Aspect Ratio Handling:**
```kotlin
// Portrait: imageHeight > imageWidth
// Landscape: imageWidth > imageHeight
// Widths are scaled inversely to aspect ratio in normalized space
val shoulderWidthX = if (isPortrait) {
    shoulderWidthRatio / aspectRatio  // Smaller X in portrait
} else {
    shoulderWidthRatio  // Normal X in landscape
}
```

### 2. **AnthropometricDataTest.kt**
Location: `app/src/test/java/com/lifthelper/domain/model/AnthropometricDataTest.kt`

**Purpose:** Tests the core measurement calculation logic

**Test Count:** 12 comprehensive tests

#### Critical Tests:

**Test 1: `testPortraitBackCamera_correctMeasurements()`**
- Most common use case
- Verifies shoulder width ~40cm, hip width ~31cm, knee width ~17cm
- Checks calibration quality is OPTIMAL
- Validates tolerance ±5cm for widths, ±3cm for heights

**Test 2: `testLandscapeBackCamera_correctMeasurements()` ⭐ CRITICAL**
- **This test validates the aspect ratio bug fix**
- Before fix: shoulder width was ~15cm, hip width was ~9cm ❌
- After fix: shoulder width ~40cm, hip width ~31cm ✅
- Ensures landscape measurements match portrait measurements

**Test 3-4: Front Camera Tests**
- Validates front camera (mirrored) produces same measurements
- Ensures mirroring doesn't affect width calculations

**Test 5-7: Resolution Tests**
- Square aspect ratio (1:1)
- Low resolution (480x640, 640x480)
- Validates measurements are resolution-independent

**Test 8: `testMeasurementsConsistentAcrossOrientations()` ⭐ MOST CRITICAL**
- **Master validation test**
- Creates poses in all 4 configurations:
  - Portrait + Back Camera
  - Landscape + Back Camera
  - Portrait + Front Camera
  - Landscape + Front Camera
- Measures all 4 and validates variance < 5cm
- Prints comparison for debugging

**Test 9: Partial Body**
- Missing lower body (knees/ankles)
- Should still measure shoulders correctly
- Quality should be ESTIMATED, not OPTIMAL

**Test 10: `testAspectRatioScaling_portraitVsLandscape()` ⭐ BUG FIX VALIDATION**
- **Direct test of the aspect ratio fix**
- Compares ratios (not absolute values) between portrait and landscape
- Before fix: ratios were different by ~1.78x (aspect ratio)
- After fix: ratios should be equal within 3% tolerance

**Test 11: Missing Critical Landmarks**
- Tests robustness when shoulders/hips are not visible
- Should return `null` instead of bad data

**Test 12: Sanity Checks**
- Validates measurements are in realistic ranges:
  - Hip width: 5-25% of height
  - Knee width: 3-20% of height

### 3. **CameraAlignmentCheckerTest.kt**
Location: `app/src/test/java/com/lifthelper/vision/calculator/CameraAlignmentCheckerTest.kt`

**Purpose:** Tests camera alignment detection and guidance

**Test Count:** 11 tests

#### Key Tests:

**Test 1-2: Ideal Pose Detection**
- Portrait and landscape ideal poses should have `AlignmentIssue.NONE`
- Tilt angle < 5 degrees
- Shoulder width ratio > 0.15 (15% of height)

**Test 3: Front vs Back Camera Consistency**
- Alignment detection should work identically for both cameras
- Validates shoulder width ratio is camera-independent

**Test 4: Tilted Pose Detection**
- 15-degree tilt should be detected as `TILTED` or `TILTED_AND_SIDE`
- Tilt angle > 8 degrees threshold
- Should provide guidance message ("Hold phone upright")

**Test 5: Side View Detection**
- Narrow shoulders (< 15% of height) detected as `SIDE_VIEW`
- Should provide guidance ("Face the camera directly")

**Test 6: Cross-Orientation Consistency**
- Tilt detection works in both portrait and landscape
- Ensures orientation doesn't affect alignment checks

**Test 7: `testAspectRatioCorrection_shoulderWidthRatio()` ⭐ BUG FIX VALIDATION**
- **Tests the CameraAlignmentChecker aspect ratio fix**
- Shoulder width ratio should be consistent (±3%) across orientations
- Before fix: landscape was ~1.78x different
- After fix: should match within tolerance

**Test 8: Small Tilt Not Flagged**
- 5-degree tilt is acceptable, should not be flagged
- Threshold is 8 degrees

**Test 9: Guidance Messages**
- Validates appropriate messages for each issue type
- Checks for keywords like "upright", "camera", "good"

**Test 10: Shoulder/Hip Ratio**
- Validates ratio calculation (should be 0.8-1.5 for frontal view)

**Test 11: `testAllCombinations_matrix()` ⭐ COMPREHENSIVE**
- Tests all combinations:
  - Portrait/Landscape × Front/Back × Square
  - 6 total combinations
- Prints results matrix for debugging
- All ideal poses should have `NONE` alignment issue

## Running the Tests

### Prerequisites
The project requires JDK 17 for building. If you encounter JDK issues:

```bash
# Check Java version
java -version  # Should be 17.x

# If using SDKMAN
sdk use java 17.0.x

# If using update-alternatives
sudo update-alternatives --config java
```

### Run All Unit Tests
```bash
./gradlew :app:testDebugUnitTest
```

### Run Specific Test Class
```bash
./gradlew :app:testDebugUnitTest --tests "com.lifthelper.domain.model.AnthropometricDataTest"
./gradlew :app:testDebugUnitTest --tests "com.lifthelper.vision.calculator.CameraAlignmentCheckerTest"
```

### Run Single Test Method
```bash
./gradlew :app:testDebugUnitTest --tests "*.AnthropometricDataTest.testMeasurementsConsistentAcrossOrientations"
```

### View Test Results
After running tests, open:
```
app/build/reports/tests/testDebugUnitTest/index.html
```

## Expected Test Results

### All Tests Should Pass ✅

If any test fails, it indicates a regression in:
- Aspect ratio calculations
- Orientation handling
- Camera mirroring logic
- Measurement accuracy

### Key Metrics

**Expected Measurements for 175cm Person:**
- Shoulder width: 40cm ± 5cm
- Hip width: 31cm ± 5cm
- Knee width: 17cm ± 5cm
- Total height: 175cm ± 3cm

**Expected Ratios (orientation-independent):**
- Shoulder width: ~23% of height
- Hip width: ~18% of height
- Knee width: ~10% of height

**Variance Across Orientations:**
- Shoulder width: < 5cm variance between portrait/landscape
- Hip width: < 5cm variance
- Ratios: < 3% variance

## Test Coverage

### What's Tested ✅
- ✅ Portrait orientation (1080x1920)
- ✅ Landscape orientation (1920x1080)
- ✅ Square aspect ratio (1080x1080)
- ✅ Low resolution (480x640, 640x480)
- ✅ Front camera (mirrored)
- ✅ Back camera (normal)
- ✅ Tilted camera (15 degrees)
- ✅ Side view (turned sideways)
- ✅ Partial body (missing lower body)
- ✅ Missing landmarks (no shoulders)
- ✅ Aspect ratio corrections
- ✅ Sanity checks
- ✅ Calibration quality assessment
- ✅ Alignment detection
- ✅ Guidance messages

### What's NOT Tested ❌
- ❌ Actual camera hardware
- ❌ MediaPipe pose detection accuracy
- ❌ UI rendering
- ❌ Camera permissions
- ❌ Image rotation/processing
- ❌ Real-time performance
- ❌ Database persistence
- ❌ Integration with ViewModels

These require **instrumented tests** (androidTest) which run on a device/emulator.

## Troubleshooting

### Test Failures

**Symptom:** Width measurements are too small in landscape (e.g., 9cm instead of 31cm)
- **Cause:** Aspect ratio bug is back
- **Fix:** Check `AnthropometricData.kt` lines 330-358 for aspect ratio logic
- **Expected:** Landscape should NOT divide by aspectRatio for X widths

**Symptom:** Measurements inconsistent between portrait and landscape
- **Cause:** Aspect ratio correction is wrong
- **Fix:** Verify `shoulderWidthEstimated` calculation in both orientations
- **Test:** Run `testMeasurementsConsistentAcrossOrientations` to diagnose

**Symptom:** Front camera measurements differ from back camera
- **Cause:** Mirroring logic error
- **Fix:** Check that only X coordinates are mirrored, not measurements
- **Test:** Run `testFrontCamera_sameAsBackCamera`

### Build Issues

**JDK Image Transform Error:**
```
jlink executable does not exist
```
- Install JDK 17 (not just JRE)
- Set JAVA_HOME to JDK directory
- Ensure jlink binary exists in JDK bin/

**Compilation Errors:**
- Run `./gradlew clean` first
- Delete `.gradle` and `build` directories
- Invalidate caches in Android Studio

## Contributing

When adding new orientation/camera features:

1. **Add test fixtures** to `PoseResultTestFixtures.kt`
2. **Add unit tests** to `AnthropometricDataTest.kt`
3. **Validate consistency** across all orientations
4. **Check aspect ratio** handling is correct
5. **Run full test suite** before committing

## Test Maintenance

### When to Update Tests

**Add tests when:**
- Adding new body measurements (arm length, torso height, etc.)
- Supporting new camera modes (ultra-wide, telephoto)
- Changing aspect ratio calculations
- Adding new orientations (upside-down, etc.)

**Update fixtures when:**
- MediaPipe landmark indices change
- Anthropometric standards change
- Adding support for different body types

**Update tolerances when:**
- Improving measurement accuracy
- Better calibration methods
- Higher resolution cameras

## References

- [MediaPipe Pose Landmark Model](https://developers.google.com/mediapipe/solutions/vision/pose_landmarker)
- [Android CameraX Orientation Handling](https://developer.android.com/training/camerax/orientation-rotation)
- [JUnit 4 Documentation](https://junit.org/junit4/)
- [Kotlin Test](https://kotlinlang.org/api/latest/kotlin.test/)

---

**Last Updated:** 2025-12-12
**Test Coverage:** 23 unit tests across 2 test classes
**Critical Bug Validated:** Landscape mode aspect ratio fix ✅
