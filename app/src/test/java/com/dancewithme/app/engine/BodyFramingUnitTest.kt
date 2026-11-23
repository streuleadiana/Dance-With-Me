package com.dancewithme.app.engine

import com.dancewithme.app.data.AngleVector
import com.dancewithme.app.data.Point3D
import com.dancewithme.app.data.PoseFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Teste unitare pentru [BodyFramingEvaluator]:
 * - Verifică detecția completă a corpului ([FramingStatus.OK]).
 * - Verifică avertizarea când picioarele nu sunt în cadru ([FramingStatus.LEGS_NOT_VISIBLE]).
 * - Verifică avertizarea când umerii/brațele nu sunt în cadru ([FramingStatus.UPPER_BODY_NOT_VISIBLE]).
 * - Verifică avertizarea când nicio persoană nu este vizibilă ([FramingStatus.BODY_NOT_DETECTED]).
 */
class BodyFramingUnitTest {

    private fun createFullVisibleLandmarks(defaultVisibility: Float = 0.95f): MutableList<Point3D> {
        return MutableList(PoseFrame.REQUIRED_LANDMARKS_COUNT) {
            Point3D(x = 0.5f, y = 0.5f, z = 0f, visibility = defaultVisibility)
        }
    }

    @Test
    fun `fully visible body returns FramingStatus OK with null hint and all 8 joints visible`() {
        val landmarks = createFullVisibleLandmarks(defaultVisibility = 0.95f)

        val assessment = BodyFramingEvaluator.evaluate(landmarks)

        assertEquals(FramingStatus.OK, assessment.status)
        assertNull(assessment.hintMessage)
        assertEquals(8, assessment.visibleJoints.size)
        assertTrue(assessment.visibleJoints.values.all { it })
    }

    @Test
    fun `low visibility on knees and ankles returns LEGS_NOT_VISIBLE and marks knees invisible`() {
        val landmarks = createFullVisibleLandmarks(defaultVisibility = 0.90f)
        landmarks[PoseFrame.LEFT_KNEE] = landmarks[PoseFrame.LEFT_KNEE].copy(visibility = 0.2f)
        landmarks[PoseFrame.RIGHT_KNEE] = landmarks[PoseFrame.RIGHT_KNEE].copy(visibility = 0.2f)
        landmarks[PoseFrame.LEFT_ANKLE] = landmarks[PoseFrame.LEFT_ANKLE].copy(visibility = 0.2f)
        landmarks[PoseFrame.RIGHT_ANKLE] = landmarks[PoseFrame.RIGHT_ANKLE].copy(visibility = 0.2f)

        val assessment = BodyFramingEvaluator.evaluate(landmarks)

        assertEquals(FramingStatus.LEGS_NOT_VISIBLE, assessment.status)
        assertNotNull(assessment.hintMessage)
        assertEquals(BodyFramingEvaluator.HINT_LEGS_NOT_VISIBLE, assessment.hintMessage)
        assertFalse(assessment.visibleJoints[AngleVector.JOINT_LEFT_KNEE] ?: true)
        assertFalse(assessment.visibleJoints[AngleVector.JOINT_RIGHT_KNEE] ?: true)
        assertTrue(assessment.visibleJoints[AngleVector.JOINT_LEFT_ELBOW] ?: false)
        assertTrue(assessment.visibleJoints[AngleVector.JOINT_RIGHT_ELBOW] ?: false)
    }

    @Test
    fun `low visibility on shoulders returns UPPER_BODY_NOT_VISIBLE`() {
        val landmarks = createFullVisibleLandmarks(defaultVisibility = 0.90f)
        landmarks[PoseFrame.LEFT_SHOULDER] = landmarks[PoseFrame.LEFT_SHOULDER].copy(visibility = 0.1f)
        landmarks[PoseFrame.RIGHT_SHOULDER] = landmarks[PoseFrame.RIGHT_SHOULDER].copy(visibility = 0.1f)

        val assessment = BodyFramingEvaluator.evaluate(landmarks)

        assertEquals(FramingStatus.UPPER_BODY_NOT_VISIBLE, assessment.status)
        assertNotNull(assessment.hintMessage)
        assertEquals(BodyFramingEvaluator.HINT_UPPER_BODY_NOT_VISIBLE, assessment.hintMessage)
        assertFalse(assessment.visibleJoints[AngleVector.JOINT_LEFT_SHOULDER] ?: true)
        assertFalse(assessment.visibleJoints[AngleVector.JOINT_RIGHT_SHOULDER] ?: true)
    }

    @Test
    fun `empty list or all landmarks with low visibility returns BODY_NOT_DETECTED`() {
        val emptyAssessment = BodyFramingEvaluator.evaluate(emptyList())
        assertEquals(FramingStatus.BODY_NOT_DETECTED, emptyAssessment.status)
        assertEquals(BodyFramingEvaluator.HINT_BODY_NOT_DETECTED, emptyAssessment.hintMessage)
        assertTrue(emptyAssessment.visibleJoints.values.none { it })

        val lowVisLandmarks = createFullVisibleLandmarks(defaultVisibility = 0.1f)
        val lowVisAssessment = BodyFramingEvaluator.evaluate(lowVisLandmarks)
        assertEquals(FramingStatus.BODY_NOT_DETECTED, lowVisAssessment.status)
        assertEquals(BodyFramingEvaluator.HINT_BODY_NOT_DETECTED, lowVisAssessment.hintMessage)
        assertTrue(lowVisAssessment.visibleJoints.values.none { it })
    }
}
