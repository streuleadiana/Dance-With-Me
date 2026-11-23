package com.dancewithme.app.data

import kotlinx.serialization.Serializable

/**
 * Rezultatul evaluării unei sesiuni de dans (returnat de motorul DTW).
 *
 * @property totalScore Scorul general [0..100].
 * @property formScore Scorul pentru corectitudinea unghiurilor articulare [0..100].
 * @property timingScore Scorul pentru sincronizarea temporală (cât de aproape de diagonală a fost calea DTW) [0..100].
 * @property jointErrors Eroarea medie absolută (în grade) pentru fiecare articulație.
 * @property signedJointErrors Eroarea medie cu semn (`userAngle - refAngle`, în grade) pentru fiecare articulație:
 *           - valoare pozitivă (+): unghi prea mare (membrul a fost ținut prea întins / deschis);
 *           - valoare negativă (-): unghi prea mic (membrul a fost ținut prea îndoit / strâns).
 */
@Serializable
data class DrillResult(
    val totalScore: Float,
    val formScore: Float,
    val timingScore: Float,
    val jointErrors: Map<String, Float>,
    val signedJointErrors: Map<String, Float> = emptyMap()
)
