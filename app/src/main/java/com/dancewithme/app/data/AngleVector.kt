package com.dancewithme.app.data

import kotlinx.serialization.Serializable

/**
 * Vectorul celor 8 unghiuri articulare principale (în grade, 0°..180°),
 * extras dintr-un [PoseFrame] folosind produsul scalar 3D.
 *
 * Câmpurile `umarStang` și `umarDrept` au valori default `0f` pentru compatibilitate
 * retroactivă cu fișierele JSON generate în versiunile cu 6 unghiuri.
 */
@Serializable
data class AngleVector(
    val genunchiStang: Float,
    val genunchiDrept: Float,
    val soldStang: Float,
    val soldDrept: Float,
    val cotStang: Float,
    val cotDrept: Float,
    val umarStang: Float = 0f,
    val umarDrept: Float = 0f
) {
    /**
     * Returnează valorile unghiurilor sub formă de Array pentru procesare rapidă în DTW.
     */
    fun toFloatArray(): FloatArray = floatArrayOf(
        genunchiStang,
        genunchiDrept,
        soldStang,
        soldDrept,
        cotStang,
        cotDrept,
        umarStang,
        umarDrept
    )

    /**
     * Returnează o mapare între numele articulației și valoarea unghiului (în grade).
     */
    fun toJointMap(): Map<String, Float> = mapOf(
        JOINT_LEFT_KNEE to genunchiStang,
        JOINT_RIGHT_KNEE to genunchiDrept,
        JOINT_LEFT_HIP to soldStang,
        JOINT_RIGHT_HIP to soldDrept,
        JOINT_LEFT_ELBOW to cotStang,
        JOINT_RIGHT_ELBOW to cotDrept,
        JOINT_LEFT_SHOULDER to umarStang,
        JOINT_RIGHT_SHOULDER to umarDrept
    )

    companion object {
        const val JOINT_COUNT = 8

        const val JOINT_LEFT_KNEE = "Genunchi Stâng"
        const val JOINT_RIGHT_KNEE = "Genunchi Drept"
        const val JOINT_LEFT_HIP = "Șold Stâng"
        const val JOINT_RIGHT_HIP = "Șold Drept"
        const val JOINT_LEFT_ELBOW = "Cot Stâng"
        const val JOINT_RIGHT_ELBOW = "Cot Drept"
        const val JOINT_LEFT_SHOULDER = "Umăr Stâng"
        const val JOINT_RIGHT_SHOULDER = "Umăr Drept"

        val JOINT_NAMES = listOf(
            JOINT_LEFT_KNEE,
            JOINT_RIGHT_KNEE,
            JOINT_LEFT_HIP,
            JOINT_RIGHT_HIP,
            JOINT_LEFT_ELBOW,
            JOINT_RIGHT_ELBOW,
            JOINT_LEFT_SHOULDER,
            JOINT_RIGHT_SHOULDER
        )
    }
}
