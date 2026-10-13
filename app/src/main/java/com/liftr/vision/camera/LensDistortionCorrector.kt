package com.liftr.vision.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.PoseKeypoint
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Handles lens distortion correction using camera intrinsic parameters.
 *
 * Smartphone cameras, especially wide-angle lenses, introduce barrel distortion
 * that makes straight lines appear curved and affects distance measurements
 * near the frame edges (errors up to 10%).
 *
 * This class retrieves camera intrinsic calibration parameters (focal length,
 * principal point, distortion coefficients) and applies the Brown-Conrady
 * distortion model to correct pose landmark coordinates.
 *
 * Fallback strategy:
 * 1. Use device-specific intrinsics if available (API 23+, best accuracy)
 * 2. Use simplified empirical model if intrinsics unavailable (good accuracy)
 * 3. No correction if disabled (faster but less accurate at edges)
 */
@Singleton
class LensDistortionCorrector @Inject constructor(
    private val logger: Logger
) {
    companion object {
        private const val TAG = "LensDistortionCorrector"

        // Empirical distortion coefficient for typical smartphone wide-angle camera
        // Negative value indicates barrel distortion (outward bulge)
        private const val EMPIRICAL_K1 = -0.20f

        // Minimum radial distance from center to apply correction
        // Avoids numerical instability near the center where correction is negligible
        private const val MIN_RADIUS_THRESHOLD = 0.01f
    }

    /**
     * Camera intrinsic calibration data
     */
    data class CameraIntrinsics(
        val focalLengthX: Float,      // fx - horizontal focal length (pixels)
        val focalLengthY: Float,      // fy - vertical focal length (pixels)
        val principalPointX: Float,   // cx - optical center X (pixels)
        val principalPointY: Float,   // cy - optical center Y (pixels)
        val distortionCoeffs: FloatArray,  // [k1, k2, k3, k4, k5, k6] - radial distortion
        val imageWidth: Int,
        val imageHeight: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as CameraIntrinsics
            return focalLengthX == other.focalLengthX &&
                    focalLengthY == other.focalLengthY &&
                    principalPointX == other.principalPointX &&
                    principalPointY == other.principalPointY &&
                    distortionCoeffs.contentEquals(other.distortionCoeffs) &&
                    imageWidth == other.imageWidth &&
                    imageHeight == other.imageHeight
        }

        override fun hashCode(): Int {
            var result = focalLengthX.hashCode()
            result = 31 * result + focalLengthY.hashCode()
            result = 31 * result + principalPointX.hashCode()
            result = 31 * result + principalPointY.hashCode()
            result = 31 * result + distortionCoeffs.contentHashCode()
            result = 31 * result + imageWidth
            result = 31 * result + imageHeight
            return result
        }
    }

    // Thread-safe: accessed from camera switch thread and frame processing thread (BUG-F fix)
    @Volatile private var cachedIntrinsics: CameraIntrinsics? = null
    @Volatile private var correctionMode: CorrectionMode = CorrectionMode.AUTO

    enum class CorrectionMode {
        AUTO,           // Use intrinsics if available, fallback to empirical
        INTRINSICS,     // Use device-specific intrinsics only
        EMPIRICAL,      // Use simplified empirical model
        NONE            // No correction (fastest, least accurate)
    }

    /**
     * Retrieve camera intrinsic parameters from the device.
     *
     * Requires Android API 23+ and camera hardware support.
     * Returns null if intrinsics are unavailable.
     */
    fun retrieveIntrinsics(
        cameraManager: CameraManager,
        cameraId: String,
        imageWidth: Int,
        imageHeight: Int
    ): CameraIntrinsics? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            logger.w(TAG, "Camera intrinsics not available on API < 23")
            return null
        }

        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)

            // Retrieve intrinsic calibration [fx, fy, cx, cy, s]
            val intrinsicCalibration = characteristics.get(
                CameraCharacteristics.LENS_INTRINSIC_CALIBRATION
            )

            // Retrieve distortion coefficients [k1, k2, k3, k4, k5, k6]
            val distortionCoeffs = characteristics.get(
                CameraCharacteristics.LENS_DISTORTION
            )

            if (intrinsicCalibration == null || distortionCoeffs == null) {
                logger.w(TAG, "Camera intrinsics or distortion coefficients not available")
                return null
            }

            if (intrinsicCalibration.size < 5 || distortionCoeffs.size < 5) {
                logger.w(TAG, "Incomplete intrinsic calibration data")
                return null
            }

            val intrinsics = CameraIntrinsics(
                focalLengthX = intrinsicCalibration[0],
                focalLengthY = intrinsicCalibration[1],
                principalPointX = intrinsicCalibration[2],
                principalPointY = intrinsicCalibration[3],
                // intrinsicCalibration[4] is skew (s), typically 0 for modern cameras
                distortionCoeffs = distortionCoeffs,
                imageWidth = imageWidth,
                imageHeight = imageHeight
            )

            cachedIntrinsics = intrinsics

            logger.i(TAG, "✅ Camera intrinsics retrieved: fx=${intrinsics.focalLengthX}, " +
                    "fy=${intrinsics.focalLengthY}, " +
                    "cx=${intrinsics.principalPointX}, cy=${intrinsics.principalPointY}, " +
                    "k1=${distortionCoeffs[0]}")

            intrinsics
        } catch (e: Exception) {
            logger.e(TAG, "Failed to retrieve camera intrinsics: ${e.message}")
            null
        }
    }

    /**
     * Correct a single pose landmark for lens distortion.
     *
     * @param keypoint Original pose keypoint with normalized coordinates (0-1 range)
     * @param imageWidth Width of the camera frame in pixels
     * @param imageHeight Height of the camera frame in pixels
     * @return Corrected keypoint with distortion removed
     */
    fun correctLandmark(
        keypoint: PoseKeypoint,
        imageWidth: Int,
        imageHeight: Int
    ): PoseKeypoint {
        when (correctionMode) {
            CorrectionMode.NONE -> return keypoint

            CorrectionMode.INTRINSICS -> {
                val intrinsics = cachedIntrinsics
                return if (intrinsics != null &&
                          intrinsics.imageWidth == imageWidth &&
                          intrinsics.imageHeight == imageHeight) {
                    correctWithIntrinsics(keypoint, intrinsics)
                } else {
                    logger.w(TAG, "Intrinsics not available or image size mismatch, " +
                            "skipping correction")
                    keypoint
                }
            }

            CorrectionMode.EMPIRICAL -> {
                return correctWithEmpiricalModel(keypoint, imageWidth, imageHeight)
            }

            CorrectionMode.AUTO -> {
                val intrinsics = cachedIntrinsics
                return if (intrinsics != null &&
                          intrinsics.imageWidth == imageWidth &&
                          intrinsics.imageHeight == imageHeight) {
                    correctWithIntrinsics(keypoint, intrinsics)
                } else {
                    correctWithEmpiricalModel(keypoint, imageWidth, imageHeight)
                }
            }
        }
    }

    /**
     * Correct all pose landmarks in a list.
     *
     * @param landmarks List of pose keypoints
     * @param imageWidth Width of the camera frame
     * @param imageHeight Height of the camera frame
     * @return List of corrected keypoints
     */
    fun correctLandmarks(
        landmarks: List<PoseKeypoint>,
        imageWidth: Int,
        imageHeight: Int
    ): List<PoseKeypoint> {
        return landmarks.map { correctLandmark(it, imageWidth, imageHeight) }
    }

    /**
     * Apply Brown-Conrady distortion model using device-specific intrinsics.
     *
     * Formula:
     * x_undistorted = x_distorted * (1 + k1*r² + k2*r⁴ + k3*r⁶)
     * y_undistorted = y_distorted * (1 + k1*r² + k2*r⁴ + k3*r⁶)
     *
     * Where r² = x_norm² + y_norm² (squared radial distance from optical center)
     */
    private fun correctWithIntrinsics(
        keypoint: PoseKeypoint,
        intrinsics: CameraIntrinsics
    ): PoseKeypoint {
        // Convert normalized coordinates [0,1] to pixel coordinates
        val xPixel = keypoint.x * intrinsics.imageWidth
        val yPixel = keypoint.y * intrinsics.imageHeight

        // Normalize to camera coordinate system (origin at principal point)
        val xNorm = (xPixel - intrinsics.principalPointX) / intrinsics.focalLengthX
        val yNorm = (yPixel - intrinsics.principalPointY) / intrinsics.focalLengthY

        // Calculate radial distance squared
        val r2 = xNorm * xNorm + yNorm * yNorm

        if (r2 < MIN_RADIUS_THRESHOLD * MIN_RADIUS_THRESHOLD) {
            // Near optical center, no correction needed
            return keypoint
        }

        // Apply radial distortion correction
        // Brown-Conrady model: x' = x * (1 + k1*r² + k2*r⁴ + k3*r⁶)
        val k1 = intrinsics.distortionCoeffs.getOrElse(0) { 0f }
        val k2 = intrinsics.distortionCoeffs.getOrElse(1) { 0f }
        val k3 = intrinsics.distortionCoeffs.getOrElse(4) { 0f }

        val r4 = r2 * r2
        val r6 = r4 * r2

        val radialDistortion = 1.0f + k1 * r2 + k2 * r4 + k3 * r6

        // Apply correction
        val xCorrectedNorm = xNorm * radialDistortion
        val yCorrectedNorm = yNorm * radialDistortion

        // Convert back to pixel coordinates
        val xCorrectedPixel = xCorrectedNorm * intrinsics.focalLengthX + intrinsics.principalPointX
        val yCorrectedPixel = yCorrectedNorm * intrinsics.focalLengthY + intrinsics.principalPointY

        // Convert back to normalized coordinates [0,1]
        val xCorrected = xCorrectedPixel / intrinsics.imageWidth
        val yCorrected = yCorrectedPixel / intrinsics.imageHeight

        return keypoint.copy(
            x = xCorrected.coerceIn(0f, 1f),
            y = yCorrected.coerceIn(0f, 1f)
        )
    }

    /**
     * Apply simplified empirical distortion model.
     *
     * Uses a single radial distortion coefficient (k1 ≈ -0.20) typical
     * for smartphone wide-angle cameras. Less accurate than device-specific
     * intrinsics but works on all devices.
     *
     * Assumes principal point at image center and isotropic focal length.
     *
     * Note: imageWidth and imageHeight are not used in the normalized coordinate
     * space (0-1 range) implementation, but are kept for API consistency.
     */
    private fun correctWithEmpiricalModel(
        keypoint: PoseKeypoint,
        @Suppress("UNUSED_PARAMETER") imageWidth: Int,
        @Suppress("UNUSED_PARAMETER") imageHeight: Int
    ): PoseKeypoint {
        // Distance from normalized center [0.5, 0.5]
        val dx = keypoint.x - 0.5f
        val dy = keypoint.y - 0.5f

        // Radial distance from center
        val r = sqrt(dx * dx + dy * dy)

        if (r < MIN_RADIUS_THRESHOLD) {
            // Near center, no correction needed
            return keypoint
        }

        // Apply barrel distortion correction
        // Barrel distortion: points are pulled toward center (k1 < 0)
        // To correct: push points away from center
        val correction = 1.0f + EMPIRICAL_K1 * r * r

        // Avoid division by zero and extreme corrections
        if (correction < 0.5f || correction > 2.0f) {
            logger.w(TAG, "Extreme correction factor: $correction for r=$r, skipping")
            return keypoint
        }

        // Apply correction radially
        val xCorrected = 0.5f + dx / correction
        val yCorrected = 0.5f + dy / correction

        return keypoint.copy(
            x = xCorrected.coerceIn(0f, 1f),
            y = yCorrected.coerceIn(0f, 1f)
        )
    }

    /**
     * Set the correction mode.
     */
    fun setCorrectionMode(mode: CorrectionMode) {
        correctionMode = mode
        logger.d(TAG, "Correction mode set to: $mode")
    }

    /**
     * Get current correction mode.
     */
    fun getCorrectionMode(): CorrectionMode = correctionMode

    /**
     * Check if intrinsics are available.
     */
    fun hasIntrinsics(): Boolean = cachedIntrinsics != null

    /**
     * Clear cached intrinsics (e.g., when camera changes).
     */
    fun clearCache() {
        cachedIntrinsics = null
        logger.d(TAG, "Intrinsics cache cleared")
    }
}
