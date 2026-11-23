package com.dancewithme.app.engine

import com.dancewithme.app.data.AngleVector
import com.dancewithme.app.data.Point3D
import com.dancewithme.app.data.PoseFrame
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Motor matematic pentru calculul unghiurilor articulare în spațiul 3D și normalizarea coordonatelor.
 */
object JointAngleCalculator {

    private const val EPSILON = 1e-6f

    /**
     * Calculează unghiul în grade [0°..180°] format de trei puncte 3D (A - B - C),
     * unde [b] este vârful unghiului (articulația, ex: genunchiul).
     *
     * Formula matematică (produs scalar 3D):
     *   BA = A - B
     *   BC = C - B
     *   cos(θ) = (BA · BC) / (||BA|| * ||BC||)
     *   θ = arccos(clamp(cos(θ), -1.0, 1.0)) * (180 / π)
     */
    fun calculateAngle3D(a: Point3D, b: Point3D, c: Point3D): Float {
        // Vectorul BA = A - B
        val baX = a.x - b.x
        val baY = a.y - b.y
        val baZ = a.z - b.z

        // Vectorul BC = C - B
        val bcX = c.x - b.x
        val bcY = c.y - b.y
        val bcZ = c.z - b.z

        // Produsul scalar BA · BC
        val dotProduct = (baX * bcX) + (baY * bcY) + (baZ * bcZ)

        // Normele euclidiene ||BA|| și ||BC||
        val magnitudeBA = sqrt((baX * baX) + (baY * baY) + (baZ * baZ))
        val magnitudeBC = sqrt((bcX * bcX) + (bcY * bcY) + (bcZ * bcZ))

        val denominator = magnitudeBA * magnitudeBC
        if (denominator < EPSILON) {
            return 0f
        }

        // Clamp în [-1f, 1f] pentru a preveni NaN din erori de virgulă mobilă în acos()
        val cosTheta = (dotProduct / denominator).coerceIn(-1.0f, 1.0f)
        val radians = acos(cosTheta)

        return Math.toDegrees(radians.toDouble()).toFloat()
    }

    /**
     * Normalizează coordonatele 3D ale unui [PoseFrame] astfel încât scheletul să fie
     * invariant la translație (centrat în originea bazinului) și la scară (scalat după
     * distanța dintre umeri / trunchi).
     */
    fun normalizeLandmarks(landmarks: List<Point3D>): List<Point3D> {
        if (landmarks.size < PoseFrame.REQUIRED_LANDMARKS_COUNT) return landmarks

        val leftHip = landmarks[PoseFrame.LEFT_HIP]
        val rightHip = landmarks[PoseFrame.RIGHT_HIP]

        // Centrul bazinului (Pelvis center)
        val centerX = (leftHip.x + rightHip.x) * 0.5f
        val centerY = (leftHip.y + rightHip.y) * 0.5f
        val centerZ = (leftHip.z + rightHip.z) * 0.5f

        val leftShoulder = landmarks[PoseFrame.LEFT_SHOULDER]
        val rightShoulder = landmarks[PoseFrame.RIGHT_SHOULDER]
        val shoulderCenterX = (leftShoulder.x + rightShoulder.x) * 0.5f
        val shoulderCenterY = (leftShoulder.y + rightShoulder.y) * 0.5f
        val shoulderCenterZ = (leftShoulder.z + rightShoulder.z) * 0.5f

        // Lungimea trunchiului (distanța 3D dintre centrul bazinului și centrul umerilor)
        val dx = shoulderCenterX - centerX
        val dy = shoulderCenterY - centerY
        val dz = shoulderCenterZ - centerZ
        val torsoScale = sqrt((dx * dx) + (dy * dy) + (dz * dz)).coerceAtLeast(EPSILON)

        return landmarks.map { point ->
            Point3D(
                x = (point.x - centerX) / torsoScale,
                y = (point.y - centerY) / torsoScale,
                z = (point.z - centerZ) / torsoScale,
                visibility = point.visibility
            )
        }
    }

    /**
     * Extrage [AngleVector] (cele 8 unghiuri articulare cheie) dintr-un [PoseFrame].
     *
     * Articulațiile calculate:
     * - Genunchi Stâng: Șold Stâng (23) -> Genunchi Stâng (25) -> Gleznă Stângă (27)
     * - Genunchi Drept: Șold Drept (24) -> Genunchi Drept (26) -> Gleznă Dreaptă (28)
     * - Șold Stâng:     Umăr Stâng (11) -> Șold Stâng (23)     -> Genunchi Stâng (25)
     * - Șold Drept:     Umăr Drept (12) -> Șold Drept (24)     -> Genunchi Drept (26)
     * - Cot Stâng:      Umăr Stâng (11) -> Cot Stâng (13)      -> Încheietură Stângă (15)
     * - Cot Drept:      Umăr Drept (12) -> Cot Drept (14)      -> Încheietură Dreaptă (16)
     * - Umăr Stâng:     Șold Stâng (23) -> Umăr Stâng (11)     -> Cot Stâng (13)
     * - Umăr Drept:     Șold Drept (24) -> Umăr Drept (12)     -> Cot Drept (14)
     */
    fun extractAngleVector(poseFrame: PoseFrame): AngleVector {
        val pts = poseFrame.landmarks
        require(pts.size >= PoseFrame.REQUIRED_LANDMARKS_COUNT) {
            "PoseFrame trebuie să conțină cel puțin ${PoseFrame.REQUIRED_LANDMARKS_COUNT} repere, dar are ${pts.size}."
        }

        val genunchiStang = calculateAngle3D(
            a = pts[PoseFrame.LEFT_HIP],
            b = pts[PoseFrame.LEFT_KNEE],
            c = pts[PoseFrame.LEFT_ANKLE]
        )

        val genunchiDrept = calculateAngle3D(
            a = pts[PoseFrame.RIGHT_HIP],
            b = pts[PoseFrame.RIGHT_KNEE],
            c = pts[PoseFrame.RIGHT_ANKLE]
        )

        val soldStang = calculateAngle3D(
            a = pts[PoseFrame.LEFT_SHOULDER],
            b = pts[PoseFrame.LEFT_HIP],
            c = pts[PoseFrame.LEFT_KNEE]
        )

        val soldDrept = calculateAngle3D(
            a = pts[PoseFrame.RIGHT_SHOULDER],
            b = pts[PoseFrame.RIGHT_HIP],
            c = pts[PoseFrame.RIGHT_KNEE]
        )

        val cotStang = calculateAngle3D(
            a = pts[PoseFrame.LEFT_SHOULDER],
            b = pts[PoseFrame.LEFT_ELBOW],
            c = pts[PoseFrame.LEFT_WRIST]
        )

        val cotDrept = calculateAngle3D(
            a = pts[PoseFrame.RIGHT_SHOULDER],
            b = pts[PoseFrame.RIGHT_ELBOW],
            c = pts[PoseFrame.RIGHT_WRIST]
        )

        val umarStang = calculateAngle3D(
            a = pts[PoseFrame.LEFT_HIP],
            b = pts[PoseFrame.LEFT_SHOULDER],
            c = pts[PoseFrame.LEFT_ELBOW]
        )

        val umarDrept = calculateAngle3D(
            a = pts[PoseFrame.RIGHT_HIP],
            b = pts[PoseFrame.RIGHT_SHOULDER],
            c = pts[PoseFrame.RIGHT_ELBOW]
        )

        return AngleVector(
            genunchiStang = genunchiStang,
            genunchiDrept = genunchiDrept,
            soldStang = soldStang,
            soldDrept = soldDrept,
            cotStang = cotStang,
            cotDrept = cotDrept,
            umarStang = umarStang,
            umarDrept = umarDrept
        )
    }
}
