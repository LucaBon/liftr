package com.lifthelper.ui.screens.calibration

import android.Manifest
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.lifthelper.domain.model.CalibrationQuality
import com.lifthelper.ui.components.PoseOverlay
import com.lifthelper.vision.camera.CameraManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Screen for capturing anthropometric measurements using camera
 *
 * Guides user to stand in frontal pose and automatically measures body proportions
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun AnthropometricCalibrationScreen(
    onComplete: () -> Unit,
    onSkip: () -> Unit,
    viewModel: AnthropometricCalibrationViewModel = hiltViewModel(),
    cameraManager: CameraManager = hiltViewModel<AnthropometricCameraViewModel>().cameraManager
) {
    val uiState by viewModel.uiState.collectAsState()
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    Scaffold {  paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (cameraPermissionState.status.isGranted) {
                // Camera preview with pose overlay
                CameraPreviewWithGuide(
                    cameraManager = cameraManager,
                    uiState = uiState,
                    onFrameAnalyzed = { imageProxy ->
                        viewModel.processFrame(imageProxy)
                    }
                )

                // Instruction overlay
                CalibrationInstructionOverlay(
                    calibrationQuality = uiState.calibrationQuality,
                    isCapturing = uiState.isCapturing,
                    countdown = uiState.captureCountdown,
                    errorMessage = uiState.errorMessage,
                    modifier = Modifier.align(Alignment.TopCenter)
                )

                // Body outline guide
                if (!uiState.isCapturing) {
                    BodyOutlineGuide(
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Camera switch button (top right)
                if (!uiState.isCapturing) {
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

                // Action buttons
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Capture button - always show when not capturing (for solo front camera mode)
                    if (!uiState.isCapturing) {
                        Button(
                            onClick = { viewModel.startCapture() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isCapturing,
                            colors = if (uiState.calibrationQuality == CalibrationQuality.OPTIMAL) {
                                ButtonDefaults.buttonColors()
                            } else {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        ) {
                            Text(
                                if (uiState.calibrationQuality == CalibrationQuality.OPTIMAL) {
                                    "Capture Body Measurements"
                                } else {
                                    "Capture (move to fit in frame)"
                                }
                            )
                        }
                    }

                    // Skip button
                    if (!uiState.isCapturing) {
                        TextButton(
                            onClick = onSkip,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Skip for now")
                        }
                    }
                }

                // Success dialog
                if (uiState.captureComplete) {
                    CaptureSuccessDialog(
                        anthropometricData = uiState.anthropometricData,
                        onContinue = {
                            viewModel.saveMeasurements()
                            onComplete()
                        },
                        onRetry = {
                            viewModel.resetCapture()
                        }
                    )
                }
            } else {
                // Permission request
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Camera access needed to measure body proportions",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                        Text("Grant Permission")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(onClick = onSkip) {
                        Text("Skip calibration")
                    }
                }
            }
        }
    }
}

@Composable
fun CalibrationInstructionOverlay(
    calibrationQuality: CalibrationQuality,
    isCapturing: Boolean,
    countdown: Int?,
    errorMessage: String?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = MaterialTheme.shapes.medium,
        color = when (calibrationQuality) {
            CalibrationQuality.OPTIMAL -> Color(0xFF4CAF50).copy(alpha = 0.9f) // Green
            CalibrationQuality.ESTIMATED -> Color(0xFFFF9800).copy(alpha = 0.9f) // Orange
            else -> Color(0xFFD32F2F).copy(alpha = 0.9f) // Red
        },
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status icon
            Icon(
                imageVector = if (calibrationQuality == CalibrationQuality.OPTIMAL)
                    Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Instructions based on state
            Text(
                text = when {
                    errorMessage != null -> errorMessage
                    countdown != null -> "Capturing in $countdown..."
                    isCapturing -> "Hold still..."
                    calibrationQuality == CalibrationQuality.OPTIMAL ->
                        "Perfect! Stand still and press capture"
                    else -> "Step back until full body is visible\nStand facing the camera"
                },
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            if (!isCapturing && calibrationQuality != CalibrationQuality.OPTIMAL) {
                Text(
                    text = "Make sure your head and feet are visible",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun BodyOutlineGuide(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val centerX = size.width / 2
        val guideColor = Color.White.copy(alpha = 0.3f)
        val strokeWidth = 2f

        // Much smaller scale to fit comfortably in camera view
        val scale = 0.45f

        // Draw simple body outline guide - very compact to ensure it fits
        // Head circle (higher up, smaller)
        drawCircle(
            color = guideColor,
            radius = size.width * 0.06f * scale,
            center = Offset(centerX, size.height * 0.12f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
        )

        // Shoulders line
        drawLine(
            color = guideColor,
            start = Offset(centerX - size.width * 0.12f * scale, size.height * 0.18f),
            end = Offset(centerX + size.width * 0.12f * scale, size.height * 0.18f),
            strokeWidth = strokeWidth
        )

        // Torso
        drawLine(
            color = guideColor,
            start = Offset(centerX, size.height * 0.18f),
            end = Offset(centerX, size.height * 0.40f),
            strokeWidth = strokeWidth
        )

        // Hips line
        drawLine(
            color = guideColor,
            start = Offset(centerX - size.width * 0.10f * scale, size.height * 0.40f),
            end = Offset(centerX + size.width * 0.10f * scale, size.height * 0.40f),
            strokeWidth = strokeWidth
        )

        // Left leg
        drawLine(
            color = guideColor,
            start = Offset(centerX - size.width * 0.05f * scale, size.height * 0.40f),
            end = Offset(centerX - size.width * 0.05f * scale, size.height * 0.75f),
            strokeWidth = strokeWidth
        )

        // Right leg
        drawLine(
            color = guideColor,
            start = Offset(centerX + size.width * 0.05f * scale, size.height * 0.40f),
            end = Offset(centerX + size.width * 0.05f * scale, size.height * 0.75f),
            strokeWidth = strokeWidth
        )
    }
}

@Composable
fun CaptureSuccessDialog(
    anthropometricData: com.lifthelper.domain.model.AnthropometricData?,
    onContinue: () -> Unit,
    onRetry: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { },
        icon = {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text("Measurements Captured!")
        },
        text = {
            Column {
                Text("Body measurements successfully recorded:")

                Spacer(modifier = Modifier.height(12.dp))

                anthropometricData?.let { data ->
                    MeasurementRow("Height", "${(data.userHeight * 100).toInt()} cm")
                    MeasurementRow("Shoulder Width", "${(data.getShoulderWidth() * 100).toInt()} cm")
                    MeasurementRow("Torso Height", "${(data.getTorsoHeight() * 100).toInt()} cm")
                    MeasurementRow("Leg Length", "${((data.getUpperLegHeight() + data.getLowerLegHeight()) * 100).toInt()} cm")

                    if (data.captureQuality == CalibrationQuality.OPTIMAL) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "✓ High quality capture",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF4CAF50)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onContinue) {
                Text("Continue")
            }
        },
        dismissButton = {
            TextButton(onClick = onRetry) {
                Text("Retake")
            }
        }
    )
}

@Composable
fun MeasurementRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun CameraPreviewWithGuide(
    cameraManager: CameraManager,
    uiState: AnthropometricCalibrationState,
    onFrameAnalyzed: (androidx.camera.core.ImageProxy) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // Create PreviewView only once
    val previewView = remember {
        mutableStateOf<PreviewView?>(null)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera preview
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    previewView.value = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Start camera after view is created
        LaunchedEffect(previewView.value, cameraManager, lifecycleOwner) {
            previewView.value?.let { view ->
                cameraManager.startCamera(
                    lifecycleOwner = lifecycleOwner,
                    previewView = view,
                    onFrameAnalyzed = onFrameAnalyzed
                )
            }
        }

        // Skeleton overlay removed - only show body outline guide instead

        DisposableEffect(Unit) {
            onDispose {
                cameraManager.stopCamera()
            }
        }
    }
}

// Helper ViewModel to inject CameraManager
@HiltViewModel
class AnthropometricCameraViewModel @Inject constructor(
    val cameraManager: CameraManager
) : ViewModel()
