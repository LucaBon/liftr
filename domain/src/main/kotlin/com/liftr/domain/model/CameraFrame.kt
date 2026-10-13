package com.liftr.domain.model

/**
 * Domain representation of a camera frame for pose detection.
 *
 * This abstraction isolates the domain layer from Android-specific camera implementations,
 * allowing for testing without Android dependencies and potential reuse in other platforms.
 *
 * @property width Frame width in pixels
 * @property height Frame height in pixels
 * @property timestamp Frame timestamp in milliseconds
 * @property rotationDegrees Rotation applied to the frame (0, 90, 180, or 270 degrees)
 * @property data Frame data accessor for conversion to image formats
 */
data class CameraFrame(
    val width: Int,
    val height: Int,
    val timestamp: Long,
    val rotationDegrees: Int,
    val data: FrameData
)

/**
 * Interface for accessing frame data in a platform-agnostic way.
 * Implementations can wrap ImageProxy, Bitmap, or other platform-specific types.
 */
interface FrameData {
    /**
     * Provides a way to process the frame data.
     * The processor receives platform-specific data and returns a result.
     */
    fun <T> process(processor: FrameProcessor<T>): T
}

/**
 * Processor interface for handling platform-specific frame data.
 * Implementations in the infrastructure layer handle the actual conversion.
 */
interface FrameProcessor<T> {
    fun process(data: Any): T
}
