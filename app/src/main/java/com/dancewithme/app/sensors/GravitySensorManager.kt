package com.dancewithme.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.acos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Manager pentru citirea senzorului de gravitație (`TYPE_GRAVITY`, cu fallback la `TYPE_ACCELEROMETER`)
 * pentru ecranul de calibrare (`CalibrationScreen`).
 *
 * Calculează unghiul de înclinare (Pitch/Tilt) al telefonului față de planul orizontal în grade [0°..180°]:
 * - ~0°: telefonul stă culcat pe masă cu ecranul în sus.
 * - ~90°: telefonul stă perfect vertical (poziția ideală pe suport pentru filmarea dansatorului).
 */
class GravitySensorManager(
    context: Context,
    private val onTiltChanged: (Float) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /**
     * Indică dacă dispozitivul are un senzor fizic de gravitație/accelerometru disponibil.
     */
    val hasSensor: Boolean = gravitySensor != null

    /**
     * Înregistrează listener-ul pentru citirea în timp real a înclinării telefonului.
     */
    fun startListening() {
        gravitySensor?.let { sensor ->
            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_UI
            )
        }
    }

    /**
     * Dezînregistrează listener-ul pentru a economisi bateria când utilizatorul părăsește `CalibrationScreen`.
     */
    fun stopListening() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val values = event?.values ?: return
        if (values.size < 3) return

        val gx = values[0]
        val gy = values[1]
        val gz = values[2]

        val normG = sqrt((gx * gx) + (gy * gy) + (gz * gz))
        if (normG < 1e-3f) return

        // Când telefonul stă vertical în portret sau landscape:
        // gz (axa perpendiculară pe ecran) tinde spre 0, iar arccos(|gz| / normG) tinde spre 90°.
        // Folosim acos(gz / normG) astfel încât:
        // - Cu fața în sus pe masă (gz ≈ +9.81) -> ~0°
        // - Perfect vertical (gz ≈ 0) -> ~90°
        // - Înclinat pe spate/față -> variază continuu în jurul a 90°
        val cosTilt = (gz / normG).coerceIn(-1.0f, 1.0f)
        val tiltRadians = acos(cosTilt)
        val tiltDegrees = Math.toDegrees(tiltRadians.toDouble()).toFloat()

        // Rotunjim la o zecimală pentru stabilitate vizuală în UI
        val smoothedTilt = (tiltDegrees * 10f).roundToInt() / 10f
        onTiltChanged(smoothedTilt)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
