package com.dancewithme.app.engine

import com.dancewithme.app.data.Point3D

/**
 * Filtru de netezire temporală (Exponential Moving Average - EMA) pentru reperele 3D MediaPipe.
 *
 * Reduce tremuratul (jitter-ul) coordonatelor între cadre succesive la 30 FPS pe un dispozitiv real.
 *
 * Formula EMA pentru fiecare coordonată:
 *   S_t = alpha * X_t + (1 - alpha) * S_{t-1}
 *
 * @property alpha Factorul de netezire în intervalul (0.0, 1.0]. O valoare de 0.4f oferă un echilibru
 *                 optim între eliminarea zgomotului de înaltă frecvență și latență minimă.
 */
class LandmarkSmoother(
    private val alpha: Float = DEFAULT_ALPHA
) {
    init {
        require(alpha in 0.01f..1.0f) { "Factorul alpha trebuie să fie în intervalul [0.01, 1.0]." }
    }

    private val lock = Any()
    private var previousLandmarks: List<Point3D>? = null

    /**
     * Netezește o listă de [Point3D] față de cadrul anterior.
     * Dacă este primul cadru (sau după [reset]), returnează lista curentă și o salvează ca stare inițială.
     */
    fun smooth(currentLandmarks: List<Point3D>): List<Point3D> = synchronized(lock) {
        if (currentLandmarks.isEmpty()) return currentLandmarks

        val prev = previousLandmarks
        if (prev == null || prev.size != currentLandmarks.size) {
            previousLandmarks = currentLandmarks
            return currentLandmarks
        }

        val oneMinusAlpha = 1.0f - alpha
        val smoothed = ArrayList<Point3D>(currentLandmarks.size)

        for (i in currentLandmarks.indices) {
            val cur = currentLandmarks[i]
            val old = prev[i]
            smoothed.add(
                Point3D(
                    x = alpha * cur.x + oneMinusAlpha * old.x,
                    y = alpha * cur.y + oneMinusAlpha * old.y,
                    z = alpha * cur.z + oneMinusAlpha * old.z,
                    visibility = cur.visibility
                )
            )
        }

        previousLandmarks = smoothed
        return smoothed
    }

    /**
     * Resetează istoricul filtrului (apelat la începerea sau resetarea unui antrenament).
     */
    fun reset() = synchronized(lock) {
        previousLandmarks = null
    }

    companion object {
        const val DEFAULT_ALPHA = 0.4f
    }
}
