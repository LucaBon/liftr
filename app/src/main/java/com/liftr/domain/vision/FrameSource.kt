package com.liftr.domain.vision

import com.liftr.domain.model.CameraFrame

/**
 * A platform camera frame handed to the domain layer.
 *
 * Wraps the platform buffer (e.g. CameraX ImageProxy) so domain code can
 * convert it and release it without depending on the camera API.
 */
interface FrameSource {
    /** Wraps the underlying buffer as a [CameraFrame]; valid until [release]. */
    fun toCameraFrame(): CameraFrame

    /** Returns the underlying buffer to the platform. Called exactly once. */
    fun release()
}
