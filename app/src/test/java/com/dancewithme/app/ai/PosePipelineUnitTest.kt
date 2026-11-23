package com.dancewithme.app.ai

import com.dancewithme.app.data.PoseFrame
import com.dancewithme.app.engine.JointAngleCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Teste unitare pentru verificarea extragerii scheletului 3D (33 puncte) și integrării cu
 * [JointAngleCalculator] la 30 FPS.
 */
class PosePipelineUnitTest {

    @Test
    fun `simulated PoseLandmarker output produces 33 valid 3D landmarks and converts to AngleVector`() {
        val output = PoseLandmarkerHelper.generateSimulatedPoseOutput(timestampMs = 1000L)

        assertEquals(PoseFrame.REQUIRED_LANDMARKS_COUNT, output.poseFrame.landmarks.size)
        assertEquals(PoseFrame.REQUIRED_LANDMARKS_COUNT, output.normalizedLandmarks.size)
        assertTrue(output.isSimulated)

        val angleVector = JointAngleCalculator.extractAngleVector(output.poseFrame)

        // Verificăm că toate cele 6 unghiuri articulare sunt în intervalul fizic valid (0°..180°)
        assertTrue(angleVector.genunchiStang in 1f..180f)
        assertTrue(angleVector.genunchiDrept in 1f..180f)
        assertTrue(angleVector.soldStang in 1f..180f)
        assertTrue(angleVector.soldDrept in 1f..180f)
        assertTrue(angleVector.cotStang in 1f..180f)
        assertTrue(angleVector.cotDrept in 1f..180f)
    }

    @Test
    fun `simulated 30 FPS stream over 20 seconds produces dynamic joint angle trajectories`() {
        val framesCount = 600 // 20 secunde @ 30 FPS
        val angleVectors = (0 until framesCount).map { frameIndex ->
            val timestampMs = frameIndex * 33L
            val output = PoseLandmarkerHelper.generateSimulatedPoseOutput(timestampMs)
            JointAngleCalculator.extractAngleVector(output.poseFrame)
        }

        assertEquals(600, angleVectors.size)

        // Verificăm că unghiurile variază în timp (nu sunt statice)
        val minLeftArm = angleVectors.minOf { it.cotStang }
        val maxLeftArm = angleVectors.maxOf { it.cotStang }
        assertTrue("Expected dynamic arm motion, got min=$minLeftArm, max=$maxLeftArm", (maxLeftArm - minLeftArm) > 5f)
    }
}
