package com.dancewithme.app.data

import kotlinx.serialization.Serializable

/**
 * Reprezintă un punct 3D extras de MediaPipe Pose Landmarker.
 *
 * @property x Coordonata X (normalizată [0, 1] sau în metri pentru world landmarks).
 * @property y Coordonata Y (normalizată [0, 1] sau în metri pentru world landmarks).
 * @property z Coordonata Z (adâncimea relativă la centrul șoldurilor).
 * @property visibility Probabilitatea [0, 1] ca punctul să fie vizibil și neobturat în cadru.
 */
@Serializable
data class Point3D(
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float = 1.0f
)
