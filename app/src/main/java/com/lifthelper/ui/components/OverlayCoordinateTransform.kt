package com.lifthelper.ui.components

import androidx.compose.ui.geometry.Offset

/**
 * Shared coordinate transformation utilities for camera overlays.
 *
 * Used by [PoseOverlay] and [BarPathOverlay] to convert normalized pose
 * coordinates to canvas pixel positions, accounting for camera rotation
 * and front-camera mirroring.
 */
object OverlayCoordinateTransform {

    /**
     * Scaling parameters for mapping image coordinates to canvas coordinates.
     */
    data class OverlayScaling(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float,
        val rotatedImageWidth: Int,
        val rotatedImageHeight: Int
    )

    /**
     * Calculates FIT_CENTER scaling to map the rotated image into the canvas.
     */
    fun calculateScaling(
        imageWidth: Int,
        imageHeight: Int,
        canvasWidth: Float,
        canvasHeight: Float,
        rotationDegrees: Int
    ): OverlayScaling {
        val rotatedImageWidth = if (rotationDegrees == 90 || rotationDegrees == 270) imageHeight else imageWidth
        val rotatedImageHeight = if (rotationDegrees == 90 || rotationDegrees == 270) imageWidth else imageHeight

        val scale = minOf(
            canvasWidth / rotatedImageWidth,
            canvasHeight / rotatedImageHeight
        )

        val scaledWidth = rotatedImageWidth * scale
        val scaledHeight = rotatedImageHeight * scale

        return OverlayScaling(
            scale = scale,
            offsetX = (canvasWidth - scaledWidth) / 2f,
            offsetY = (canvasHeight - scaledHeight) / 2f,
            rotatedImageWidth = rotatedImageWidth,
            rotatedImageHeight = rotatedImageHeight
        )
    }

    /**
     * Transforms normalized coordinates (0-1) based on camera rotation and mirroring.
     *
     * @param x Normalized X coordinate from pose detection (0.0-1.0)
     * @param y Normalized Y coordinate from pose detection (0.0-1.0)
     * @param rotation Camera rotation in degrees (0, 90, 180, 270)
     * @param isFrontCamera Whether the front camera is active (requires horizontal mirror)
     * @return Transformed (x, y) pair in rotated coordinate space (0.0-1.0)
     */
    fun transformCoordinates(x: Float, y: Float, rotation: Int, isFrontCamera: Boolean): Pair<Float, Float> {
        val mirroredX = if (isFrontCamera) 1f - x else x

        return when (rotation) {
            90 -> Pair(1f - y, mirroredX)
            180 -> Pair(1f - mirroredX, 1f - y)
            270 -> Pair(y, 1f - mirroredX)
            else -> Pair(mirroredX, y)
        }
    }

    /**
     * Converts a normalized coordinate to a canvas pixel Offset.
     *
     * @param normalizedX Normalized X (0.0-1.0) from pose detection
     * @param normalizedY Normalized Y (0.0-1.0) from pose detection
     * @param scaling Pre-calculated overlay scaling parameters
     * @param rotation Camera rotation in degrees
     * @param isFrontCamera Whether the front camera is active
     * @return Canvas pixel Offset for drawing
     */
    fun toCanvasOffset(
        normalizedX: Float,
        normalizedY: Float,
        scaling: OverlayScaling,
        rotation: Int,
        isFrontCamera: Boolean
    ): Offset {
        val (tx, ty) = transformCoordinates(normalizedX, normalizedY, rotation, isFrontCamera)
        return Offset(
            x = tx * scaling.rotatedImageWidth * scaling.scale + scaling.offsetX,
            y = ty * scaling.rotatedImageHeight * scaling.scale + scaling.offsetY
        )
    }
}
