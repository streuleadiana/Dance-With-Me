package com.dancewithme.app.data

import kotlinx.serialization.Serializable

/**
 * Progresul local al utilizatorului pentru o coregrafie de referință ([ReferenceDance]).
 *
 * @property danceId ID-ul dansului (ex. `hora_simpla_01`, `gangnam_style_04`).
 * @property bestScore Recordul personal (cel mai mare scor total DTW obținut, în intervalul `[0..100]`).
 * @property attemptsCount Numărul total de antrenamente finalizate pentru acest dans.
 * @property lastScore Scorul obținut la cel mai recent antrenament.
 * @property lastPlayedTimestampMs Momentul de timp (epoch ms) al ultimei încercări.
 */
@Serializable
data class DanceProgress(
    val danceId: String,
    val bestScore: Float,
    val attemptsCount: Int,
    val lastScore: Float,
    val lastPlayedTimestampMs: Long
)
