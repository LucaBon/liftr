package com.liftr.vision.adapter

import androidx.camera.core.ImageProxy
import com.liftr.domain.model.CameraFrame
import com.liftr.domain.model.FrameData
import com.liftr.domain.model.FrameProcessor

/**
 * Adapter that converts ImageProxy to domain CameraFrame.
 * This isolates Android-specific camera types from the domain layer.
 */
object ImageProxyAdapter {
    /**
     * Converts an ImageProxy to a domain CameraFrame.
     *
     * @param imageProxy The Android CameraX ImageProxy
     * @return Domain-layer CameraFrame
     */
    fun toCameraFrame(imageProxy: ImageProxy): CameraFrame {
        return CameraFrame(
            width = imageProxy.width,
            height = imageProxy.height,
            timestamp = System.currentTimeMillis(),
            rotationDegrees = imageProxy.imageInfo.rotationDegrees,
            data = ImageProxyFrameData(imageProxy)
        )
    }
}

/**
 * FrameData implementation that wraps ImageProxy.
 * Allows the domain layer to process frames without knowing about ImageProxy.
 */
private class ImageProxyFrameData(
    private val imageProxy: ImageProxy
) : FrameData {
    override fun <T> process(processor: FrameProcessor<T>): T {
        return processor.process(imageProxy)
    }
}
