# LiftHelper Logging Guide

## Filtering App-Specific Logs

All LiftHelper logs use the prefix `LiftHelper_` followed by a component name. This makes it easy to filter out system noise.

### Using ADB Logcat

#### View ALL LiftHelper logs:
```bash
adb logcat | grep "LiftHelper_"
```

#### View specific components:

**Camera operations:**
```bash
adb logcat | grep "LiftHelper_Camera"
```

**Workout & Recording:**
```bash
adb logcat | grep "LiftHelper_Workout"
```

**Pose Detection:**
```bash
adb logcat | grep "LiftHelper_Pose"
```

**Velocity Calculations:**
```bash
adb logcat | grep "LiftHelper_Velocity"
```

**Front Squat Body Part Tracking:**
```bash
adb logcat | grep "Front Squat"
```

**Calibration:**
```bash
adb logcat | grep "LiftHelper_Calibration"
```

**Movement Detection:**
```bash
adb logcat | grep "LiftHelper_Movement"
```

#### View multiple components at once:
```bash
adb logcat | grep -E "LiftHelper_(Camera|Workout)"
```

#### Filter by log level:
```bash
# Only errors and warnings
adb logcat *:E *:W | grep "LiftHelper_"

# Info and above
adb logcat *:I | grep "LiftHelper_"

# Debug and above (verbose)
adb logcat *:D | grep "LiftHelper_"
```

#### Save logs to file:
```bash
adb logcat | grep "LiftHelper_" > lifthelper_logs.txt
```

#### Clear previous logs and start fresh:
```bash
adb logcat -c && adb logcat | grep "LiftHelper_"
```

## Key Log Messages to Watch For

### Camera Lifecycle:
- ✅ `Camera started successfully` - Camera initialized properly
- ✅ `Camera stopped` - Camera shut down cleanly
- ❌ `Camera initialization failed` - Check permissions or device issues
- `Camera FPS: X.X` - Monitor performance (logged every 30 frames)

### Recording Session:
- ▶️ `START RECORDING` - Recording begins
- ⏹️ `STOP RECORDING` - Recording ends with total rep count
- ✅ `Rep completed: X` - New rep detected with velocity data
- **Note:** Reps are **only** counted when recording is active. Before pressing "Start Recording", the app shows the pose overlay but does not count reps.

### Pose Detection:
- ✅ `PoseLandmarker initialized successfully` - ML model loaded
- `Pose detections: X, skipped frames: Y` - Performance stats (every 30 detections)
- ⚠️ `No pose detected in frame` - Person not visible or lighting issues
- ⚠️ `No calibration data available` - User needs to calibrate first

### Phase Transitions:
- `Phase transition: IDLE -> ECCENTRIC` - Starting downward movement
- `Phase transition: ECCENTRIC -> BOTTOM` - Reached bottom position
- `Phase transition: BOTTOM -> CONCENTRIC` - Starting upward movement
- `Phase transition: CONCENTRIC -> TOP` - Completed rep

### Body Part Tracking (Front Squat):
- `Front Squat - Hips: X.XX m/s, Shoulders: X.XX m/s, Head: X.XX m/s | Instant Avg: X.XX m/s | Bar Speed: X.XX m/s` - Vertical velocity tracking
  - Shows individual vertical velocities for hips, shoulders, and head
  - **Instant Avg**: Average of all three body parts for stability
  - **Bar Speed**: Average of shoulders and head only (represents bar position)
- Logged every 30 frames when velocity exceeds 0.1 m/s

## Expected Log Flow

### Normal Workout Session:

1. **App Start:**
   ```
   LiftHelper_Pose: PoseDetector initializing...
   LiftHelper_Pose: ✅ PoseLandmarker initialized successfully
   LiftHelper_Workout: WorkoutViewModel initialized
   LiftHelper_Workout: Calibration data loaded: isCalibrated=true
   ```

2. **Camera Starts:**
   ```
   LiftHelper_Workout: CameraPreview composable created
   LiftHelper_Camera: startCamera() called
   LiftHelper_Camera: ✅ Camera started successfully
   LiftHelper_Camera: Camera FPS: 28.5 (every 30 frames)
   ```

3. **Before Recording (Preview Mode):**
   - Camera is running and showing preview
   - Pose overlay visible
   - **NO rep counting or phase transitions**
   - May see: "No pose detected (missed X frames)" if person not in frame

4. **Start Recording:**
   ```
   LiftHelper_Workout: ▶️ START RECORDING - Exercise: FRONT_SQUAT
   LiftHelper_Workout: Phase transition: IDLE -> ECCENTRIC
   LiftHelper_Workout: Phase transition: ECCENTRIC -> BOTTOM
   LiftHelper_Workout: Phase transition: BOTTOM -> CONCENTRIC
   LiftHelper_Workout: Phase transition: CONCENTRIC -> TOP
   LiftHelper_Workout: ✅ Rep completed: 1 (velocity: -17.5 m/s)
   ```

5. **Stop Recording:**
   ```
   LiftHelper_Workout: ⏹️ STOP RECORDING - Total reps: 5
   ```

6. **Navigate Away:**
   ```
   LiftHelper_Workout: DisposableEffect: CameraPreview disposing
   LiftHelper_Camera: stopCamera() called
   LiftHelper_Camera: ✅ Camera stopped (total frames processed: 450)
   ```

## Debugging Common Issues

### App crashes when pressing "Start Recording":

1. **Check camera initialization:**
   ```bash
   adb logcat | grep -E "LiftHelper_Camera|LiftHelper_Workout"
   ```
   Look for: "Camera started successfully" before "START RECORDING"

2. **Check for errors:**
   ```bash
   adb logcat *:E | grep "LiftHelper_"
   ```

3. **Full crash dump:**
   ```bash
   adb logcat | grep -E "LiftHelper_|AndroidRuntime"
   ```

### App crashes during recording (NoSuchElementException):

**Error:** `NoSuchElementException: Array contains no element matching the predicate`

**Cause:** Velocity calculation returning unexpected values (e.g., negative velocities for downward movement)

**Fix:** Now handles negative velocities using absolute value (fixed!)

**Debug:**
```bash
adb logcat | grep -E "LiftHelper_Velocity|AndroidRuntime"
```
Look for velocity values in logs to ensure they're reasonable.

### No reps being counted:

1. **Verify recording is active:**
   ```bash
   adb logcat | grep "START RECORDING"
   ```
   Reps are **only** counted after you see: `▶️ START RECORDING`

   **Common mistake:** Looking at the preview screen before pressing "Start Recording" - no reps will be counted!

2. **Check pose detection:**
   ```bash
   adb logcat | grep -E "LiftHelper_Pose|LiftHelper_Workout"
   ```
   Look for: "Pose detections" count increasing

3. **Check calibration:**
   ```bash
   adb logcat | grep "Calibration data loaded"
   ```
   Should show: `isCalibrated=true`

4. **Monitor phase transitions:**
   ```bash
   adb logcat | grep "Phase transition"
   ```
   Should see transitions through: IDLE → ECCENTRIC → BOTTOM → CONCENTRIC → TOP

   **If you see phase transitions BEFORE "START RECORDING":** You have a bug (now fixed!)

### Poor performance / Low FPS:

```bash
adb logcat | grep -E "Camera FPS|Pose detections"
```
- Camera FPS should be close to 25-30
- Pose detections should be ~15-20 per second (shown every 30 detections)
- Check "skipped frames" count

### No person detected / Pose detection issues:

```bash
adb logcat | grep -E "No pose detected|Pose detections"
```
- "No pose detected (missed X frames in last 2s)" - This is normal if no person is in frame
- Check pose detection success rate: Should see "Pose detections: 30" every ~2 seconds
- If detection rate is low (<50%), check lighting and camera position

## Log Tag Reference

| Tag | Purpose | Key Messages |
|-----|---------|--------------|
| `LiftHelper_App` | General app lifecycle | App start/stop |
| `LiftHelper_Camera` | Camera initialization & frames | Start/stop, FPS stats |
| `LiftHelper_Pose` | Pose detection ML | Model init, detection count |
| `LiftHelper_Workout` | Recording & rep counting | Start/stop, reps, phases |
| `LiftHelper_Velocity` | Velocity calculations | Speed measurements |
| `LiftHelper_Movement` | Movement phase detection | Phase transitions |
| `LiftHelper_Calibration` | User calibration | Height, pixel ratio |

## Advanced Filtering

### Monitor just the essentials:
```bash
adb logcat *:I | grep -E "LiftHelper_(Camera|Workout)" | grep -E "✅|❌|▶️|⏹️"
```

### Track full recording session:
```bash
adb logcat | grep -E "START RECORDING|Rep completed|STOP RECORDING"
```

### Performance monitoring:
```bash
adb logcat | grep -E "FPS|detections:|skipped"
```

### Android Studio Logcat Filter:
```
tag:LiftHelper_
```

Or for specific component:
```
tag:LiftHelper_Camera
```

## Performance Analysis

### Calculate Pose Detection Success Rate:

From your logs, calculate:
```
Pose Detection Rate = (Pose detections / Camera frames) × 100%
```

Example from logs:
- Camera frames: 420 frames in 14 seconds
- Pose detections: 90 detections in 14 seconds
- **Success rate: 21%** ⚠️ (Low - person may not be in frame or lighting issues)

Healthy metrics:
- ✅ **70-90%**: Excellent (person consistently in frame)
- ⚠️ **40-70%**: Moderate (check camera angle/lighting)
- 🔴 **<40%**: Poor (person not visible or major issues)

### Check Composable Recomposition:

Excessive recompositions cause performance issues:
```bash
adb logcat | grep "CameraPreview composable"
```
- Should see "CameraPreview composable created" **once** at startup
- If you see it hundreds of times, there's a performance issue (now fixed!)

### Frame Processing Time:

Monitor if frame processing is keeping up with camera:
```bash
adb logcat | grep -E "Camera FPS|Pose detections"
```
Calculate detection latency:
- Detection FPS = (Pose detections / time in seconds)
- Should be **~15 detections/sec** for good responsiveness
