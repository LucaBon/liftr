package com.liftr.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.liftr.domain.model.PoseResult

@Composable
fun PoseOverlay(
    poseResult: PoseResult?,
    imageWidth: Int,
    imageHeight: Int,
    modifier: Modifier = Modifier,
    rotationDegrees: Int = 90, // Default rotation for portrait mode
    isFrontCamera: Boolean = false // Front camera requires horizontal mirroring
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        poseResult?.let { pose ->
            val scaling = OverlayCoordinateTransform.calculateScaling(
                imageWidth, imageHeight, size.width, size.height, rotationDegrees
            )

            // Draw skeleton connections
            drawPoseConnections(
                pose, scaling, rotationDegrees, isFrontCamera
            )

            // Draw keypoints
            pose.landmarks.forEach { landmark ->
                if (landmark.visibility > 0.5f) {
                    val offset = OverlayCoordinateTransform.toCanvasOffset(
                        landmark.x, landmark.y, scaling, rotationDegrees, isFrontCamera
                    )

                    drawCircle(
                        color = Color.Green,
                        radius = 12f,
                        center = offset
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawPoseConnections(
    pose: PoseResult,
    scaling: OverlayCoordinateTransform.OverlayScaling,
    rotationDegrees: Int,
    isFrontCamera: Boolean
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
                val startOffset = OverlayCoordinateTransform.toCanvasOffset(
                    startLandmark.x, startLandmark.y, scaling, rotationDegrees, isFrontCamera
                )
                val endOffset = OverlayCoordinateTransform.toCanvasOffset(
                    endLandmark.x, endLandmark.y, scaling, rotationDegrees, isFrontCamera
                )

                drawLine(
                    color = Color.White,
                    start = startOffset,
                    end = endOffset,
                    strokeWidth = 6f
                )
            }
        }
    }
}
