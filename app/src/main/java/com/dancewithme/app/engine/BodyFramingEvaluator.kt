package com.dancewithme.app.engine

import com.dancewithme.app.data.AngleVector
import com.dancewithme.app.data.Point3D
import com.dancewithme.app.data.PoseFrame

/**
 * Starea de încadrare a corpului utilizatorului în câmpul vizual al camerei.
 */
enum class FramingStatus {
    /** Întregul corp (umeri, brațe, șolduri, genunchi, glezne) este vizibil în cadru. */
    OK,

    /** Nu s-a detectat nicio siluetă sau trunchiul nu este vizibil în fața camerei. */
    BODY_NOT_DETECTED,

    /** Partea superioară (umerii sau coatele) iese din cadru sau este obturată. */
    UPPER_BODY_NOT_VISIBLE,

    /** Partea inferioară (genunchii sau gleznele) nu este vizibilă (utilizatorul stă prea aproape). */
    LEGS_NOT_VISIBLE
}

/**
 * Rezultatul evaluării vizibilității corpului pentru un cadru [PoseFrame].
 *
 * @property status Starea generală a încadrării în cameră ([FramingStatus]).
 * @property hintMessage Mesajul de ghidare în limba română (sau `null` dacă încadrarea este [FramingStatus.OK]).
 * @property visibleJoints Harta vizibilității pentru fiecare dintre cele 8 articulații din [AngleVector.JOINT_NAMES].
 */
data class FramingAssessment(
    val status: FramingStatus,
    val hintMessage: String?,
    val visibleJoints: Map<String, Boolean>
)

/**
 * Evaluator pur (fără dependențe Android) care verifică scorul `visibility` al reperelor MediaPipe
 * pentru a ghida utilizatorul să se încadreze complet în cameră și pentru a marca în gri
 * membrele obturate (occluded) în loc să le penalizeze vizual cu roșu.
 */
object BodyFramingEvaluator {

    const val MIN_VISIBILITY_THRESHOLD = 0.55f

    const val HINT_BODY_NOT_DETECTED =
        "Așază-te în fața camerei astfel încât să fii vizibil complet."
    const val HINT_UPPER_BODY_NOT_VISIBLE =
        "Încadrează-ți umerii și brațele în cameră!"
    const val HINT_LEGS_NOT_VISIBLE =
        "Fă un pas în spate — nu ți se văd picioarele!"

    /**
     * Evaluează lista de 33 de repere [Point3D] și returnează [FramingAssessment].
     */
    fun evaluate(landmarks: List<Point3D>): FramingAssessment {
        if (landmarks.size < PoseFrame.REQUIRED_LANDMARKS_COUNT) {
            return FramingAssessment(
                status = FramingStatus.BODY_NOT_DETECTED,
                hintMessage = HINT_BODY_NOT_DETECTED,
                visibleJoints = AngleVector.JOINT_NAMES.associateWith { false }
            )
        }

        val visibleJoints = mapOf(
            AngleVector.JOINT_LEFT_KNEE to areAllVisible(
                landmarks,
                PoseFrame.LEFT_HIP,
                PoseFrame.LEFT_KNEE,
                PoseFrame.LEFT_ANKLE
            ),
            AngleVector.JOINT_RIGHT_KNEE to areAllVisible(
                landmarks,
                PoseFrame.RIGHT_HIP,
                PoseFrame.RIGHT_KNEE,
                PoseFrame.RIGHT_ANKLE
            ),
            AngleVector.JOINT_LEFT_HIP to areAllVisible(
                landmarks,
                PoseFrame.LEFT_SHOULDER,
                PoseFrame.LEFT_HIP,
                PoseFrame.LEFT_KNEE
            ),
            AngleVector.JOINT_RIGHT_HIP to areAllVisible(
                landmarks,
                PoseFrame.RIGHT_SHOULDER,
                PoseFrame.RIGHT_HIP,
                PoseFrame.RIGHT_KNEE
            ),
            AngleVector.JOINT_LEFT_ELBOW to areAllVisible(
                landmarks,
                PoseFrame.LEFT_SHOULDER,
                PoseFrame.LEFT_ELBOW,
                PoseFrame.LEFT_WRIST
            ),
            AngleVector.JOINT_RIGHT_ELBOW to areAllVisible(
                landmarks,
                PoseFrame.RIGHT_SHOULDER,
                PoseFrame.RIGHT_ELBOW,
                PoseFrame.RIGHT_WRIST
            ),
            AngleVector.JOINT_LEFT_SHOULDER to areAllVisible(
                landmarks,
                PoseFrame.LEFT_HIP,
                PoseFrame.LEFT_SHOULDER,
                PoseFrame.LEFT_ELBOW
            ),
            AngleVector.JOINT_RIGHT_SHOULDER to areAllVisible(
                landmarks,
                PoseFrame.RIGHT_HIP,
                PoseFrame.RIGHT_SHOULDER,
                PoseFrame.RIGHT_ELBOW
            )
        )

        val shouldersVisible = areAllVisible(landmarks, PoseFrame.LEFT_SHOULDER, PoseFrame.RIGHT_SHOULDER)
        val elbowsVisible = areAllVisible(landmarks, PoseFrame.LEFT_ELBOW, PoseFrame.RIGHT_ELBOW)
        val hipsVisible = areAllVisible(landmarks, PoseFrame.LEFT_HIP, PoseFrame.RIGHT_HIP)
        val kneesVisible = areAllVisible(landmarks, PoseFrame.LEFT_KNEE, PoseFrame.RIGHT_KNEE)
        val anklesVisible = areAllVisible(landmarks, PoseFrame.LEFT_ANKLE, PoseFrame.RIGHT_ANKLE)

        val torsoMeanVisibility = (
            landmarks[PoseFrame.LEFT_SHOULDER].visibility +
                landmarks[PoseFrame.RIGHT_SHOULDER].visibility +
                landmarks[PoseFrame.LEFT_HIP].visibility +
                landmarks[PoseFrame.RIGHT_HIP].visibility
            ) * 0.25f

        return when {
            (!shouldersVisible && !hipsVisible) || (torsoMeanVisibility < MIN_VISIBILITY_THRESHOLD && !hipsVisible) -> {
                FramingAssessment(
                    status = FramingStatus.BODY_NOT_DETECTED,
                    hintMessage = HINT_BODY_NOT_DETECTED,
                    visibleJoints = visibleJoints
                )
            }

            !shouldersVisible || !elbowsVisible -> {
                FramingAssessment(
                    status = FramingStatus.UPPER_BODY_NOT_VISIBLE,
                    hintMessage = HINT_UPPER_BODY_NOT_VISIBLE,
                    visibleJoints = visibleJoints
                )
            }

            !hipsVisible || !kneesVisible || !anklesVisible -> {
                FramingAssessment(
                    status = FramingStatus.LEGS_NOT_VISIBLE,
                    hintMessage = HINT_LEGS_NOT_VISIBLE,
                    visibleJoints = visibleJoints
                )
            }

            else -> {
                FramingAssessment(
                    status = FramingStatus.OK,
                    hintMessage = null,
                    visibleJoints = visibleJoints
                )
            }
        }
    }

    private fun isPointVisible(landmarks: List<Point3D>, index: Int): Boolean {
        val point = landmarks.getOrNull(index) ?: return false
        return point.visibility >= MIN_VISIBILITY_THRESHOLD
    }

    private fun areAllVisible(landmarks: List<Point3D>, vararg indices: Int): Boolean {
        return indices.all { idx -> isPointVisible(landmarks, idx) }
    }
}
