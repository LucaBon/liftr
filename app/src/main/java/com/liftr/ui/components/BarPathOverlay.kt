package com.liftr.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import com.liftr.domain.exercise.BarPathAnalyzer
import com.liftr.domain.model.BarPathPoint
import com.liftr.ui.theme.BarPathReference
import com.liftr.ui.theme.BarPathStart

/**
 * Real-time bar path overlay drawn on the camera preview during recording.
 *
 * Draws the barbell's trajectory as a velocity-colored polyline, with a
 * dashed vertical reference line from the first captured point's X position.
 */
@Composable
fun BarPathOverlay(
    barPathPoints: List<BarPathPoint>,
    imageWidth: Int,
    imageHeight: Int,
    modifier: Modifier = Modifier,
    rotationDegrees: Int = 90,
    isFrontCamera: Boolean = false
) {
    if (barPathPoints.isEmpty()) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val scaling = OverlayCoordinateTransform.calculateScaling(
            imageWidth, imageHeight, size.width, size.height, rotationDegrees
        )

        val firstPoint = barPathPoints.first()

        // Dashed vertical reference line through the bar's starting position.
        // It has to be built in canvas space: sweeping the image-space Y from 0
        // to 1 at a fixed image-space X produces a line that the 90°/270°
        // rotation turns on its side, drawing a horizontal line across the
        // screen — which is what happened on every portrait workout.
        val startRef = OverlayCoordinateTransform.toCanvasOffset(
            firstPoint.normalizedX, firstPoint.normalizedY, scaling, rotationDegrees, isFrontCamera
        )
        drawLine(
            color = BarPathReference,
            start = Offset(startRef.x, scaling.offsetY),
            end = Offset(startRef.x, scaling.offsetY + scaling.rotatedImageHeight * scaling.scale),
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
        )

        // Draw velocity-colored polyline segments
        for (i in 0 until barPathPoints.size - 1) {
            val current = barPathPoints[i]
            val next = barPathPoints[i + 1]

            val startOffset = OverlayCoordinateTransform.toCanvasOffset(
                current.normalizedX, current.normalizedY, scaling, rotationDegrees, isFrontCamera
            )
            val endOffset = OverlayCoordinateTransform.toCanvasOffset(
                next.normalizedX, next.normalizedY, scaling, rotationDegrees, isFrontCamera
            )

            val colorArgb = BarPathAnalyzer.velocityToColorArgb(next.velocity)
            drawLine(
                color = Color(colorArgb),
                start = startOffset,
                end = endOffset,
                strokeWidth = 4f
            )
        }

        // Draw start marker (green dot)
        val startOffset = OverlayCoordinateTransform.toCanvasOffset(
            firstPoint.normalizedX, firstPoint.normalizedY, scaling, rotationDegrees, isFrontCamera
        )
        drawCircle(
            color = BarPathStart,
            radius = 8f,
            center = startOffset
        )

        // Draw current position marker (white dot)
        val lastPoint = barPathPoints.last()
        val endOffset = OverlayCoordinateTransform.toCanvasOffset(
            lastPoint.normalizedX, lastPoint.normalizedY, scaling, rotationDegrees, isFrontCamera
        )
        drawCircle(
            color = Color.White,
            radius = 6f,
            center = endOffset
        )
    }
}
