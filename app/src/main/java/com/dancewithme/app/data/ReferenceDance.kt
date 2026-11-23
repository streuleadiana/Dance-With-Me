package com.dancewithme.app.data

import kotlinx.serialization.Serializable

/**
 * Modelul unui dans de referință (deserializat din JSON).
 *
 * @property id Identificatorul unic al dansului.
 * @property title Titlul afișat în meniul principal.
 * @property durationSec Durata exercițiului în secunde (standard: 20 secunde).
 * @property frames Lista secvențială de [AngleVector] (ex: 600 cadre pentru 20s @ 30 FPS).
 * @property description Scurtă descriere în limba română a pașilor și posturii coregrafiei.
 * @property videoAssetName Numele opțional al fișierului video demonstrativ din `assets/videos/` (ex: `"gangnam_style.mp4"`).
 * @property ghostFrames Lista opțională cu cele 600 de cadre ale scheletului de referință (33 puncte normalizate [0..1])
 *                       folosită în `TutorialScreen` și ca "Ghost Skeleton" live în `DrillScreen`.
 */
@Serializable
data class ReferenceDance(
    val id: String,
    val title: String,
    val durationSec: Int = 20,
    val bpm: Int = 90,
    val frames: List<AngleVector>,
    val description: String = "",
    val videoAssetName: String? = null,
    val ghostFrames: List<List<Point3D>> = emptyList()
)
