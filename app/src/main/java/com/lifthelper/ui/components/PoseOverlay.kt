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
    modifier: Modifier = Modifier,
    rotationDegrees: Int = 90, // Default rotation for portrait mode
    isFrontCamera: Boolean = false // Front camera requires horizontal mirroring
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        poseResult?.let { pose ->
            // Calculate scaling and offset for aspect ratio correction
            // After rotation, image dimensions are swapped
            val rotatedImageWidth = if (rotationDegrees == 90 || rotationDegrees == 270) imageHeight else imageWidth
            val rotatedImageHeight = if (rotationDegrees == 90 || rotationDegrees == 270) imageWidth else imageHeight

            val imageAspect = rotatedImageWidth.toFloat() / rotatedImageHeight.toFloat()
            val canvasAspect = size.width / size.height

            // FIT_CENTER: scale to fit inside canvas, maintaining aspect ratio
            val scale = minOf(
                size.width / rotatedImageWidth,
                size.height / rotatedImageHeight
            )

            val scaledWidth = rotatedImageWidth * scale
            val scaledHeight = rotatedImageHeight * scale

            val offsetX = (size.width - scaledWidth) / 2f
            val offsetY = (size.height - scaledHeight) / 2f

            // Use uniform scaling to maintain aspect ratio
            val scaleX = scale
            val scaleY = scale

            // Draw skeleton connections
            drawPoseConnections(
                pose,
                rotationDegrees,
                scaleX,
                scaleY,
                offsetX,
                offsetY,
                rotatedImageWidth,
                rotatedImageHeight,
                isFrontCamera
            )

            // Draw keypoints
            pose.landmarks.forEach { landmark ->
                if (landmark.visibility > 0.5f) {
                    // Transform coordinates based on rotation and camera
                    val (x, y) = transformCoordinates(
                        landmark.x,
                        landmark.y,
                        rotationDegrees,
                        isFrontCamera
                    )

                    drawCircle(
                        color = Color.Green,
                        radius = 12f,
                        center = Offset(
                            x = x * rotatedImageWidth * scaleX + offsetX,
                            y = y * rotatedImageHeight * scaleY + offsetY
                        )
                    )
                }
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

private fun transformCoordinates(x: Float, y: Float, rotation: Int, isFrontCamera: Boolean): Pair<Float, Float> {
    // First apply camera mirroring if using front camera
    val mirroredX = if (isFrontCamera) 1f - x else x

    // Then apply rotation transformation
    return when (rotation) {
        90 -> Pair(1f - y, mirroredX)      // Rotate 90° clockwise: swap and invert Y
        180 -> Pair(1f - mirroredX, 1f - y) // Rotate 180°: invert both
        270 -> Pair(y, 1f - mirroredX)      // Rotate 270° clockwise: swap and invert X
        else -> Pair(mirroredX, y)          // No rotation
    }
}

private fun DrawScope.drawPoseConnections(
    pose: PoseResult,
    rotationDegrees: Int = 90,
    scaleX: Float = 1f,
    scaleY: Float = 1f,
    offsetX: Float = 0f,
    offsetY: Float = 0f,
    rotatedImageWidth: Int,
    rotatedImageHeight: Int,
    isFrontCamera: Boolean = false
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
                // Transform coordinates based on rotation and camera
                val (startX, startY) = transformCoordinates(
                    startLandmark.x,
                    startLandmark.y,
                    rotationDegrees,
                    isFrontCamera
                )
                val (endX, endY) = transformCoordinates(
                    endLandmark.x,
                    endLandmark.y,
                    rotationDegrees,
                    isFrontCamera
                )

                drawLine(
                    color = Color.White,
                    start = Offset(
                        x = startX * rotatedImageWidth * scaleX + offsetX,
                        y = startY * rotatedImageHeight * scaleY + offsetY
                    ),
                    end = Offset(
                        x = endX * rotatedImageWidth * scaleX + offsetX,
                        y = endY * rotatedImageHeight * scaleY + offsetY
                    ),
                    strokeWidth = 6f
                )
            }
        }
    }
}
