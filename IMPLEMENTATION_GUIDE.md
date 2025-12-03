# Lift Helper - Implementation Guide for AI Agents

## Project Overview

This is an Android weightlifting training app that uses markerless computer vision (Google MediaPipe Pose) to measure:
- Bar velocity (instantaneous and average) during lifts
- Jump height using flight time method
- Real-time feedback with color-coded velocity zones
- Frame-by-frame velocity profiles stored in database

**Technology Stack:**
- Kotlin + Jetpack Compose
- CameraX for camera handling
- MediaPipe Pose for markerless tracking
- Room for data persistence
- Hilt for dependency injection
- Clean Architecture (Data → Domain → Presentation)

**Target:** Android 14+ (API 34), Min SDK 26

---

## Current Project State

### ✅ Completed Components

1. **Gradle Configuration** - All dependencies configured in:
   - [build.gradle.kts](build.gradle.kts) (root)
   - [app/build.gradle.kts](app/build.gradle.kts) (app module)

2. **Core Infrastructure**
   - [LiftHelperApplication.kt](app/src/main/java/com/lifthelper/LiftHelperApplication.kt) - Hilt application
   - [MainActivity.kt](app/src/main/java/com/lifthelper/MainActivity.kt) - Main activity
   - [di/AppModule.kt](app/src/main/java/com/lifthelper/di/AppModule.kt) - DI module with DataStore and executor
   - [util/Constants.kt](app/src/main/java/com/lifthelper/util/Constants.kt) - App constants

3. **Domain Models** (all in [domain/model/](app/src/main/java/com/lifthelper/domain/model/))
   - `ExerciseType.kt` - Exercise types enum
   - `CalibrationData.kt` - User calibration data
   - `VelocityData.kt` - Velocity tracking models + VelocityZone enum
   - `JumpData.kt` - Jump measurement data
   - `PoseKeypoint.kt` - MediaPipe pose models with landmark indices

4. **Data Layer**
   - [data/local/preferences/SettingsDataStore.kt](app/src/main/java/com/lifthelper/data/local/preferences/SettingsDataStore.kt) - Calibration persistence

5. **UI Theme**
   - [ui/theme/Theme.kt](app/src/main/java/com/lifthelper/ui/theme/Theme.kt)
   - [ui/theme/Color.kt](app/src/main/java/com/lifthelper/ui/theme/Color.kt)
   - [ui/theme/Type.kt](app/src/main/java/com/lifthelper/ui/theme/Type.kt)

6. **Resources**
   - [res/values/strings.xml](app/src/main/res/values/strings.xml) - All UI strings
   - [AndroidManifest.xml](app/src/main/AndroidManifest.xml) - Camera permissions configured

### 📁 Package Structure Created

```
app/src/main/java/com/lifthelper/
├── di/                          # Dependency injection
├── data/
│   ├── local/
│   │   ├── database/           # Room database (TODO)
│   │   │   ├── dao/
│   │   │   └── entities/
│   │   └── preferences/        # ✅ SettingsDataStore
│   └── repository/             # Repository implementations (TODO)
├── domain/
│   ├── model/                  # ✅ All domain models
│   ├── repository/             # Repository interfaces (TODO)
│   └── usecase/
│       └── workout/            # Use cases (TODO)
├── vision/                     # Computer vision module (TODO)
│   ├── camera/
│   ├── pose/
│   ├── tracking/
│   ├── calculator/
│   └── analyzer/
├── ui/
│   ├── theme/                  # ✅ Complete
│   ├── components/             # Reusable components (TODO)
│   ├── navigation/             # Navigation graph (TODO)
│   └── screens/                # Feature screens (TODO)
│       ├── calibration/
│       ├── home/
│       ├── workout/
│       ├── analysis/
│       └── history/
└── util/                       # ✅ Constants
```

---

## Implementation Roadmap

Follow these steps in order. Each step builds on the previous ones.

---

## STEP 1: Download MediaPipe Model

**Objective:** Get the pose detection model file

**Actions:**
1. Download `pose_landmarker_heavy.task` from:
   - URL: https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_heavy/float16/latest/pose_landmarker_heavy.task
   - OR from MediaPipe Solutions: https://developers.google.com/mediapipe/solutions/vision/pose_landmarker

2. Place the file at: `app/src/main/assets/pose_landmarker_heavy.task`

3. Create the assets directory if needed:
   ```bash
   mkdir -p app/src/main/assets
   ```

**Verification:** File exists at `app/src/main/assets/pose_landmarker_heavy.task`

---

## STEP 2: Create Navigation Graph

**Objective:** Setup app navigation flow: Calibration → Home → Workout → Analysis → History

**Files to Create:**

### 2.1 Screen.kt - Navigation destinations

**Path:** `app/src/main/java/com/lifthelper/ui/navigation/Screen.kt`

```kotlin
package com.lifthelper.ui.navigation

sealed class Screen(val route: String) {
    object Calibration : Screen("calibration")
    object Home : Screen("home")
    object Workout : Screen("workout")
    object Analysis : Screen("analysis/{setId}") {
        fun createRoute(setId: Long) = "analysis/$setId"
    }
    object History : Screen("history")
    object Settings : Screen("settings")
}
```

### 2.2 NavGraph.kt - Navigation graph implementation

**Path:** `app/src/main/java/com/lifthelper/ui/navigation/NavGraph.kt`

```kotlin
package com.lifthelper.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lifthelper.data.local.preferences.SettingsDataStore
import com.lifthelper.ui.screens.calibration.CalibrationScreen
import com.lifthelper.ui.screens.home.HomeScreen

@Composable
fun NavGraph(
    navController: NavHostController = rememberNavController(),
    settingsDataStore: SettingsDataStore = hiltViewModel<HomeViewModel>().settingsDataStore
) {
    val isCalibrated by settingsDataStore.isCalibrated.collectAsState(initial = false)

    val startDestination = if (isCalibrated) {
        Screen.Home.route
    } else {
        Screen.Calibration.route
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Calibration.route) {
            CalibrationScreen(
                onCalibrationComplete = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Calibration.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Home.route) {
            HomeScreen(
                onStartWorkout = { navController.navigate(Screen.Workout.route) },
                onViewHistory = { navController.navigate(Screen.History.route) },
                onSettings = { navController.navigate(Screen.Settings.route) }
            )
        }

        // TODO: Add other screens (Workout, Analysis, History, Settings)
    }
}
```

**Dependencies:** Requires CalibrationScreen and HomeScreen (next steps)

---

## STEP 3: Implement Calibration Screen

**Objective:** User inputs height/shoulder width for pixel-to-meter conversion

**Files to Create:**

### 3.1 CalibrationViewModel.kt

**Path:** `app/src/main/java/com/lifthelper/ui/screens/calibration/CalibrationViewModel.kt`

```kotlin
package com.lifthelper.ui.screens.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifthelper.data.local.preferences.SettingsDataStore
import com.lifthelper.domain.model.CalibrationData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CalibrationViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalibrationState())
    val uiState: StateFlow<CalibrationState> = _uiState.asStateFlow()

    fun onHeightChanged(height: String) {
        _uiState.value = _uiState.value.copy(
            height = height,
            heightError = null
        )
    }

    fun onShoulderWidthChanged(width: String) {
        _uiState.value = _uiState.value.copy(
            shoulderWidth = width,
            shoulderWidthError = null
        )
    }

    fun onContinue(onComplete: () -> Unit) {
        val height = _uiState.value.height.toFloatOrNull()
        val shoulderWidth = _uiState.value.shoulderWidth.toFloatOrNull()

        // Validation
        if (height == null || height < 100 || height > 250) {
            _uiState.value = _uiState.value.copy(
                heightError = "Please enter a valid height (100-250 cm)"
            )
            return
        }

        if (shoulderWidth != null && (shoulderWidth < 20 || shoulderWidth > 80)) {
            _uiState.value = _uiState.value.copy(
                shoulderWidthError = "Please enter a valid shoulder width (20-80 cm)"
            )
            return
        }

        // Save calibration
        val calibrationData = CalibrationData.fromUserInput(
            heightCm = height,
            shoulderWidthCm = shoulderWidth
        )

        viewModelScope.launch {
            settingsDataStore.saveCalibration(calibrationData)
            onComplete()
        }
    }

    fun onSkip(onComplete: () -> Unit) {
        // Use default values
        val calibrationData = CalibrationData.fromUserInput(
            heightCm = 170f, // Default height
            shoulderWidthCm = null // Will use default shoulder width
        )

        viewModelScope.launch {
            settingsDataStore.saveCalibration(calibrationData)
            onComplete()
        }
    }
}

data class CalibrationState(
    val height: String = "",
    val shoulderWidth: String = "",
    val heightError: String? = null,
    val shoulderWidthError: String? = null
)
```

### 3.2 CalibrationScreen.kt

**Path:** `app/src/main/java/com/lifthelper/ui/screens/calibration/CalibrationScreen.kt`

```kotlin
package com.lifthelper.ui.screens.calibration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lifthelper.R

@Composable
fun CalibrationScreen(
    onCalibrationComplete: () -> Unit,
    viewModel: CalibrationViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.calibration_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.calibration_description),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Height input
        OutlinedTextField(
            value = uiState.height,
            onValueChange = { viewModel.onHeightChanged(it) },
            label = { Text(stringResource(R.string.height_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            isError = uiState.heightError != null,
            supportingText = uiState.heightError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Shoulder width input (optional)
        OutlinedTextField(
            value = uiState.shoulderWidth,
            onValueChange = { viewModel.onShoulderWidthChanged(it) },
            label = { Text(stringResource(R.string.shoulder_width_label) + " (Optional)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            isError = uiState.shoulderWidthError != null,
            supportingText = uiState.shoulderWidthError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Continue button
        Button(
            onClick = { viewModel.onContinue(onCalibrationComplete) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.continue_button))
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Skip button
        TextButton(
            onClick = { viewModel.onSkip(onCalibrationComplete) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.skip_button))
        }
    }
}
```

**Important:** Update NavGraph.kt to inject SettingsDataStore differently (see corrected version below)

---

## STEP 4: Implement Home Screen

**Objective:** Landing screen with "Start Workout", "View History", "Settings" buttons

### 4.1 HomeViewModel.kt

**Path:** `app/src/main/java/com/lifthelper/ui/screens/home/HomeViewModel.kt`

```kotlin
package com.lifthelper.ui.screens.home

import androidx.lifecycle.ViewModel
import com.lifthelper.data.local.preferences.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    val settingsDataStore: SettingsDataStore
) : ViewModel()
```

### 4.2 HomeScreen.kt

**Path:** `app/src/main/java/com/lifthelper/ui/screens/home/HomeScreen.kt`

```kotlin
package com.lifthelper.ui.screens.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lifthelper.R

@Composable
fun HomeScreen(
    onStartWorkout: () -> Unit,
    onViewHistory: () -> Unit,
    onSettings: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Start Workout Button
            Button(
                onClick = onStartWorkout,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FitnessCenter,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.start_workout),
                    style = MaterialTheme.typography.headlineSmall
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // View History Button
            OutlinedButton(
                onClick = onViewHistory,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.view_history))
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Settings Button
            OutlinedButton(
                onClick = onSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.settings))
            }
        }
    }
}
```

---

## STEP 5: Implement CameraX Integration

**Objective:** Setup camera preview and frame analysis pipeline

### 5.1 CameraManager.kt

**Path:** `app/src/main/java/com/lifthelper/vision/camera/CameraManager.kt`

```kotlin
package com.lifthelper.vision.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.lifthelper.di.CameraExecutor
import com.lifthelper.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.concurrent.ExecutorService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @CameraExecutor private val cameraExecutor: ExecutorService
) {
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null

    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onFrameAnalyzed: (ImageProxy) -> Unit
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                // Preview use case
                val preview = Preview.Builder()
                    .setTargetResolution(Size(Constants.CAMERA_TARGET_WIDTH, Constants.CAMERA_TARGET_HEIGHT))
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                // ImageAnalysis use case
                imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(Constants.CAMERA_TARGET_WIDTH, Constants.CAMERA_TARGET_HEIGHT))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor) { imageProxy ->
                            onFrameAnalyzed(imageProxy)
                        }
                    }

                // Select back camera
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                // Unbind all and bind new use cases
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )

                Timber.d("Camera started successfully")
            } catch (e: Exception) {
                Timber.e(e, "Camera initialization failed")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stopCamera() {
        camera = null
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        Timber.d("Camera stopped")
    }
}
```

### 5.2 Update AppModule.kt to provide CameraManager

Add to `app/src/main/java/com/lifthelper/di/AppModule.kt`:

```kotlin
@Provides
@Singleton
fun provideCameraManager(
    @ApplicationContext context: Context,
    @CameraExecutor executor: ExecutorService
): CameraManager {
    return CameraManager(context, executor)
}
```

---

## STEP 6: Implement MediaPipe Pose Detection

**Objective:** Wrap MediaPipe Pose Landmarker for pose detection

### 6.1 PoseDetector.kt

**Path:** `app/src/main/java/com/lifthelper/vision/pose/PoseDetector.kt`

```kotlin
package com.lifthelper.vision.pose

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.lifthelper.domain.model.PoseKeypoint
import com.lifthelper.domain.model.PoseResult
import com.lifthelper.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PoseDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var poseLandmarker: PoseLandmarker? = null
    private var latestResult: PoseLandmarkerResult? = null

    init {
        setupPoseLandmarker()
    }

    private fun setupPoseLandmarker() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(Constants.POSE_MODEL_PATH)
                .setDelegate(BaseOptions.Delegate.GPU)
                .build()

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(Constants.POSE_MIN_DETECTION_CONFIDENCE)
                .setMinPosePresenceConfidence(Constants.POSE_MIN_PRESENCE_CONFIDENCE)
                .setMinTrackingConfidence(Constants.POSE_MIN_TRACKING_CONFIDENCE)
                .setResultListener { result, _ ->
                    latestResult = result
                }
                .setErrorListener { error ->
                    Timber.e("PoseDetector error: ${error.message}")
                }
                .build()

            poseLandmarker = PoseLandmarker.createFromOptions(context, options)
            Timber.d("PoseLandmarker initialized successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize PoseLandmarker")
        }
    }

    fun detectPose(imageProxy: ImageProxy, timestamp: Long): PoseResult? {
        try {
            // Convert ImageProxy to MPImage
            val bitmap = imageProxyToBitmap(imageProxy)
            val mpImage = BitmapImageBuilder(bitmap).build()

            // Detect pose (async, result comes via callback)
            poseLandmarker?.detectAsync(mpImage, timestamp)

            // Return latest result converted to domain model
            return latestResult?.let { convertToPoseResult(it, timestamp) }
        } catch (e: Exception) {
            Timber.e(e, "Pose detection failed")
            return null
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val bitmap = Bitmap.createBitmap(
            imageProxy.width,
            imageProxy.height,
            Bitmap.Config.ARGB_8888
        )
        // Copy pixels from ImageProxy to Bitmap
        // Note: This is a simplified version. Full implementation needs proper conversion
        return bitmap
    }

    private fun convertToPoseResult(
        landmarkerResult: PoseLandmarkerResult,
        timestamp: Long
    ): PoseResult? {
        if (landmarkerResult.landmarks().isEmpty()) return null

        val landmarks = landmarkerResult.landmarks()[0].map { landmark ->
            PoseKeypoint(
                x = landmark.x(),
                y = landmark.y(),
                z = landmark.z(),
                visibility = landmark.visibility().orElse(0f)
            )
        }

        return PoseResult(landmarks, timestamp)
    }

    fun close() {
        poseLandmarker?.close()
        poseLandmarker = null
    }
}
```

**Important Note:** The `imageProxyToBitmap` function above is simplified. For production, you need proper YUV to RGB conversion. See MediaPipe examples for full implementation.

---

## STEP 7: Create Pose Overlay Component

**Objective:** Draw skeleton overlay on camera preview

**Path:** `app/src/main/java/com/lifthelper/ui/components/PoseOverlay.kt`

```kotlin
package com.lifthelper.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.lifthelper.domain.model.PoseResult

@Composable
fun PoseOverlay(
    poseResult: PoseResult?,
    imageWidth: Int,
    imageHeight: Int,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        poseResult?.let { pose ->
            // Draw skeleton connections
            drawPoseConnections(pose, imageWidth, imageHeight)

            // Draw keypoints
            pose.landmarks.forEach { landmark ->
                if (landmark.visibility > 0.5f) {
                    drawCircle(
                        color = Color.Green,
                        radius = 8f,
                        center = Offset(
                            x = landmark.x * size.width,
                            y = landmark.y * size.height
                        )
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawPoseConnections(
    pose: PoseResult,
    imageWidth: Int,
    imageHeight: Int
) {
    // Define skeleton connections (pairs of landmark indices)
    val connections = listOf(
        // Arms
        Pair(11, 13), Pair(13, 15), // Left arm
        Pair(12, 14), Pair(14, 16), // Right arm
        // Torso
        Pair(11, 12), // Shoulders
        Pair(11, 23), Pair(12, 24), // Shoulders to hips
        Pair(23, 24), // Hips
        // Legs
        Pair(23, 25), Pair(25, 27), // Left leg
        Pair(24, 26), Pair(26, 28)  // Right leg
    )

    connections.forEach { (start, end) ->
        if (start < pose.landmarks.size && end < pose.landmarks.size) {
            val startLandmark = pose.landmarks[start]
            val endLandmark = pose.landmarks[end]

            if (startLandmark.visibility > 0.5f && endLandmark.visibility > 0.5f) {
                drawLine(
                    color = Color.White,
                    start = Offset(
                        x = startLandmark.x * size.width,
                        y = startLandmark.y * size.height
                    ),
                    end = Offset(
                        x = endLandmark.x * size.width,
                        y = endLandmark.y * size.height
                    ),
                    strokeWidth = 4f
                )
            }
        }
    }
}
```

---

## STEP 8: Implement Velocity Calculator with Second-Order Gradient

**Objective:** Calculate velocity using 5-point stencil method

### 8.1 CircularBuffer.kt (Utility)

**Path:** `app/src/main/java/com/lifthelper/util/CircularBuffer.kt`

```kotlin
package com.lifthelper.util

class CircularBuffer<T>(private val capacity: Int) {
    private val buffer = mutableListOf<T>()

    fun add(item: T) {
        if (buffer.size >= capacity) {
            buffer.removeAt(0)
        }
        buffer.add(item)
    }

    operator fun get(index: Int): T = buffer[index]

    val size: Int get() = buffer.size

    fun clear() = buffer.clear()

    fun toList(): List<T> = buffer.toList()

    fun first(): T = buffer.first()
    fun last(): T = buffer.last()
}
```

### 8.2 PositionData.kt (Data class)

**Path:** `app/src/main/java/com/lifthelper/domain/model/PositionData.kt`

```kotlin
package com.lifthelper.domain.model

data class PositionData(
    val position: Float, // meters
    val timestamp: Long // milliseconds
)
```

### 8.3 SmoothingFilter.kt (Kalman Filter)

**Path:** `app/src/main/java/com/lifthelper/vision/calculator/SmoothingFilter.kt`

```kotlin
package com.lifthelper.vision.calculator

import com.lifthelper.util.Constants
import javax.inject.Inject

class KalmanFilter1D @Inject constructor() {
    private var estimate = 0f
    private var errorCovariance = 1f

    private val processNoise = Constants.KALMAN_PROCESS_NOISE
    private val measurementNoise = Constants.KALMAN_MEASUREMENT_NOISE

    fun update(measurement: Float): Float {
        // Prediction step
        val predictionError = errorCovariance + processNoise

        // Update step
        val kalmanGain = predictionError / (predictionError + measurementNoise)
        estimate += kalmanGain * (measurement - estimate)
        errorCovariance = (1 - kalmanGain) * predictionError

        return estimate
    }

    fun reset() {
        estimate = 0f
        errorCovariance = 1f
    }
}
```

### 8.4 PositionCalculator.kt

**Path:** `app/src/main/java/com/lifthelper/vision/calculator/PositionCalculator.kt`

```kotlin
package com.lifthelper.vision.calculator

import com.lifthelper.domain.model.CalibrationData
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.PoseKeypoint
import com.lifthelper.domain.model.PoseResult
import javax.inject.Inject
import kotlin.math.sqrt

class PositionCalculator @Inject constructor() {

    /**
     * Calculate bar position in pixels based on exercise type
     */
    fun getBarPosition(poseResult: PoseResult, exerciseType: ExerciseType): Pair<Float, Float>? {
        val landmarks = poseResult.landmarks

        return when (exerciseType) {
            ExerciseType.FRONT_SQUAT, ExerciseType.BACK_SQUAT, ExerciseType.DEADLIFT -> {
                // Use hip midpoint
                val leftHip = landmarks.getOrNull(23) ?: return null
                val rightHip = landmarks.getOrNull(24) ?: return null
                if (leftHip.visibility < 0.5f || rightHip.visibility < 0.5f) return null
                getMidpoint(leftHip, rightHip)
            }

            ExerciseType.BENCH_PRESS, ExerciseType.CLEAN, ExerciseType.SNATCH -> {
                // Use wrist midpoint
                val leftWrist = landmarks.getOrNull(15) ?: return null
                val rightWrist = landmarks.getOrNull(16) ?: return null
                if (leftWrist.visibility < 0.5f || rightWrist.visibility < 0.5f) return null
                getMidpoint(leftWrist, rightWrist)
            }

            else -> null
        }
    }

    /**
     * Convert pixel position to meters using calibration data
     */
    fun pixelsToMeters(
        pixelY: Float,
        poseResult: PoseResult,
        calibrationData: CalibrationData
    ): Float {
        // Calculate pixels per meter using shoulder width
        val pixelsPerMeter = calculatePixelsPerMeter(poseResult, calibrationData)

        // Convert vertical position to meters
        return pixelY / pixelsPerMeter
    }

    /**
     * Calculate pixels per meter ratio using detected shoulder width
     */
    private fun calculatePixelsPerMeter(
        poseResult: PoseResult,
        calibrationData: CalibrationData
    ): Float {
        val landmarks = poseResult.landmarks
        val leftShoulder = landmarks.getOrNull(11) ?: return calibrationData.pixelsPerMeter
        val rightShoulder = landmarks.getOrNull(12) ?: return calibrationData.pixelsPerMeter

        if (leftShoulder.visibility < 0.5f || rightShoulder.visibility < 0.5f) {
            return calibrationData.pixelsPerMeter
        }

        // Calculate shoulder width in pixels
        val shoulderWidthPixels = distance(leftShoulder, rightShoulder)

        // Known shoulder width in meters
        val shoulderWidthMeters = calibrationData.shoulderWidth

        return shoulderWidthPixels / shoulderWidthMeters
    }

    private fun getMidpoint(p1: PoseKeypoint, p2: PoseKeypoint): Pair<Float, Float> {
        return Pair((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
    }

    private fun distance(p1: PoseKeypoint, p2: PoseKeypoint): Float {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return sqrt(dx * dx + dy * dy)
    }
}
```

### 8.5 VelocityCalculator.kt (WITH SECOND-ORDER GRADIENT)

**Path:** `app/src/main/java/com/lifthelper/vision/calculator/VelocityCalculator.kt`

```kotlin
package com.lifthelper.vision.calculator

import com.lifthelper.domain.model.CalibrationData
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.PositionData
import com.lifthelper.domain.model.PoseResult
import com.lifthelper.domain.model.VelocityData
import com.lifthelper.util.CircularBuffer
import com.lifthelper.util.Constants
import javax.inject.Inject

class VelocityCalculator @Inject constructor(
    private val positionCalculator: PositionCalculator,
    private val kalmanFilter: KalmanFilter1D
) {
    private val positionHistory = CircularBuffer<PositionData>(Constants.VELOCITY_HISTORY_SIZE)
    private var startTime: Long? = null

    fun calculate(
        poseResult: PoseResult,
        exerciseType: ExerciseType,
        calibrationData: CalibrationData,
        timestamp: Long
    ): VelocityData? {
        if (startTime == null) {
            startTime = timestamp
        }

        // 1. Get bar position in pixels
        val barPositionPixels = positionCalculator.getBarPosition(poseResult, exerciseType)
            ?: return null

        // 2. Convert to meters
        val positionMeters = positionCalculator.pixelsToMeters(
            pixelY = barPositionPixels.second,
            poseResult = poseResult,
            calibrationData = calibrationData
        )

        // 3. Smooth with Kalman filter
        val smoothedPosition = kalmanFilter.update(positionMeters)

        // 4. Store in history
        positionHistory.add(PositionData(smoothedPosition, timestamp))

        // 5. Calculate instantaneous velocity
        val instantVelocity = if (positionHistory.size >= Constants.VELOCITY_HISTORY_SIZE) {
            calculateSecondOrderGradient()
        } else {
            0f
        }

        // 6. Calculate average velocity
        val avgVelocity = calculateAverageVelocity()

        return VelocityData(
            instantaneous = instantVelocity,
            average = avgVelocity,
            position = smoothedPosition,
            timestamp = timestamp
        )
    }

    /**
     * Second-order central difference (5-point stencil)
     * Formula: v[i] = (-f[i+2] + 8*f[i+1] - 8*f[i-1] + f[i-2]) / (12*h)
     */
    private fun calculateSecondOrderGradient(): Float {
        if (positionHistory.size < 5) return 0f

        // Indices: 0=oldest, 4=newest
        val p = positionHistory
        val dx = -p[4].position + 8 * p[3].position - 8 * p[1].position + p[0].position

        // Calculate time step (average dt between points)
        val totalDt = (p[4].timestamp - p[0].timestamp) / 1000f // seconds
        val h = totalDt / 4f // time step between points

        return if (h > 0) dx / (12 * h) else 0f
    }

    /**
     * Average velocity over entire movement
     */
    private fun calculateAverageVelocity(): Float {
        if (positionHistory.size < 2) return 0f

        val totalDisplacement = positionHistory.last().position - positionHistory.first().position
        val totalTime = (positionHistory.last().timestamp - positionHistory.first().timestamp) / 1000f

        return if (totalTime > 0) totalDisplacement / totalTime else 0f
    }

    fun reset() {
        positionHistory.clear()
        kalmanFilter.reset()
        startTime = null
    }
}
```

---

## STEP 9: Implement Movement Phase Detection

**Objective:** Detect eccentric/concentric/idle phases using velocity

**Path:** `app/src/main/java/com/lifthelper/vision/tracking/MovementPhaseDetector.kt`

```kotlin
package com.lifthelper.vision.tracking

import com.lifthelper.util.CircularBuffer
import com.lifthelper.util.Constants
import javax.inject.Inject
import kotlin.math.abs

enum class MovementPhase {
    IDLE,
    ECCENTRIC,    // Lowering
    BOTTOM,       // Transition
    CONCENTRIC,   // Lifting
    TOP           // Lockout
}

class MovementPhaseDetector @Inject constructor() {
    private var currentPhase = MovementPhase.IDLE
    private val velocityHistory = CircularBuffer<Float>(10)

    fun detectPhase(velocity: Float): MovementPhase {
        velocityHistory.add(velocity)

        val avgVelocity = if (velocityHistory.size > 0) {
            velocityHistory.toList().average().toFloat()
        } else {
            0f
        }

        currentPhase = when (currentPhase) {
            MovementPhase.IDLE -> {
                if (abs(avgVelocity) > Constants.MOVEMENT_VELOCITY_THRESHOLD) {
                    if (avgVelocity < 0) MovementPhase.ECCENTRIC
                    else MovementPhase.CONCENTRIC
                } else MovementPhase.IDLE
            }

            MovementPhase.ECCENTRIC -> {
                if (avgVelocity > -Constants.TRANSITION_VELOCITY_THRESHOLD) {
                    MovementPhase.BOTTOM
                } else MovementPhase.ECCENTRIC
            }

            MovementPhase.BOTTOM -> {
                if (avgVelocity > Constants.MOVEMENT_VELOCITY_THRESHOLD) {
                    MovementPhase.CONCENTRIC
                } else MovementPhase.BOTTOM
            }

            MovementPhase.CONCENTRIC -> {
                if (avgVelocity < Constants.TRANSITION_VELOCITY_THRESHOLD) {
                    MovementPhase.TOP
                } else MovementPhase.CONCENTRIC
            }

            MovementPhase.TOP -> {
                if (abs(avgVelocity) < Constants.IDLE_VELOCITY_THRESHOLD) {
                    MovementPhase.IDLE
                } else if (avgVelocity < -Constants.MOVEMENT_VELOCITY_THRESHOLD) {
                    MovementPhase.ECCENTRIC
                } else MovementPhase.TOP
            }
        }

        return currentPhase
    }

    fun reset() {
        currentPhase = MovementPhase.IDLE
        velocityHistory.clear()
    }

    fun getCurrentPhase(): MovementPhase = currentPhase
}
```

---

## STEP 10: Create Real-Time Feedback Overlay

**Objective:** Display velocity, rep count, and target zone during workout

**Path:** `app/src/main/java/com/lifthelper/ui/components/RealTimeFeedbackOverlay.kt`

```kotlin
package com.lifthelper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lifthelper.R
import com.lifthelper.domain.model.VelocityData
import com.lifthelper.domain.model.VelocityZone
import com.lifthelper.ui.theme.*

@Composable
fun RealTimeFeedbackOverlay(
    velocityData: VelocityData?,
    repCount: Int,
    targetVelocityRange: ClosedFloatingPointRange<Float>?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Velocity display with color zone
        velocityData?.let { data ->
            VelocityDisplay(
                velocity = data.instantaneous,
                zone = VelocityZone.fromVelocity(data.instantaneous)
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Rep counter
        Text(
            text = stringResource(R.string.reps_label, repCount),
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // Target velocity range indicator
        targetVelocityRange?.let { range ->
            velocityData?.let { data ->
                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(16.dp)
                ) {
                    val progress = ((data.instantaneous - range.start) /
                            (range.endInclusive - range.start)).coerceIn(0f, 1f)

                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = if (progress in 0.4f..0.6f) VelocityGreen else VelocityOrange
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = stringResource(R.string.target_range, range.start, range.endInclusive),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun VelocityDisplay(
    velocity: Float,
    zone: VelocityZone,
    modifier: Modifier = Modifier
) {
    val zoneColor = when (zone) {
        VelocityZone.VERY_SLOW -> VelocityRed
        VelocityZone.SLOW -> VelocityOrange
        VelocityZone.MODERATE -> VelocityYellow
        VelocityZone.FAST -> VelocityLightGreen
        VelocityZone.VERY_FAST -> VelocityGreen
    }

    Column(
        modifier = modifier
            .background(zoneColor.copy(alpha = 0.9f), RoundedCornerShape(16.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.velocity_label),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White
        )

        Text(
            text = "%.2f m/s".format(velocity),
            style = MaterialTheme.typography.displayLarge,
            color = Color.White
        )

        Text(
            text = zone.name.replace("_", " "),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White
        )
    }
}
```

---

## STEP 11: Build Workout Screen (Integrating Everything)

**Objective:** Main workout screen with camera, pose overlay, and velocity feedback

This is a complex screen that ties everything together. Create these files:

### 11.1 WorkoutState.kt

**Path:** `app/src/main/java/com/lifthelper/ui/screens/workout/WorkoutState.kt`

```kotlin
package com.lifthelper.ui.screens.workout

import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.PoseResult
import com.lifthelper.domain.model.VelocityData

data class WorkoutState(
    val isActive: Boolean = false,
    val selectedExercise: ExerciseType = ExerciseType.FRONT_SQUAT,
    val weight: Float = 0f,
    val targetReps: Int = 5,
    val poseResult: PoseResult? = null,
    val velocityData: VelocityData? = null,
    val repCount: Int = 0,
    val isRecording: Boolean = false,
    val error: String? = null,
    val targetVelocityRange: ClosedFloatingPointRange<Float>? = null
)
```

### 11.2 WorkoutViewModel.kt (CRITICAL - Integrates all CV components)

**Path:** `app/src/main/java/com/lifthelper/ui/screens/workout/WorkoutViewModel.kt`

```kotlin
package com.lifthelper.ui.screens.workout

import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifthelper.data.local.preferences.SettingsDataStore
import com.lifthelper.domain.model.CalibrationData
import com.lifthelper.vision.calculator.VelocityCalculator
import com.lifthelper.vision.pose.PoseDetector
import com.lifthelper.vision.tracking.MovementPhase
import com.lifthelper.vision.tracking.MovementPhaseDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class WorkoutViewModel @Inject constructor(
    private val poseDetector: PoseDetector,
    private val velocityCalculator: VelocityCalculator,
    private val phaseDetector: MovementPhaseDetector,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkoutState())
    val uiState: StateFlow<WorkoutState> = _uiState.asStateFlow()

    private var calibrationData: CalibrationData? = null
    private var lastPhase = MovementPhase.IDLE
    private var repCount = 0

    init {
        // Load calibration data
        viewModelScope.launch {
            settingsDataStore.calibrationData.collect { calibration ->
                calibrationData = calibration
            }
        }
    }

    /**
     * Process camera frame - called by CameraX analyzer
     */
    fun processFrame(imageProxy: ImageProxy) {
        val timestamp = System.currentTimeMillis()

        try {
            // 1. Detect pose
            val poseResult = poseDetector.detectPose(imageProxy, timestamp)

            if (poseResult != null && calibrationData != null) {
                // 2. Calculate velocity
                val velocityData = velocityCalculator.calculate(
                    poseResult = poseResult,
                    exerciseType = _uiState.value.selectedExercise,
                    calibrationData = calibrationData!!,
                    timestamp = timestamp
                )

                // 3. Detect movement phase and count reps
                velocityData?.let { velocity ->
                    val currentPhase = phaseDetector.detectPhase(velocity.instantaneous)

                    // Count rep when transitioning from CONCENTRIC to TOP
                    if (lastPhase == MovementPhase.CONCENTRIC && currentPhase == MovementPhase.TOP) {
                        repCount++
                        Timber.d("Rep completed: $repCount")
                    }

                    lastPhase = currentPhase
                }

                // 4. Update UI state
                _uiState.update { state ->
                    state.copy(
                        poseResult = poseResult,
                        velocityData = velocityData,
                        repCount = repCount
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Frame processing error")
        } finally {
            imageProxy.close()
        }
    }

    fun startRecording() {
        repCount = 0
        velocityCalculator.reset()
        phaseDetector.reset()
        _uiState.update { it.copy(isRecording = true, repCount = 0) }
    }

    fun stopRecording() {
        _uiState.update { it.copy(isRecording = false) }
    }

    fun setWeight(weight: Float) {
        _uiState.update { it.copy(weight = weight) }
    }

    fun setTargetReps(reps: Int) {
        _uiState.update { it.copy(targetReps = reps) }
    }

    override fun onCleared() {
        super.onCleared()
        poseDetector.close()
    }
}
```

### 11.3 WorkoutScreen.kt

**Path:** `app/src/main/java/com/lifthelper/ui/screens/workout/WorkoutScreen.kt`

```kotlin
package com.lifthelper.ui.screens.workout

import android.Manifest
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.lifthelper.R
import com.lifthelper.ui.components.PoseOverlay
import com.lifthelper.ui.components.RealTimeFeedbackOverlay
import com.lifthelper.vision.camera.CameraManager

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun WorkoutScreen(
    onNavigateBack: () -> Unit,
    viewModel: WorkoutViewModel = hiltViewModel(),
    cameraManager: CameraManager = hiltViewModel<WorkoutViewModel>().cameraManager
) {
    val uiState by viewModel.uiState.collectAsState()
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.workout_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (cameraPermissionState.status.isGranted) {
                // Camera preview
                CameraPreview(
                    cameraManager = cameraManager,
                    onFrameAnalyzed = { imageProxy ->
                        if (uiState.isRecording) {
                            viewModel.processFrame(imageProxy)
                        } else {
                            imageProxy.close()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Pose overlay
                PoseOverlay(
                    poseResult = uiState.poseResult,
                    imageWidth = 1280,
                    imageHeight = 720,
                    modifier = Modifier.fillMaxSize()
                )

                // Real-time feedback overlay
                if (uiState.isRecording) {
                    RealTimeFeedbackOverlay(
                        velocityData = uiState.velocityData,
                        repCount = uiState.repCount,
                        targetVelocityRange = uiState.targetVelocityRange,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }

                // Control buttons at bottom
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (!uiState.isRecording) {
                        Button(
                            onClick = { viewModel.startRecording() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.start_recording))
                        }
                    } else {
                        Button(
                            onClick = { viewModel.stopRecording() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(stringResource(R.string.stop_recording))
                        }
                    }
                }
            } else {
                // Camera permission not granted
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.camera_permission_required),
                        style = MaterialTheme.typography.bodyLarge
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                        Text(stringResource(R.string.grant_permission))
                    }
                }
            }
        }
    }
}

@Composable
fun CameraPreview(
    cameraManager: CameraManager,
    onFrameAnalyzed: (androidx.camera.core.ImageProxy) -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                cameraManager.startCamera(
                    lifecycleOwner = lifecycleOwner,
                    previewView = this,
                    onFrameAnalyzed = onFrameAnalyzed
                )
            }
        },
        modifier = modifier
    )

    DisposableEffect(Unit) {
        onDispose {
            cameraManager.stopCamera()
        }
    }
}
```

**Important:** WorkoutViewModel needs CameraManager injected. Update WorkoutViewModel constructor to include it.

---

## STEP 12: Update Navigation Graph with Workout Screen

Update `NavGraph.kt` to add the Workout screen composable:

```kotlin
composable(Screen.Workout.route) {
    WorkoutScreen(
        onNavigateBack = { navController.popBackStack() }
    )
}
```

---

## STEP 13: Create Room Database Schema

**Objective:** Persist workout data with full velocity profiles

### 13.1 Create Converters.kt

**Path:** `app/src/main/java/com/lifthelper/data/local/database/Converters.kt`

```kotlin
package com.lifthelper.data.local.database

import androidx.room.TypeConverter
import com.lifthelper.domain.model.ExerciseType
import com.lifthelper.domain.model.PositionProfile
import com.lifthelper.domain.model.VelocityProfile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

class Converters {
    @TypeConverter
    fun fromExerciseType(value: ExerciseType): String = value.name

    @TypeConverter
    fun toExerciseType(value: String): ExerciseType = ExerciseType.valueOf(value)

    @TypeConverter
    fun fromVelocityProfileList(value: List<VelocityProfile>): String {
        return Json.encodeToString(value)
    }

    @TypeConverter
    fun toVelocityProfileList(value: String): List<VelocityProfile> {
        return Json.decodeFromString(value)
    }

    @TypeConverter
    fun fromPositionProfileList(value: List<PositionProfile>): String {
        return Json.encodeToString(value)
    }

    @TypeConverter
    fun toPositionProfileList(value: String): List<PositionProfile> {
        return Json.decodeFromString(value)
    }
}
```

### 13.2 Make VelocityProfile and PositionProfile serializable

Update `domain/model/VelocityData.kt`:

```kotlin
import kotlinx.serialization.Serializable

@Serializable
data class VelocityProfile(
    val timestamp: Long,
    val velocity: Float
)

@Serializable
data class PositionProfile(
    val timestamp: Long,
    val position: Float
)
```

### 13.3 Create Entity classes

**WorkoutEntity.kt:**

**Path:** `app/src/main/java/com/lifthelper/data/local/database/entities/WorkoutEntity.kt`

```kotlin
package com.lifthelper.data.local.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workouts")
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val date: Long,
    val duration: Long,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
```

**SetEntity.kt:**

**Path:** `app/src/main/java/com/lifthelper/data/local/database/entities/SetEntity.kt`

```kotlin
package com.lifthelper.data.local.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lifthelper.domain.model.ExerciseType

@Entity(
    tableName = "sets",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutEntity::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("workoutId")]
)
data class SetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val workoutId: Long,
    val exerciseType: ExerciseType,
    val weight: Float,
    val targetReps: Int,
    val completedReps: Int,
    val avgVelocity: Float,
    val peakVelocity: Float,
    val avgPower: Float? = null,
    val jumpHeight: Float? = null,
    val timestamp: Long,
    val notes: String = ""
)
```

**RepEntity.kt:**

**Path:** `app/src/main/java/com/lifthelper/data/local/database/entities/RepEntity.kt`

```kotlin
package com.lifthelper.data.local.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lifthelper.domain.model.PositionProfile
import com.lifthelper.domain.model.VelocityProfile

@Entity(
    tableName = "reps",
    foreignKeys = [
        ForeignKey(
            entity = SetEntity::class,
            parentColumns = ["id"],
            childColumns = ["setId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("setId")]
)
data class RepEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val setId: Long,
    val repNumber: Int,
    val peakVelocity: Float,
    val avgVelocity: Float,
    val timeUnderTension: Float,
    val rangeOfMotion: Float,
    val startPosition: Float,
    val endPosition: Float,
    val eccentricDuration: Float,
    val concentricDuration: Float,
    val velocityProfile: List<VelocityProfile>, // Full frame-by-frame data
    val positionProfile: List<PositionProfile>, // Full position tracking
    val timestamp: Long
)
```

### 13.4 Create DAOs

**WorkoutDao.kt:**

**Path:** `app/src/main/java/com/lifthelper/data/local/database/dao/WorkoutDao.kt`

```kotlin
package com.lifthelper.data.local.database.dao

import androidx.room.*
import com.lifthelper.data.local.database.entities.WorkoutEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutDao {
    @Insert
    suspend fun insertWorkout(workout: WorkoutEntity): Long

    @Query("SELECT * FROM workouts ORDER BY date DESC")
    fun getAllWorkouts(): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workouts WHERE id = :workoutId")
    suspend fun getWorkoutById(workoutId: Long): WorkoutEntity?

    @Delete
    suspend fun deleteWorkout(workout: WorkoutEntity)
}
```

**SetDao.kt:**

**Path:** `app/src/main/java/com/lifthelper/data/local/database/dao/SetDao.kt`

```kotlin
package com.lifthelper.data.local.database.dao

import androidx.room.*
import com.lifthelper.data.local.database.entities.SetEntity
import com.lifthelper.domain.model.ExerciseType
import kotlinx.coroutines.flow.Flow

@Dao
interface SetDao {
    @Insert
    suspend fun insertSet(set: SetEntity): Long

    @Query("SELECT * FROM sets WHERE workoutId = :workoutId")
    fun getSetsForWorkout(workoutId: Long): Flow<List<SetEntity>>

    @Query("SELECT * FROM sets WHERE exerciseType = :exerciseType ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentSetsForExercise(exerciseType: ExerciseType, limit: Int): Flow<List<SetEntity>>

    @Update
    suspend fun updateSet(set: SetEntity)
}
```

**RepDao.kt:**

**Path:** `app/src/main/java/com/lifthelper/data/local/database/dao/RepDao.kt`

```kotlin
package com.lifthelper.data.local.database.dao

import androidx.room.*
import com.lifthelper.data.local.database.entities.RepEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RepDao {
    @Insert
    suspend fun insertRep(rep: RepEntity): Long

    @Insert
    suspend fun insertReps(reps: List<RepEntity>)

    @Query("SELECT * FROM reps WHERE setId = :setId ORDER BY repNumber ASC")
    fun getRepsForSet(setId: Long): Flow<List<RepEntity>>

    @Query("SELECT AVG(avgVelocity) FROM reps WHERE setId = :setId")
    suspend fun getAverageVelocityForSet(setId: Long): Float?

    @Query("SELECT MAX(peakVelocity) FROM reps WHERE setId = :setId")
    suspend fun getPeakVelocityForSet(setId: Long): Float?
}
```

### 13.5 Create Database class

**Path:** `app/src/main/java/com/lifthelper/data/local/database/LiftDatabase.kt`

```kotlin
package com.lifthelper.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.lifthelper.data.local.database.dao.RepDao
import com.lifthelper.data.local.database.dao.SetDao
import com.lifthelper.data.local.database.dao.WorkoutDao
import com.lifthelper.data.local.database.entities.RepEntity
import com.lifthelper.data.local.database.entities.SetEntity
import com.lifthelper.data.local.database.entities.WorkoutEntity

@Database(
    entities = [
        WorkoutEntity::class,
        SetEntity::class,
        RepEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class LiftDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao
    abstract fun setDao(): SetDao
    abstract fun repDao(): RepDao
}
```

### 13.6 Provide Database in DI

Create new module:

**Path:** `app/src/main/java/com/lifthelper/di/DatabaseModule.kt`

```kotlin
package com.lifthelper.di

import android.content.Context
import androidx.room.Room
import com.lifthelper.data.local.database.LiftDatabase
import com.lifthelper.data.local.database.dao.RepDao
import com.lifthelper.data.local.database.dao.SetDao
import com.lifthelper.data.local.database.dao.WorkoutDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LiftDatabase {
        return Room.databaseBuilder(
            context,
            LiftDatabase::class.java,
            "lift_helper_db"
        ).build()
    }

    @Provides
    fun provideWorkoutDao(database: LiftDatabase): WorkoutDao {
        return database.workoutDao()
    }

    @Provides
    fun provideSetDao(database: LiftDatabase): SetDao {
        return database.setDao()
    }

    @Provides
    fun provideRepDao(database: LiftDatabase): RepDao {
        return database.repDao()
    }
}
```

---

## STEP 14: Testing & Validation

### 14.1 Build the project

```bash
./gradlew assembleDebug
```

### 14.2 Test on device

1. Connect Android device (Android 14+ recommended)
2. Run: `./gradlew installDebug`
3. Grant camera permissions
4. Test calibration flow
5. Test workout screen with camera preview
6. Verify pose detection overlay appears
7. Start recording and perform a front squat
8. Verify velocity display updates in real-time
9. Verify rep counting works

### 14.3 Verify database

After completing a set, use Android Studio's Database Inspector to verify:
- Data is saved in `sets` and `reps` tables
- `velocityProfile` and `positionProfile` JSON arrays are populated

---

## TROUBLESHOOTING GUIDE

### Issue: MediaPipe model not found

**Solution:** Ensure `pose_landmarker_heavy.task` is in `app/src/main/assets/`

### Issue: Camera not starting

**Solution:**
1. Check camera permissions granted
2. Verify manifest has `<uses-permission android:name="android.permission.CAMERA" />`
3. Check Logcat for CameraManager errors

### Issue: Pose detection not working

**Solution:**
1. Verify model file exists
2. Check GPU delegate support on device
3. Try CPU delegate: `setDelegate(BaseOptions.Delegate.CPU)`

### Issue: Velocity always zero

**Solution:**
1. Check calibrationData is loaded in WorkoutViewModel
2. Verify `SettingsDataStore` has saved calibration
3. Add debug logs in `VelocityCalculator.calculate()`

### Issue: Build errors with kotlinx.serialization

**Solution:** Ensure plugin is added in `build.gradle.kts`:
```kotlin
plugins {
    kotlin("plugin.serialization") version "1.9.22"
}
```

---

## NEXT STEPS (Future Features)

After completing the above:

1. **History Screen** - Display past workouts from database
2. **Analysis Screen** - Show velocity graphs using MPAndroidChart
3. **Jump Tracking** - Implement JumpHeightCalculator and JumpTracker
4. **Audio Feedback** - Add audio cues for velocity zones
5. **Multiple Exercises** - Add bench press, deadlift, Olympic lifts support
6. **Settings Screen** - Configure velocity zones, audio, calibration

---

## FILE CHECKLIST

Use this to track implementation progress:

### Core Infrastructure
- [x] build.gradle.kts (root)
- [x] app/build.gradle.kts
- [x] AndroidManifest.xml
- [x] LiftHelperApplication.kt
- [x] MainActivity.kt
- [x] di/AppModule.kt
- [x] util/Constants.kt

### Domain Models
- [x] domain/model/ExerciseType.kt
- [x] domain/model/CalibrationData.kt
- [x] domain/model/VelocityData.kt
- [x] domain/model/JumpData.kt
- [x] domain/model/PoseKeypoint.kt
- [ ] domain/model/PositionData.kt (STEP 8.2)

### Data Layer
- [x] data/local/preferences/SettingsDataStore.kt
- [ ] data/local/database/LiftDatabase.kt (STEP 13)
- [ ] data/local/database/Converters.kt (STEP 13)
- [ ] data/local/database/entities/*.kt (STEP 13)
- [ ] data/local/database/dao/*.kt (STEP 13)

### UI Theme
- [x] ui/theme/Theme.kt
- [x] ui/theme/Color.kt
- [x] ui/theme/Type.kt

### Navigation
- [ ] ui/navigation/Screen.kt (STEP 2.1)
- [ ] ui/navigation/NavGraph.kt (STEP 2.2)

### Screens
- [ ] ui/screens/calibration/CalibrationScreen.kt (STEP 3)
- [ ] ui/screens/calibration/CalibrationViewModel.kt (STEP 3)
- [ ] ui/screens/home/HomeScreen.kt (STEP 4)
- [ ] ui/screens/home/HomeViewModel.kt (STEP 4)
- [ ] ui/screens/workout/WorkoutScreen.kt (STEP 11)
- [ ] ui/screens/workout/WorkoutViewModel.kt (STEP 11)
- [ ] ui/screens/workout/WorkoutState.kt (STEP 11)

### Computer Vision
- [ ] vision/camera/CameraManager.kt (STEP 5)
- [ ] vision/pose/PoseDetector.kt (STEP 6)
- [ ] vision/calculator/PositionCalculator.kt (STEP 8.4)
- [ ] vision/calculator/VelocityCalculator.kt (STEP 8.5)
- [ ] vision/calculator/SmoothingFilter.kt (STEP 8.3)
- [ ] vision/tracking/MovementPhaseDetector.kt (STEP 9)

### UI Components
- [ ] ui/components/PoseOverlay.kt (STEP 7)
- [ ] ui/components/RealTimeFeedbackOverlay.kt (STEP 10)

### Utilities
- [ ] util/CircularBuffer.kt (STEP 8.1)

### Assets
- [ ] app/src/main/assets/pose_landmarker_heavy.task (STEP 1)

---

## SUMMARY

This implementation guide provides step-by-step instructions to build a complete Android weightlifting app with markerless computer vision. The key technical achievements are:

1. **Real-time pose detection** at 25-30 FPS using MediaPipe Heavy model
2. **Second-order gradient velocity calculation** for smooth, accurate measurements
3. **Full velocity profiles** stored frame-by-frame in Room database
4. **Automatic rep counting** via movement phase detection
5. **Real-time feedback** with color-coded velocity zones

Follow the steps sequentially. Each step builds on previous work. Test frequently. Good luck!
