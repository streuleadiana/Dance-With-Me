package com.dancewithme.app.audio

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Generator audio ușor bazat pe [ToneGenerator] (fără fișiere MP3 externe) pentru:
 * 1. Semnale sonore la numărătoarea inversă 3-2-1 și semnalul de start ("GO").
 * 2. Metronom ritmic sincronizat cu BPM-ul fiecărui dans pe durata celor 20 de secunde de antrenament.
 *
 * Toate apelurile către [ToneGenerator] sunt protejate cu `try/catch` pentru a preveni excepții
 * pe emulatoare sau dispozitive fără ieșire audio activă.
 */
class RhythmMetronomeHelper(
    volume: Int = DEFAULT_VOLUME
) {
    private var toneGenerator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, volume.coerceIn(0, 100))
    } catch (_: Throwable) {
        null
    }

    /**
     * Redă un semnal sonor pentru numărătoarea inversă 3-2-1 sau pentru momentul începerii dansului.
     *
     * @param isGoSignal `false` pentru secundele 3, 2, 1 (`TONE_PROP_BEEP`, ~90ms);
     *                   `true` pentru startul efectiv (`TONE_PROP_ACK`, ~180ms).
     */
    fun playCountdownBeep(isGoSignal: Boolean = false) {
        try {
            val generator = toneGenerator ?: return
            if (isGoSignal) {
                generator.startTone(ToneGenerator.TONE_PROP_ACK, GO_SIGNAL_DURATION_MS)
            } else {
                generator.startTone(ToneGenerator.TONE_PROP_BEEP, COUNTDOWN_BEEP_DURATION_MS)
            }
        } catch (_: Throwable) {
            // Ignorăm eventualele erori hardware de pe emulatoare fără suport audio
        }
    }

    /**
     * Redă un puls ritmic de metronom.
     *
     * @param isAccent `true` pentru primul timp din măsura de 4 timpi (`TONE_PROP_BEEP`, ~45ms);
     *                 `false` pentru timpii 2, 3 și 4 (`TONE_PROP_PROMPT`, ~35ms).
     */
    fun playMetronomeBeat(isAccent: Boolean) {
        try {
            val generator = toneGenerator ?: return
            if (isAccent) {
                generator.startTone(ToneGenerator.TONE_PROP_BEEP, ACCENT_BEAT_DURATION_MS)
            } else {
                generator.startTone(ToneGenerator.TONE_PROP_PROMPT, REGULAR_BEAT_DURATION_MS)
            }
        } catch (_: Throwable) {
            // Ignorăm eventualele erori hardware de pe emulatoare fără suport audio
        }
    }

    /**
     * Eliberează resursele native ale [ToneGenerator].
     */
    fun release() {
        try {
            toneGenerator?.release()
        } catch (_: Throwable) {
            // Ignorăm erorile la eliberare
        } finally {
            toneGenerator = null
        }
    }

    companion object {
        const val DEFAULT_VOLUME = 65
        const val MIN_BPM = 40
        const val MAX_BPM = 220

        private const val COUNTDOWN_BEEP_DURATION_MS = 90
        private const val GO_SIGNAL_DURATION_MS = 180
        private const val ACCENT_BEAT_DURATION_MS = 45
        private const val REGULAR_BEAT_DURATION_MS = 35

        /**
         * Convertește tempo-ul din BPM (bătăi pe minut) în intervalul în milisecunde dintre două bătăi,
         * limitând BPM-ul în intervalul sigur `[40, 220]`.
         */
        fun bpmToIntervalMs(bpm: Int): Long {
            val clampedBpm = bpm.coerceIn(MIN_BPM, MAX_BPM)
            return 60_000L / clampedBpm
        }
    }
}
