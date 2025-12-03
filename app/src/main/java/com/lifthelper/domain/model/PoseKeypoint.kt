package com.lifthelper.domain.model

data class PoseKeypoint(
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float
)

data class PoseResult(
    val landmarks: List<PoseKeypoint>,
    val timestamp: Long,
    val imageWidth: Int = 640,
    val imageHeight: Int = 480
) {
    companion object {
        // MediaPipe Pose landmark indices
        const val NOSE = 0
        const val LEFT_SHOULDER = 11
        const val RIGHT_SHOULDER = 12
        const val LEFT_ELBOW = 13
        const val RIGHT_ELBOW = 14
        const val LEFT_WRIST = 15
        const val RIGHT_WRIST = 16
        const val LEFT_HIP = 23
        const val RIGHT_HIP = 24
        const val LEFT_KNEE = 25
        const val RIGHT_KNEE = 26
        const val LEFT_ANKLE = 27
        const val RIGHT_ANKLE = 28
    }
}
