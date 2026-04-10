package com.lifthelper.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import com.lifthelper.domain.exercise.BarPathAnalyzer
import com.lifthelper.domain.model.BarPathPoint
import com.lifthelper.ui.theme.BarPathReference
import com.lifthelper.ui.theme.BarPathStart

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

        // Draw dashed vertical reference line from first point's X
        val refTop = OverlayCoordinateTransform.toCanvasOffset(
            firstPoint.normalizedX, 0f, scaling, rotationDegrees, isFrontCamera
        )
        val refBottom = OverlayCoordinateTransform.toCanvasOffset(
            firstPoint.normalizedX, 1f, scaling, rotationDegrees, isFrontCamera
        )
        drawLine(
            color = BarPathReference,
            start = refTop,
            end = refBottom,
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
