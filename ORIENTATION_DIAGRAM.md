# Orientation Handling Visual Guide

## Supported Orientations

```
┌─────────────────────────────────────────────────────────────┐
│                    SUPPORTED ORIENTATIONS                    │
└─────────────────────────────────────────────────────────────┘

   Portrait (Normal)              Landscape (Right)         Landscape (Left)

   ┌───────────┐                  ┌─────────────────┐      ┌─────────────────┐
   │  ○ ○      │                  │    🔴           │      │           🔴    │
   │  ╱─╲      │                  │   ╱─╲          │      │          ╱─╲   │
   │   │       │                  │    │           │      │           │    │
   │  ╱ ╲      │                  │   ╱ ╲          │      │          ╱ ╲   │
   │           │                  │                │      │                │
   │   📱      │                  └─────────────────┘      └─────────────────┘
   │           │                         📱                       📱
   │           │                    (rotated right)          (rotated left)
   └───────────┘

   ✅ ALLOWED                      ✅ ALLOWED                ✅ ALLOWED
   0-44° or 315-359°               225-314°                  45-134°


   Portrait Inverted

        📱
   ┌───────────┐
   │           │
   │   ╲ ╱     │
   │    │      │
   │   ╲─╱     │
   │  ○ ○ 🔴   │
   └───────────┘

   ❌ BLOCKED
   135-224°
```

---

## Camera Configurations

```
┌─────────────────────────────────────────────────────────────┐
│                4 SUPPORTED CONFIGURATIONS                    │
└─────────────────────────────────────────────────────────────┘

Configuration 1: Portrait + Back Camera
┌───────────┐
│  📷       │  ← Back camera on top
│           │
│   👤      │  ← User faces camera
│           │
│     📱    │
└───────────┘

Configuration 2: Portrait + Front Camera (Mirrored)
┌───────────┐
│     📱    │
│           │
│   👤 📷   │  ← Front camera (selfie mode)
│           │
│           │
└───────────┘

Configuration 3: Landscape + Back Camera
┌──────────────────┐
│ 📷              📱│  ← Back camera on left
│                  │
│       👤         │  ← User faces camera
│                  │
└──────────────────┘

Configuration 4: Landscape + Front Camera (Mirrored)
┌──────────────────┐
│📱              📷 │  ← Front camera on right
│                  │
│       👤         │  ← User faces camera
│                  │
└──────────────────┘
```

---

## Sensor Orientation → Screen Orientation Mapping

```
        0° (Portrait Normal)
             ↑
             │
             │
315° ←───────┼───────→ 45°
             │
             │
        ALLOWED


             │
270° ←───────┼───────→ 90°
             │
      Landscape      Landscape
        Left           Right
      ALLOWED        ALLOWED
             │

             │
225° ←───────┼───────→ 135°
             │
             │
        BLOCKED
        (Inverted)
             │
             ↓
           180°


Mapping Logic:
  0-44°    → Portrait Normal    ✅
  45-134°  → Landscape Left     ✅
  135-224° → Portrait Inverted  ❌ BLOCKED
  225-314° → Landscape Right    ✅
  315-359° → Portrait Normal    ✅
```

---

## Aspect Ratio Handling

### Portrait Mode (1080 × 1920)

```
┌─────────┐  ↑
│         │  │ 1920px (HEIGHT - longer dimension)
│         │  │
│    📷   │  │
│         │  │
│   👤    │  │
│         │  │
│    📱   │  │
│         │  ↓
└─────────┘
  ← 1080 →
   WIDTH
   (narrow)

Aspect Ratio = 1920 / 1080 = 1.78

X-axis (width) is NARROW  → Scale by 1.78
Y-axis (height) is WIDE   → Use as-is

shoulderWidth = deltaX × 1.78
```

### Landscape Mode (1920 × 1080)

```
┌─────────────────────────────┐ ↑
│  📷       👤          📱    │ │ 1080px (HEIGHT - narrow)
└─────────────────────────────┘ ↓
  ← 1920px (WIDTH - longer) →

Aspect Ratio = 1920 / 1080 = 1.78

X-axis (width) is WIDE    → Use as-is
Y-axis (height) is NARROW → Scale by 1.78

shoulderWidth = deltaX  (no scaling needed)
height = deltaY × 1.78
```

---

## Coordinate System

### Normalized Coordinates (0.0 to 1.0)

MediaPipe pose landmarks use normalized coordinates:

```
(0, 0) ────────────────────→ (1, 0)
  │                              │
  │                              │
  │         👤 Pose              │
  │        ╱─╲                   │
  │         │                    │
  │        ╱ ╲                   │
  │                              │
  ↓                              ↓
(0, 1) ────────────────────→ (1, 1)

X: 0.0 (left) to 1.0 (right)
Y: 0.0 (top) to 1.0 (bottom)
```

### Distance Calculation

To get real-world distances, we need to:
1. Convert normalized coords to pixel coords
2. Apply aspect ratio correction
3. Scale using reference measurement (height)

```kotlin
// Portrait: X is narrow dimension
val dx = (x2 - x1) * aspectRatio  // Scale X
val dy = (y2 - y1)                // Y unchanged

// Landscape: Y is narrow dimension
val dx = (x2 - x1)                // X unchanged
val dy = (y2 - y1) * aspectRatio  // Scale Y

distance = sqrt(dx² + dy²)
```

---

## Real-World Example

### Measuring Shoulder Width

#### Portrait Mode (1080 × 1920)
```
Left Shoulder:  (0.3, 0.4) → (324px, 768px)
Right Shoulder: (0.7, 0.4) → (756px, 768px)

deltaX = 756 - 324 = 432px
deltaY = 768 - 768 = 0px

Aspect ratio = 1.78
deltaX_scaled = 432 × 1.78 = 769px

distance = sqrt(769² + 0²) = 769px
```

#### Landscape Mode (1920 × 1080)
```
Left Shoulder:  (0.3, 0.4) → (576px, 432px)
Right Shoulder: (0.7, 0.4) → (1344px, 432px)

deltaX = 1344 - 576 = 768px
deltaY = 432 - 432 = 0px

No scaling needed in landscape for X
distance = sqrt(768² + 0²) = 768px
```

**Result**: ~768-769px in both orientations ✅ Consistent!

---

## Alignment Detection

### Camera Tilt Detection

```
Tilted Left               Level                 Tilted Right

   👤                     👤                       👤
  ╱─╲                   ╱─╲                     ╱─╲
 ╱   │                   │                       │  ╲
╱     ╲                 ╱ ╲                     ╱    ╲

Shoulder angle: -15°   Shoulder angle: 0°    Shoulder angle: +15°
❌ ALERT              ✅ GOOD               ❌ ALERT

Max tilt allowed: ±10°
```

### Side View Detection

```
Front View (Good)         Side View (Bad)

    👤                       👤
   ╱─╲                       │
    │                       ╱│╲
   ╱ ╲                       │
                            ╱ ╲

Shoulder width: 45% of     Shoulder width: 15% of
body height ✅            body height ❌
```

Detection logic:
```kotlin
val shoulderWidthRatio = shoulderWidth / bodyHeight

if (shoulderWidthRatio < 0.15) {
    // Side view detected ❌
    return "Please face camera directly"
} else {
    // Front view ✅
    return "Good alignment"
}
```

---

## Testing Scenarios

### Scenario 1: Smooth Rotation
```
1. Start:      Portrait (normal)    ┌─────┐
                                    │  👤  │
                                    └─────┘

2. Rotate →    Landscape (right)    ┌─────────┐
                                    │   👤    │
                                    └─────────┘

3. Result:     UI adapts smoothly ✅
               Tracking continues ✅
```

### Scenario 2: Blocked Rotation
```
1. Start:      Portrait (normal)    ┌─────┐
                                    │  👤  │
                                    └─────┘

2. Flip ↓      Portrait (inverted)  ┌─────┐
                                    │  👤  │ ← Blocked!
                                    └─────┘

3. Result:     Stays in portrait ✅
               No orientation change ✅
```

### Scenario 3: Camera Switch
```
1. Start:      Back camera          ┌─────┐
               Portrait             │ 📷   │
                                    │  👤  │
                                    └─────┘

2. Switch:     Front camera         ┌─────┐
               Portrait             │  👤  │
                                    │ 📷   │
                                    └─────┘

3. Result:     Camera switches ✅
               Orientation preserved ✅
               Mirroring applied ✅
```

---

## Implementation Flow

```
┌──────────────────────────────────────────────────────────┐
│ 1. Device Rotation                                       │
│    User rotates device to new angle                      │
└────────────────┬─────────────────────────────────────────┘
                 │
                 ↓
┌──────────────────────────────────────────────────────────┐
│ 2. OrientationEventListener (MainActivity)               │
│    - Detects sensor orientation (0-359°)                 │
│    - Maps to screen orientation                          │
│    - Blocks if inverted portrait (135-224°)              │
└────────────────┬─────────────────────────────────────────┘
                 │
                 ↓
┌──────────────────────────────────────────────────────────┐
│ 3. Configuration Change                                  │
│    - Android system updates configuration                │
│    - UI recomposes with new orientation                  │
└────────────────┬─────────────────────────────────────────┘
                 │
                 ↓
┌──────────────────────────────────────────────────────────┐
│ 4. OrientationUtils                                      │
│    - rememberOrientation() detects PORTRAIT/LANDSCAPE    │
│    - UI adapts layout accordingly                        │
└────────────────┬─────────────────────────────────────────┘
                 │
                 ↓
┌──────────────────────────────────────────────────────────┐
│ 5. CameraManager                                         │
│    - Updates display rotation                            │
│    - Restarts camera preview if needed                   │
└────────────────┬─────────────────────────────────────────┘
                 │
                 ↓
┌──────────────────────────────────────────────────────────┐
│ 6. Pose Detection                                        │
│    - Receives frames in correct orientation              │
│    - Applies aspect ratio correction                     │
│    - Calculates measurements consistently                │
└──────────────────────────────────────────────────────────┘
```

---

## Troubleshooting

### Issue: Measurements inconsistent between orientations

**Diagnosis**:
```bash
# Check aspect ratio calculation
grep "aspectRatio" app/src/main/java/com/lifthelper/domain/model/AnthropometricData.kt

# Run consistency test
./gradlew :app:testDebugUnitTest --tests "*testMeasurementsConsistentAcrossOrientations"
```

**Expected**: Variance < 3%

---

### Issue: Portrait inverted not blocked

**Diagnosis**:
- Check `OrientationEventListener` is enabled
- Verify sensor orientation range (135-224°)
- Test with device rotation lock OFF

**Fix**: Ensure `setupOrientationListener()` is called in `MainActivity.onCreate()`

---

### Issue: Camera preview rotated incorrectly

**Diagnosis**:
- Check `displayRotation` in CameraManager
- Verify CameraX `setTargetRotation()`

**Fix**: Ensure rotation passed to both Preview and ImageAnalysis use cases

---

## Summary

✅ **4 configurations supported** (Portrait/Landscape × Back/Front)
❌ **Inverted orientations blocked** (better UX, simpler code)
📐 **Aspect ratio handling** ensures consistent measurements
🧪 **Comprehensive tests** verify correctness
📱 **Smooth transitions** between allowed orientations
