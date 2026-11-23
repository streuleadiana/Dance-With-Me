package com.dancewithme.app.data

import kotlinx.serialization.Serializable

/**
 * Reprezintă un cadru (frame) de postură capturat la un anumit moment de timp.
 * Conține cele 33 de puncte (landmarks) standard MediaPipe Pose.
 */
@Serializable
data class PoseFrame(
    val timestamp: Long,
    val landmarks: List<Point3D>
) {
    companion object {
        const val REQUIRED_LANDMARKS_COUNT = 33

        // Indici standard MediaPipe Pose Landmarker (BlazePose 33 repere)
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
