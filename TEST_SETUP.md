# Test Setup Guide - Fixing JDK Issue

## Problem

The tests cannot run because your system has **OpenJDK JRE** but not the full **OpenJDK JDK**. The `jlink` tool is missing, which is required for Android Gradle Plugin to build the test environment.

**Error Message:**
```
jlink executable /usr/lib/jvm/java-21-openjdk-amd64/bin/jlink does not exist
```

## Solution

Install the full OpenJDK 21 JDK (not just JRE):

```bash
sudo apt-get update
sudo apt-get install openjdk-21-jdk
```

This will install the complete JDK including the `jlink` tool.

## Verify Installation

After installation, verify:

```bash
# Check jlink exists
ls -la /usr/lib/jvm/java-21-openjdk-amd64/bin/jlink

# Should show: -rwxr-xr-x ... jlink

# Check Java version
java -version
# Should show: openjdk version "21.0.x"
```

## Run Tests

Once the JDK is installed, run the tests:

```bash
# Clean build first
./gradlew clean

# Run all unit tests
./gradlew :app:testDebugUnitTest

# View results
```

## Alternative: Use JDK 17

If you prefer to use JDK 17 (which is the recommended version for Android development):

```bash
# Install JDK 17
sudo apt-get install openjdk-17-jdk

# Set as default
sudo update-alternatives --config java
# Select java-17

sudo update-alternatives --config javac
# Select javac-17

# Verify
java -version
# Should show: openjdk version "17.x.x"

# Update JAVA_HOME in your shell config (~/.bashrc or ~/.zshrc)
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
```

## Expected Test Results

Once tests run successfully, you should see:

```
✅ AnthropometricDataTest: 12 tests passed
✅ CameraAlignmentCheckerTest: 11 tests passed

Total: 23 tests, 23 passed, 0 failed
```

## Key Tests to Monitor

### Critical Tests (validate bug fixes):

1. **testLandscapeBackCamera_correctMeasurements**
   - ✅ Shoulder width should be ~40cm (not ~15cm)
   - ✅ Hip width should be ~31cm (not ~9cm)

2. **testMeasurementsConsistentAcrossOrientations**
   - ✅ Variance across portrait/landscape < 5cm
   - Prints comparison table

3. **testAspectRatioScaling_portraitVsLandscape**
   - ✅ Ratios should match within 3%

4. **testAspectRatioCorrection_shoulderWidthRatio**
   - ✅ CameraAlignmentChecker ratios consistent

### Sample Output:

```
Portrait shoulder width: 40.2cm, Landscape shoulder width: 39.8cm
Portrait hip width: 31.1cm, Landscape hip width: 30.9cm
Shoulder widths: [40.2, 39.8, 40.0, 39.9] (variance: 0.4 cm) ✅
Hip widths: [31.1, 30.9, 31.0, 31.0] (variance: 0.2 cm) ✅
```

## Troubleshooting

### Tests still fail after JDK installation

1. **Clean Gradle caches:**
   ```bash
   ./gradlew clean
   rm -rf ~/.gradle/caches/
   rm -rf .gradle/
   ```

2. **Invalidate Android Studio caches:**
   - File → Invalidate Caches → Invalidate and Restart

3. **Check JAVA_HOME:**
   ```bash
   echo $JAVA_HOME
   # Should point to JDK directory, not JRE
   ```

### Specific test failures

If a specific test fails, check the detailed report:
```bash
cat app/build/reports/tests/testDebugUnitTest/classes/com.lifthelper.domain.model.AnthropometricDataTest.html
```

### Build errors

If you see compilation errors:
```bash
# Try with stacktrace
./gradlew :app:testDebugUnitTest --stacktrace

# Or with debug output
./gradlew :app:testDebugUnitTest --debug > build.log 2>&1
```

## Next Steps After Tests Pass

1. **Review test output** - Check all 23 tests passed
2. **Verify measurements** - Confirm landscape mode shows ~40cm shoulder width
3. **Test on device** - Run actual app in both orientations
4. **Check real measurements** - Use anthropometric calibration and verify accuracy

## Additional Information

- **Test files:** `app/src/test/java/com/lifthelper/`
- **Test fixtures:** `PoseResultTestFixtures.kt`
- **Test count:** 23 unit tests
- **Coverage:** All orientations, both cameras, edge cases
- **Documentation:** See `TESTING_GUIDE.md` for details

---

**Quick Reference:**

```bash
# Install JDK
sudo apt-get install openjdk-21-jdk

# Run tests
./gradlew :app:testDebugUnitTest

# View report
open app/build/reports/tests/testDebugUnitTest/index.html
```
