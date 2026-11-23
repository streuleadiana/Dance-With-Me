package com.dancewithme.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

/**
 * Manager pentru senzorul de proximitate (`Sensor.TYPE_PROXIMITY`), folosit ca declanșator
 * hands-free alternativ pentru pornirea antrenamentului (trecerea mâinii prin fața părții
 * superioare a telefonului în faza `READY`).
 *
 * Include un mecanism de debounce de 2 secunde ([DEBOUNCE_INTERVAL_MS]) pentru a preveni
 * declanșările multiple accidentale.
 */
class ProximitySensorManager(
    context: Context,
    private val onHandWaveDetected: () -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val proximitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    private var lastTriggerTimestampMs: Long = 0L

    /**
     * Indică dacă dispozitivul dispune de un senzor fizic de proximitate.
     */
    val hasSensor: Boolean = proximitySensor != null

    /**
     * Înregistrează listener-ul pentru senzorul de proximitate.
     */
    fun startListening() {
        proximitySensor?.let { sensor ->
            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_UI
            )
        }
    }

    /**
     * Dezînregistrează listener-ul când antrenamentul a început sau utilizatorul părăsește ecranul.
     */
    fun stopListening() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val sensor = event?.sensor ?: return
        if (sensor.type != Sensor.TYPE_PROXIMITY) return
        val values = event.values
        if (values == null || values.isEmpty()) return

        val distanceCm = values[0]
        val maxRange = sensor.maximumRange

        if (shouldTriggerWave(distanceCm = distanceCm, maxRange = maxRange)) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastTriggerTimestampMs >= DEBOUNCE_INTERVAL_MS) {
                lastTriggerTimestampMs = now
                onHandWaveDetected()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        const val DEBOUNCE_INTERVAL_MS = 2_000L
        const val NEAR_THRESHOLD_CM = 3.0f

        /**
         * Verifică dacă distanța raportată de senzor indică apropierea mâinii.
         */
        fun shouldTriggerWave(distanceCm: Float, maxRange: Float): Boolean {
            return distanceCm >= 0f && distanceCm < maxRange && distanceCm < NEAR_THRESHOLD_CM
        }
    }
}
