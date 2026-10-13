package com.liftr.domain.validation

import com.liftr.domain.model.PoseKeypoint

/**
 * Validates pose landmarks for anthropometric data extraction.
 *
 * This class is responsible for checking if detected pose landmarks
 * meet the minimum quality and visibility requirements for accurate
 * body measurements.
 */
class PoseValidator {

    /**
     * Validates that all critical body landmarks are visible with sufficient confidence.
     *
     * Critical landmarks include:
     * - Shoulders (11, 12)
     * - Hips (23, 24)
     * - Knees (25, 26)
     *
     * @param landmarks List of detected pose keypoints
     * @return ValidationResult indicating if pose is valid or which landmarks are missing
     */
    fun validateCriticalLandmarks(landmarks: List<PoseKeypoint>): ValidationResult {
        val criticalBodyLandmarks = listOf(
            11, 12, // shoulders
            23, 24, // hips
            25, 26  // knees
        )

        val missingLandmarks = criticalBodyLandmarks.filter { idx ->
            val visibility = landmarks.getOrNull(idx)?.visibility ?: 0f
            visibility < VISIBILITY_THRESHOLD
        }

        return if (missingLandmarks.isEmpty()) {
            ValidationResult.Valid
        } else {
            val landmarkNames = missingLandmarks.map { getLandmarkName(it) }
            val visibilities = missingLandmarks.map {
                landmarks.getOrNull(it)?.visibility ?: 0f
            }

            ValidationResult.Invalid(
                reason = "Missing critical body landmarks",
                missingLandmarks = missingLandmarks,
                landmarkNames = landmarkNames,
                visibilities = visibilities
            )
        }
    }

    /**
     * Validates that at least one head landmark is visible.
     *
     * Head landmarks include:
     * - Nose (0)
     * - Left ear (7)
     * - Right ear (8)
     *
     * @param landmarks List of detected pose keypoints
     * @return true if at least one head landmark is visible
     */
    fun validateHeadLandmarks(landmarks: List<PoseKeypoint>): Boolean {
        val headLandmarks = listOf(0, 7, 8)  // nose, left_ear, right_ear

        return headLandmarks.any { idx ->
            landmarks.getOrNull(idx)?.visibility?.let { it >= VISIBILITY_THRESHOLD } ?: false
        }
    }

    /**
     * Checks if both ankle landmarks are visible with sufficient confidence.
     *
     * Ankle landmarks: 27 (left), 28 (right)
     *
     * @param landmarks List of detected pose keypoints
     * @return true if both ankles are visible
     */
    fun validateAnklesVisible(landmarks: List<PoseKeypoint>): Boolean {
        val ankleIndices = listOf(27, 28)

        return ankleIndices.all { idx ->
            landmarks.getOrNull(idx)?.visibility?.let { it >= VISIBILITY_THRESHOLD } ?: false
        }
    }

    /**
     * Gets the list of visible head landmark indices.
     *
     * @param landmarks List of detected pose keypoints
     * @return List of indices of visible head landmarks
     */
    fun getVisibleHeadLandmarks(landmarks: List<PoseKeypoint>): List<Int> {
        val headLandmarks = listOf(0, 7, 8)  // nose, left_ear, right_ear

        return headLandmarks.filter { idx ->
            landmarks.getOrNull(idx)?.visibility?.let { it >= VISIBILITY_THRESHOLD } ?: false
        }
    }

    /**
     * Checks if left elbow is visible.
     *
     * @param landmarks List of detected pose keypoints
     * @return true if left elbow (13) is visible
     */
    fun validateLeftElbowVisible(landmarks: List<PoseKeypoint>): Boolean {
        return (landmarks.getOrNull(13)?.visibility ?: 0f) >= VISIBILITY_THRESHOLD
    }

    /**
     * Checks if left wrist is visible.
     *
     * @param landmarks List of detected pose keypoints
     * @return true if left wrist (15) is visible
     */
    fun validateLeftWristVisible(landmarks: List<PoseKeypoint>): Boolean {
        return (landmarks.getOrNull(15)?.visibility ?: 0f) >= VISIBILITY_THRESHOLD
    }

    /**
     * Gets a human-readable name for a landmark index.
     *
     * @param index Landmark index
     * @return Human-readable name
     */
    private fun getLandmarkName(index: Int): String {
        return when (index) {
            0 -> "nose"
            7 -> "left_ear"
            8 -> "right_ear"
            11 -> "left_shoulder"
            12 -> "right_shoulder"
            13 -> "left_elbow"
            15 -> "left_wrist"
            23 -> "left_hip"
            24 -> "right_hip"
            25 -> "left_knee"
            26 -> "right_knee"
            27 -> "left_ankle"
            28 -> "right_ankle"
            else -> "landmark_$index"
        }
    }

    companion object {
        /**
         * Minimum visibility threshold for a landmark to be considered valid.
         * Range: 0.0 (not visible) to 1.0 (fully visible)
         */
        private const val VISIBILITY_THRESHOLD = 0.5f
    }
}

/**
 * Result of pose validation.
 */
sealed class ValidationResult {
    /**
     * Pose is valid and meets all requirements.
     */
    object Valid : ValidationResult()

    /**
     * Pose is invalid due to missing or low-confidence landmarks.
     *
     * @property reason Description of why validation failed
     * @property missingLandmarks List of landmark indices that are missing
     * @property landmarkNames Human-readable names of missing landmarks
     * @property visibilities Visibility scores of the missing landmarks
     */
    data class Invalid(
        val reason: String,
        val missingLandmarks: List<Int>,
        val landmarkNames: List<String> = emptyList(),
        val visibilities: List<Float> = emptyList()
    ) : ValidationResult()
}
