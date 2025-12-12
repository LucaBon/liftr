package com.lifthelper.ui.screens.workout

import android.Manifest
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Cameraswitch
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
import com.lifthelper.ui.components.CalibrationWarningOverlay
import com.lifthelper.ui.components.PoseOverlay
import com.lifthelper.ui.components.RealTimeFeedbackOverlay
import com.lifthelper.util.Constants.TAG_WORKOUT
import com.lifthelper.util.getRotationDegrees
import com.lifthelper.util.isPortrait
import com.lifthelper.util.rememberOrientation
import com.lifthelper.vision.camera.CameraManager
import javax.inject.Inject

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WorkoutScreen(
    onNavigateBack: () -> Unit,
    viewModel: WorkoutViewModel = hiltViewModel(),
    cameraManager: CameraManager = hiltViewModel<WorkoutCameraViewModel>().cameraManager
) {
    val uiState by viewModel.uiState.collectAsState()
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    val orientation = rememberOrientation()

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
                        // Always process frames to show pose overlay
                        viewModel.processFrame(imageProxy)
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Pose overlay
                PoseOverlay(
                    poseResult = uiState.poseResult,
                    imageWidth = uiState.poseResult?.imageWidth ?: 640,
                    imageHeight = uiState.poseResult?.imageHeight ?: 480,
                    modifier = Modifier.fillMaxSize(),
                    rotationDegrees = orientation.getRotationDegrees(),
                    isFrontCamera = cameraManager.isFrontCamera()
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

                // Calibration warning overlay (shown below feedback)
                CalibrationWarningOverlay(
                    calibrationQuality = uiState.calibrationQuality,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = if (uiState.isRecording) 120.dp else 16.dp)
                )

                // Camera switch button (top right) - only show when not recording
                if (!uiState.isRecording) {
                    FloatingActionButton(
                        onClick = { cameraManager.switchCamera() },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp),
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = "Switch Camera"
                        )
                    }
                }

                // Control buttons at bottom
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Exercise selector (only visible when not recording)
                    if (!uiState.isRecording) {
                        ExerciseSelector(
                            selectedExercise = uiState.selectedExercise,
                            onExerciseSelected = { viewModel.setExercise(it) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Start/Stop recording button
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

    // Create PreviewView only once
    val previewView = remember {
        mutableStateOf<PreviewView?>(null)
    }

    // Only log on first composition and key changes
    LaunchedEffect(Unit) {
        Log.d(TAG_WORKOUT, "CameraPreview composable created")
    }

    AndroidView(
        factory = { ctx ->
            Log.d(TAG_WORKOUT, "Creating PreviewView")
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FIT_CENTER
                previewView.value = this
            }
        },
        modifier = modifier,
        update = { /* No updates needed */ }
    )

    // Start camera after view is created
    LaunchedEffect(previewView.value, cameraManager, lifecycleOwner) {
        previewView.value?.let { view ->
            Log.i(TAG_WORKOUT, "LaunchedEffect: PreviewView ready, starting camera")
            cameraManager.startCamera(
                lifecycleOwner = lifecycleOwner,
                previewView = view,
                onFrameAnalyzed = onFrameAnalyzed
            )
        } ?: Log.w(TAG_WORKOUT, "LaunchedEffect: PreviewView is null")
    }

    DisposableEffect(Unit) {
        Log.d(TAG_WORKOUT, "DisposableEffect: CameraPreview created")
        onDispose {
            Log.i(TAG_WORKOUT, "DisposableEffect: CameraPreview disposing, stopping camera")
            cameraManager.stopCamera()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseSelector(
    selectedExercise: com.lifthelper.domain.model.ExerciseType,
    onExerciseSelected: (com.lifthelper.domain.model.ExerciseType) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedExercise.getDisplayName(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Exercise") },
            trailingIcon = {
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Select exercise")
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
            )
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            com.lifthelper.domain.model.ExerciseType.entries.forEach { exercise ->
                DropdownMenuItem(
                    text = { Text(exercise.getDisplayName()) },
                    onClick = {
                        onExerciseSelected(exercise)
                        expanded = false
                    }
                )
            }
        }
    }
}

// Helper ViewModel to inject CameraManager
@dagger.hilt.android.lifecycle.HiltViewModel
class WorkoutCameraViewModel @Inject constructor(
    val cameraManager: CameraManager
) : androidx.lifecycle.ViewModel()
