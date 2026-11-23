package com.dancewithme.app.ui

import kotlin.math.max

/**
 * Utilitar geometric pur (fără dependențe Android) pentru maparea coordonatelor normalizate `[0..1]`
 * din spațiul imaginii sursă (`sourceWidth x sourceHeight`, ex: `480x640` în portret) în spațiul
 * `Canvas`-ului de pe ecran (`viewWidth x viewHeight`), replicând exact transformarea
 * `PreviewView.ScaleType.FILL_CENTER` din CameraX (scalare uniformă cu decupare centrată).
 */
object CoordinateMapper {

    /**
     * Mapează un punct normalizat `(normX, normY)` din imaginea camerei în coordonate pixel `(x, y)` pe ecran
     * conform strategiei `FILL_CENTER`.
     */
    fun mapFillCenter(
        normX: Float,
        normY: Float,
        sourceWidth: Int,
        sourceHeight: Int,
        viewWidth: Float,
        viewHeight: Float
    ): Pair<Float, Float> {
        if (sourceWidth <= 0 || sourceHeight <= 0 || viewWidth <= 0f || viewHeight <= 0f) {
            return (normX * viewWidth) to (normY * viewHeight)
        }

        val scale = max(
            viewWidth / sourceWidth.toFloat(),
            viewHeight / sourceHeight.toFloat()
        )
        val scaledWidth = sourceWidth * scale
        val scaledHeight = sourceHeight * scale
        val offsetX = (viewWidth - scaledWidth) * 0.5f
        val offsetY = (viewHeight - scaledHeight) * 0.5f

        val mappedX = (normX * scaledWidth) + offsetX
        val mappedY = (normY * scaledHeight) + offsetY
        return mappedX to mappedY
    }
}
