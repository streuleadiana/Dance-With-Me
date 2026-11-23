package com.dancewithme.app.engine

import com.dancewithme.app.data.AngleVector
import com.dancewithme.app.data.DrillResult
import com.dancewithme.app.data.ReferenceDance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Motor matematic DTW (Dynamic Time Warping) cu constrângere de bandă Sakoe-Chiba.
 * Aliniază secvența de [AngleVector] (8 unghiuri articulare) a utilizatorului cu secvența de referință
 * și calculează scorurile detaliate ([DrillResult]), inclusiv erorile absolute și erorile cu semn
 * pe fiecare articulație.
 *
 * @property sakoeChibaWindow Raza benzii Sakoe-Chiba în cadre (implicit 45 cadre = ±1.5s @ 30 FPS).
 */
class DtwEngine(
    private val sakoeChibaWindow: Int = DEFAULT_SAKOE_CHIBA_BAND
) {

    /**
     * Compară secvența de cadre a utilizatorului cu un [ReferenceDance] folosind DTW cu banda Sakoe-Chiba.
     */
    fun compare(
        userFrames: List<AngleVector>,
        reference: ReferenceDance
    ): DrillResult = evaluateDrill(
        userFrames = userFrames,
        referenceFrames = reference.frames,
        windowSize = sakoeChibaWindow
    )

    companion object {
        /**
         * Lățimea implicită a benzii Sakoe-Chiba (în cadre).
         * La 30 FPS, 45 de cadre corespund unei toleranțe temporale de ±1.5 secunde.
         */
        const val DEFAULT_SAKOE_CHIBA_BAND = 45

        /**
         * Eroarea unghiulară medie maximă (în grade) la care scorul de formă devine 0%.
         */
        private const val MAX_TOLERATED_ANGLE_ERROR_DEG = 60.0f

        /**
         * Evaluează execuția utilizatorului față de dansul de referință folosind DTW cu banda Sakoe-Chiba.
         *
         * @param userFrames Lista de [AngleVector] capturată de la utilizator (~600 cadre).
         * @param referenceFrames Lista de [AngleVector] din dansul de referință (~600 cadre).
         * @param windowSize Raza benzii Sakoe-Chiba (număr de cadre).
         * @return [DrillResult] cu scorul total, scorul de formă, scorul de sincronizare,
         *         erorile absolute ([DrillResult.jointErrors]) și erorile cu semn ([DrillResult.signedJointErrors]).
         */
        fun evaluateDrill(
            userFrames: List<AngleVector>,
            referenceFrames: List<AngleVector>,
            windowSize: Int = DEFAULT_SAKOE_CHIBA_BAND
        ): DrillResult {
            if (userFrames.isEmpty() || referenceFrames.isEmpty()) {
                return emptyDrillResult()
            }

            val n = userFrames.size
            val m = referenceFrames.size

            // Banda efectivă trebuie să fie cel puțin diferența de lungime |n - m| pentru ca destinația (n, m) să fie accesibilă
            val effectiveWindow = max(windowSize, abs(n - m))

            // Matricea de cost cumulativ DTW: (n + 1) x (m + 1)
            val dtw = Array(n + 1) { FloatArray(m + 1) { Float.POSITIVE_INFINITY } }
            dtw[0][0] = 0f

            for (i in 1..n) {
                // Proiecția diagonalei când n != m: j_center = i * m / n
                val centerJ = ((i.toLong() * m) / n).toInt()
                val jStart = max(1, centerJ - effectiveWindow)
                val jEnd = min(m, centerJ + effectiveWindow)

                val userVec = userFrames[i - 1]
                for (j in jStart..jEnd) {
                    val refVec = referenceFrames[j - 1]
                    val frameCost = frameDistance(userVec, refVec)

                    val minPrev = min(
                        dtw[i - 1][j],
                        min(dtw[i][j - 1], dtw[i - 1][j - 1])
                    )

                    if (minPrev != Float.POSITIVE_INFINITY) {
                        dtw[i][j] = frameCost + minPrev
                    }
                }
            }

            // Backtracking pe calea optimă de la (n, m) la (1, 1) pentru a extrage:
            // 1. Erorile absolute și cu semn pe fiecare dintre cele 8 articulații de-a lungul alinierii optime
            // 2. Abaterea temporală (timing error) față de diagonala ideală
            var i = n
            var j = m
            var pathLength = 0
            var totalTimingDeviation = 0f

            val jointErrorSums = FloatArray(AngleVector.JOINT_COUNT)
            val signedJointErrorSums = FloatArray(AngleVector.JOINT_COUNT)

            while (i > 0 && j > 0) {
                val u = userFrames[i - 1]
                val r = referenceFrames[j - 1]

                val diffLeftKnee = u.genunchiStang - r.genunchiStang
                val diffRightKnee = u.genunchiDrept - r.genunchiDrept
                val diffLeftHip = u.soldStang - r.soldStang
                val diffRightHip = u.soldDrept - r.soldDrept
                val diffLeftElbow = u.cotStang - r.cotStang
                val diffRightElbow = u.cotDrept - r.cotDrept
                val diffLeftShoulder = u.umarStang - r.umarStang
                val diffRightShoulder = u.umarDrept - r.umarDrept

                jointErrorSums[0] += abs(diffLeftKnee)
                jointErrorSums[1] += abs(diffRightKnee)
                jointErrorSums[2] += abs(diffLeftHip)
                jointErrorSums[3] += abs(diffRightHip)
                jointErrorSums[4] += abs(diffLeftElbow)
                jointErrorSums[5] += abs(diffRightElbow)
                jointErrorSums[6] += abs(diffLeftShoulder)
                jointErrorSums[7] += abs(diffRightShoulder)

                signedJointErrorSums[0] += diffLeftKnee
                signedJointErrorSums[1] += diffRightKnee
                signedJointErrorSums[2] += diffLeftHip
                signedJointErrorSums[3] += diffRightHip
                signedJointErrorSums[4] += diffLeftElbow
                signedJointErrorSums[5] += diffRightElbow
                signedJointErrorSums[6] += diffLeftShoulder
                signedJointErrorSums[7] += diffRightShoulder

                // Poziția ideală pe diagonală pentru pasul i
                val idealJ = (i.toFloat() * m.toFloat()) / n.toFloat()
                totalTimingDeviation += abs(j - idealJ)
                pathLength++

                if (i == 1 && j == 1) break

                val diag = if (i > 1 && j > 1) dtw[i - 1][j - 1] else Float.POSITIVE_INFINITY
                val up = if (i > 1) dtw[i - 1][j] else Float.POSITIVE_INFINITY
                val left = if (j > 1) dtw[i][j - 1] else Float.POSITIVE_INFINITY

                when {
                    diag <= up && diag <= left -> {
                        i--
                        j--
                    }
                    up <= left -> i--
                    else -> j--
                }
            }

            val steps = pathLength.coerceAtLeast(1)

            // Eroarea medie absolută și cu semn (în grade) pe fiecare articulație
            val jointErrorsMap = linkedMapOf<String, Float>()
            val signedJointErrorsMap = linkedMapOf<String, Float>()
            var meanAngleErrorAllJoints = 0f

            for (k in 0 until AngleVector.JOINT_COUNT) {
                val avgJointErr = jointErrorSums[k] / steps
                val avgSignedErr = signedJointErrorSums[k] / steps

                val roundedErr = (avgJointErr * 10f).roundToInt() / 10f
                val roundedSignedErr = (avgSignedErr * 10f).roundToInt() / 10f

                val jointName = AngleVector.JOINT_NAMES[k]
                jointErrorsMap[jointName] = roundedErr
                signedJointErrorsMap[jointName] = roundedSignedErr
                meanAngleErrorAllJoints += avgJointErr
            }
            meanAngleErrorAllJoints /= AngleVector.JOINT_COUNT

            // Scorul de Formă (0..100): 100% când eroarea e 0°, scade liniar până la MAX_TOLERATED_ANGLE_ERROR_DEG
            val formScore = (100f * (1f - (meanAngleErrorAllJoints / MAX_TOLERATED_ANGLE_ERROR_DEG)))
                .coerceIn(0f, 100f)

            // Scorul de Timing (0..100): măsoară cât de aproape de diagonală a rămas alinierea DTW
            val avgTimingDeviationFrames = totalTimingDeviation / steps
            val maxTimingReference = max(effectiveWindow.toFloat(), 15f)
            val timingScore = (100f * (1f - (avgTimingDeviationFrames / maxTimingReference)))
                .coerceIn(0f, 100f)

            // Scorul Total: medie ponderată (70% corectitudinea posturii / formei, 30% sincronizarea / ritmul)
            val totalScore = ((formScore * 0.7f) + (timingScore * 0.3f)).coerceIn(0f, 100f)

            return DrillResult(
                totalScore = (totalScore * 10f).roundToInt() / 10f,
                formScore = (formScore * 10f).roundToInt() / 10f,
                timingScore = (timingScore * 10f).roundToInt() / 10f,
                jointErrors = jointErrorsMap,
                signedJointErrors = signedJointErrorsMap
            )
        }

        /**
         * Calculează distanța medie absolută (L1 / Manhattan normalizată) în grade
         * între doi vectori de 8 unghiuri [AngleVector].
         */
        fun frameDistance(a: AngleVector, b: AngleVector): Float {
            val sumDiff = abs(a.genunchiStang - b.genunchiStang) +
                abs(a.genunchiDrept - b.genunchiDrept) +
                abs(a.soldStang - b.soldStang) +
                abs(a.soldDrept - b.soldDrept) +
                abs(a.cotStang - b.cotStang) +
                abs(a.cotDrept - b.cotDrept) +
                abs(a.umarStang - b.umarStang) +
                abs(a.umarDrept - b.umarDrept)
            return sumDiff / AngleVector.JOINT_COUNT
        }

        private fun emptyDrillResult(): DrillResult = DrillResult(
            totalScore = 0f,
            formScore = 0f,
            timingScore = 0f,
            jointErrors = AngleVector.JOINT_NAMES.associateWith { 0f },
            signedJointErrors = AngleVector.JOINT_NAMES.associateWith { 0f }
        )
    }
}
