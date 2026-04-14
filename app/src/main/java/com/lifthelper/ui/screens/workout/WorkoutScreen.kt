package com.lifthelper.ui.screens.workout

import android.Manifest
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.lifthelper.R
import com.lifthelper.ui.components.common.StatItem
import com.lifthelper.domain.exercise.Severity
import com.lifthelper.domain.model.RepQualityScore
import com.lifthelper.domain.model.ScoreGrade
import com.lifthelper.domain.model.SetSummary
import com.lifthelper.ui.components.BarPathOverlay
import com.lifthelper.ui.components.BarPathReplayCard
import com.lifthelper.ui.components.CalibrationWarningOverlay
import com.lifthelper.ui.components.ManualEntryForm
import com.lifthelper.ui.components.PrCelebrationOverlay
import com.lifthelper.ui.components.PoseOverlay
import com.lifthelper.ui.components.RealTimeFeedbackOverlay
import com.lifthelper.ui.components.RestTimerOverlay
import com.lifthelper.ui.components.VelocityLossStrip
import com.lifthelper.ui.components.TempoInputWidget
import com.lifthelper.ui.components.TempoProgressBar
import com.lifthelper.ui.theme.VbtStopAlert
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
    viewModel: WorkoutViewModel = hiltViewModel()
) {
    // BUG-D fix: create camera ViewModel inside composable body, not in parameter default
    val cameraViewModel: WorkoutCameraViewModel = hiltViewModel()
    val cameraManager = cameraViewModel.cameraManager
    val uiState by viewModel.uiState.collectAsState()
    val barPathPoints by viewModel.currentBarPathPoints.collectAsState()
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    val orientation = rememberOrientation()
    val view = androidx.compose.ui.platform.LocalView.current
    // BUG-F fix: the Activity uses configChanges="orientation|screenSize" so
    // CameraX is never re-bound with the new display rotation. Push the new
    // rotation into CameraX whenever the orientation flips — otherwise frames
    // (and therefore pose landmarks) stay locked to the original orientation
    // and the skeleton appears rotated 90° in landscape.
    LaunchedEffect(orientation) {
        cameraManager.updateTargetRotation(view.display?.rotation ?: android.view.Surface.ROTATION_0)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    var showSaveDialog by remember { mutableStateOf(false) }

    // Save confirmation dialog
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(stringResource(R.string.save_workout_title)) },
            text = { Text(stringResource(R.string.save_workout_message, uiState.completedSetCount)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveWorkout()
                    showSaveDialog = false
                    onNavigateBack()
                }) {
                    Text(stringResource(R.string.save_workout_save))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSaveDialog = false
                    onNavigateBack()
                }) {
                    Text(stringResource(R.string.save_workout_discard))
                }
            }
        )
    }

    // Handle errors via Snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            when (error) {
                is com.lifthelper.ui.common.UiError.Message -> {
                    snackbarHostState.showSnackbar(
                        message = error.text,
                        duration = SnackbarDuration.Short
                    )
                    viewModel.clearError()
                }
                is com.lifthelper.ui.common.UiError.Recoverable -> {
                    val result = snackbarHostState.showSnackbar(
                        message = error.text,
                        actionLabel = error.action.label,
                        duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        error.action.onClick()
                    }
                    viewModel.clearError()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.workout_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (viewModel.hasUnsavedSets()) {
                            showSaveDialog = true
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.content_desc_back)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
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

                // Bar path overlay (between pose skeleton and feedback HUD)
                if (uiState.isRecording && barPathPoints.isNotEmpty()) {
                    BarPathOverlay(
                        barPathPoints = barPathPoints,
                        imageWidth = uiState.poseResult?.imageWidth ?: 640,
                        imageHeight = uiState.poseResult?.imageHeight ?: 480,
                        modifier = Modifier.fillMaxSize(),
                        rotationDegrees = orientation.getRotationDegrees(),
                        isFrontCamera = cameraManager.isFrontCamera()
                    )
                }

                // Primary HUD strip (rep count + velocity). Portrait: top-center.
                // Landscape: top-start so it doesn't cover the preview center.
                if (uiState.isRecording) {
                    RealTimeFeedbackOverlay(
                        velocityData = uiState.velocityData,
                        repCount = uiState.repCount,
                        targetReps = uiState.targetReps,
                        targetVelocityRange = uiState.targetVelocityRange,
                        modifier = Modifier.align(
                            if (orientation.isPortrait()) Alignment.TopCenter else Alignment.TopStart
                        ),
                        vbtZone = uiState.vbtZone,
                        vbtZonesEnabled = uiState.vbtZonesEnabled,
                        lastRepDepthOk = uiState.lastRepDepthOk
                    )

                    // Tempo progress bar overlay
                    val currentTempoState = uiState.tempoState
                    val currentTempoCue = uiState.tempoCue
                    if (uiState.tempoModeEnabled && currentTempoState != null && currentTempoCue != null) {
                        TempoProgressBar(
                            tempoState = currentTempoState,
                            tempoCue = currentTempoCue,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 100.dp)
                        )
                    }
                }

                // Calibration warning — compact pill at top-end in both orientations.
                // Tapping it opens a dialog with the full explanation. When not
                // recording, the camera-switch FAB sits at top-end with a 48dp
                // size + 16dp padding, so we push the pill below it.
                CalibrationWarningOverlay(
                    calibrationQuality = uiState.calibrationQuality,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(
                            top = if (uiState.isRecording) 16.dp else 80.dp,
                            end = 16.dp
                        )
                )

                // Camera switch button (top right) - only show when not recording
                if (!uiState.isRecording) {
                    FloatingActionButton(
                        onClick = { cameraManager.switchCamera() },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp),
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = stringResource(R.string.content_desc_switch_camera)
                        )
                    }
                }

                // Control panel at bottom. Landscape caps width + height and
                // becomes scrollable when the setup panel exceeds the cap,
                // while still wrapping content so the recording stop button
                // sits at the true bottom of the screen (via align BottomCenter).
                val isLandscape = !orientation.isPortrait()
                val screenHeightDp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .then(
                            if (isLandscape) {
                                Modifier
                                    .widthIn(max = 520.dp)
                                    .heightIn(max = screenHeightDp * 0.85f)
                            } else {
                                Modifier.fillMaxWidth()
                            }
                        )
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                        .zIndex(10f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!uiState.isRecording) {
                        // Mode toggle: Camera / Manual
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = !uiState.isManualEntryMode,
                                onClick = { if (uiState.isManualEntryMode) viewModel.toggleManualEntryMode() },
                                label = { Text(stringResource(R.string.mode_camera)) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = uiState.isManualEntryMode,
                                onClick = { if (!uiState.isManualEntryMode) viewModel.toggleManualEntryMode() },
                                label = { Text(stringResource(R.string.mode_manual)) },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (uiState.isManualEntryMode) {
                            // Manual entry form
                            ManualEntryForm(
                                exerciseNames = uiState.customExerciseNames,
                                onLogSet = { name, weight, reps, notes ->
                                    viewModel.logManualSet(name, weight, reps, notes)
                                }
                            )
                        } else {
                            // Camera setup panel — exercise, weight, reps
                            WorkoutSetupPanel(
                                selectedExercise = uiState.selectedExercise,
                                weight = uiState.weight.value,
                                targetReps = uiState.targetReps.value,
                                onExerciseSelected = { viewModel.setExercise(it) },
                                onWeightChanged = { viewModel.setWeight(it) },
                                onTargetRepsChanged = { viewModel.setTargetReps(it) }
                            )

                            // Tempo mode toggle and input
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.tempo_mode_enabled),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = uiState.tempoModeEnabled,
                                    onCheckedChange = { viewModel.toggleTempoMode(it) }
                                )
                            }

                            if (uiState.tempoModeEnabled) {
                                TempoInputWidget(
                                    prescription = uiState.tempoPrescription,
                                    onPrescriptionChanged = { viewModel.setTempoPrescription(it) },
                                    modifier = Modifier
                                        .background(
                                            MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                            RoundedCornerShape(12.dp)
                                        )
                                        .padding(12.dp)
                                )
                            }

                            Button(
                                onClick = { viewModel.startRecording() },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = uiState.weight.value > 0f
                            ) {
                                Text(stringResource(R.string.start_recording))
                            }
                        }
                    } else {
                        // Velocity-loss strip (peripheral, above controls).
                        // Only visible from rep 2 onwards when VBT tracking is on.
                        VelocityLossStrip(
                            velocityLossState = uiState.velocityLossState,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Velocity-loss stop alert: non-modal toast above the
                        // Stop button. Slides in when the threshold is crossed
                        // and can be dismissed so it stops covering the preview.
                        AnimatedVisibility(
                            visible = uiState.velocityLossState.shouldStop &&
                                !uiState.velocityLossAlertDismissed,
                            enter = slideInVertically { it } + fadeIn(),
                            exit = slideOutVertically { it } + fadeOut()
                        ) {
                            VelocityLossStopAlert(
                                thresholdPercent = (uiState.velocityLossState.threshold * 100).toInt(),
                                onDismiss = { viewModel.dismissVelocityLossAlert() },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Compact info chip during recording
                        WorkoutInfoChip(
                            exerciseName = uiState.selectedExercise.getDisplayName(),
                            weight = uiState.weight.value,
                            targetReps = uiState.targetReps.value
                        )

                        Button(
                            onClick = { viewModel.stopRecording() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp),
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

    // Post-set summary sheet
    uiState.lastSetSummary?.let { summary ->
        SetSummarySheet(
            summary = summary,
            onDismiss = { viewModel.dismissSummary() }
        )
    }

    // PR celebration overlay
    if (uiState.newPersonalRecords.isNotEmpty()) {
        PrCelebrationOverlay(
            records = uiState.newPersonalRecords,
            onDismiss = { viewModel.dismissPRCelebration() }
        )
    }

    // Rest timer overlay
    uiState.restTimerState?.let { timerState ->
        if (!timerState.isComplete) {
            RestTimerOverlay(
                timerState = timerState,
                onSkip = { viewModel.skipRest() },
                onExtend = { viewModel.extendRest() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetSummarySheet(
    summary: SetSummary,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Text(
                text = stringResource(R.string.set_summary_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "${summary.exerciseType.getDisplayName()} \u00B7 ${summary.weight.value.toInt()} kg \u00B7 " +
                    stringResource(R.string.set_summary_reps_format, summary.completedReps.value, summary.targetReps.value),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Overall score bar
            ScoreBar(score = summary.overallScore, grade = summary.overallGrade)

            Spacer(modifier = Modifier.height(16.dp))

            // Per-rep score table
            if (summary.repScores.isNotEmpty()) {
                RepScoreTable(
                    scores = summary.repScores,
                    velocities = summary.repVelocities,
                    bestIndex = summary.bestRepIndex,
                    worstIndex = summary.worstRepIndex
                )
            }

            // Bar path replay card
            if (summary.repBarPaths.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                BarPathReplayCard(repBarPaths = summary.repBarPaths)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Aggregate form issues
            if (summary.aggregateFormIssues.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.set_summary_form_issues),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                summary.aggregateFormIssues.forEach { aggregate ->
                    val reps = aggregate.repNumbers.joinToString(", ")
                    val icon = when (aggregate.issue.severity) {
                        Severity.CRITICAL -> "\u26A0\uFE0F"
                        Severity.WARNING -> "\u26A0"
                        Severity.INFO -> "\u2139"
                    }
                    Text(
                        text = "$icon ${aggregate.issue.message} (reps $reps)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    label = stringResource(R.string.set_summary_avg_velocity),
                    value = "%.2f m/s".format(summary.avgVelocity.value)
                )
                StatItem(
                    label = stringResource(R.string.set_summary_velocity_loss),
                    value = "${(summary.velocityLossPercent * 100).toInt()}%%"
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Dismiss button
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.set_summary_dismiss))
            }
        }
    }
}

@Composable
fun ScoreBar(score: Int, grade: ScoreGrade) {
    val gradeColor = Color(grade.colorHex)

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$score/100",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = gradeColor
            )
            Text(
                text = grade.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = gradeColor
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { score / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = gradeColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@Composable
fun RepScoreTable(
    scores: List<RepQualityScore>,
    velocities: List<com.lifthelper.domain.value.MetersPerSecond>,
    bestIndex: Int,
    worstIndex: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        // Header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            TableCell(text = stringResource(R.string.set_summary_rep_header), weight = 0.8f, bold = true)
            TableCell(text = stringResource(R.string.set_summary_vel_header), weight = 1f, bold = true)
            TableCell(text = stringResource(R.string.set_summary_depth_header), weight = 1f, bold = true)
            TableCell(text = stringResource(R.string.set_summary_form_header), weight = 0.8f, bold = true)
            TableCell(text = stringResource(R.string.set_summary_tempo_header), weight = 1f, bold = true)
            TableCell(text = stringResource(R.string.set_summary_score_header), weight = 1f, bold = true)
            TableCell(text = "", weight = 0.6f, bold = false) // marker column
        }

        // Data rows
        scores.forEachIndexed { index, score ->
            val velocity = velocities.getOrNull(index)
            val marker = when (index) {
                bestIndex -> "\u2605" // star
                worstIndex -> "\u25BC" // down triangle
                else -> ""
            }
            val rowColor = if (index % 2 == 0) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(rowColor)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                TableCell(text = "${index + 1}", weight = 0.8f)
                TableCell(text = velocity?.let { "%.2f".format(it.value) } ?: "-", weight = 1f)
                TableCell(text = if (score.depthAchieved) "\u2713" else "~", weight = 1f)
                TableCell(text = "${score.formScore}", weight = 0.8f)
                TableCell(text = "${score.tempoScore}", weight = 1f)
                TableCell(
                    text = "${score.totalScore}",
                    weight = 1f,
                    color = Color(score.grade.colorHex)
                )
                TableCell(text = marker, weight = 0.6f)
            }
        }
    }
}

@Composable
fun RowScope.TableCell(
    text: String,
    weight: Float,
    bold: Boolean = false,
    color: Color = Color.Unspecified
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier.weight(weight)
    )
}

// Uses shared StatItem from com.lifthelper.ui.components.common

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
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedExercise.getDisplayName(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Exercise") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
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
            com.lifthelper.domain.model.ExerciseType.entries
                .filter { !it.isJumpExercise() }
                .forEach { exercise ->
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

@Composable
fun WorkoutSetupPanel(
    selectedExercise: com.lifthelper.domain.model.ExerciseType,
    weight: Float,
    targetReps: Int,
    onExerciseSelected: (com.lifthelper.domain.model.ExerciseType) -> Unit,
    onWeightChanged: (Float) -> Unit,
    onTargetRepsChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var weightText by remember(weight) {
        mutableStateOf(if (weight > 0f) weight.toInt().toString() else "")
    }
    var repsText by remember(targetReps) {
        mutableStateOf(targetReps.toString())
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                RoundedCornerShape(16.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ExerciseSelector(
            selectedExercise = selectedExercise,
            onExerciseSelected = onExerciseSelected,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = weightText,
                onValueChange = { text ->
                    weightText = text
                    text.toFloatOrNull()?.let { onWeightChanged(it) }
                },
                label = { Text(stringResource(R.string.weight_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                )
            )
            OutlinedTextField(
                value = repsText,
                onValueChange = { text ->
                    repsText = text
                    text.toIntOrNull()?.let { onTargetRepsChanged(it) }
                },
                label = { Text(stringResource(R.string.target_reps)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                )
            )
        }
    }
}

@Composable
fun WorkoutInfoChip(
    exerciseName: String,
    weight: Float,
    targetReps: Int,
    modifier: Modifier = Modifier
) {
    Text(
        text = "$exerciseName \u00B7 ${weight.toInt()}kg \u00B7 $targetReps reps",
        style = MaterialTheme.typography.labelLarge,
        color = Color.White,
        modifier = modifier
            .background(
                Color.Black.copy(alpha = 0.6f),
                RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
fun VelocityLossStopAlert(
    thresholdPercent: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(VbtStopAlert.copy(alpha = 0.95f), RoundedCornerShape(12.dp))
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.vbt_stop_alert_title, thresholdPercent),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.vbt_stop_alert_message),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.content_desc_dismiss),
                tint = Color.White
            )
        }
    }
}

// Helper ViewModel to inject CameraManager
@dagger.hilt.android.lifecycle.HiltViewModel
class WorkoutCameraViewModel @Inject constructor(
    val cameraManager: CameraManager
) : androidx.lifecycle.ViewModel()
