package com.dancewithme.app.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.dancewithme.app.data.AngleVector
import com.dancewithme.app.data.DrillResult
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Helper pentru oferirea feedback-ului vocal la finalul antrenamentului folosind
 * motorul nativ Android [TextToSpeech].
 *
 * Generează automat rezumatul vocal al scorului DTW și oferă o indicație acționabilă
 * pentru articulația cu cea mai mare abatere unghiulară, bazându-se pe semnul erorii
 * din [DrillResult.signedJointErrors] (prea întins vs. prea îndoit).
 *
 * Include sincronizare prin [UtteranceProgressListener] (`onFinished`) astfel încât
 * recunoașterea vocală Vosk de pe `ResultScreen` să pornească abia după ce TTS-ul a terminat de vorbit.
 */
class TtsFeedbackHelper(
    context: Context,
    private val preferredLocale: Locale = Locale.forLanguageTag("ro-RO")
) : TextToSpeech.OnInitListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val utteranceCallbacks = ConcurrentHashMap<String, () -> Unit>()

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isReady: Boolean = false
    private var initFailed: Boolean = false
    private var pendingSpeechText: String? = null
    private var pendingOnFinished: (() -> Unit)? = null

    override fun onInit(status: Int) {
        val engine = tts ?: return
        if (status == TextToSpeech.SUCCESS) {
            val langResult = engine.setLanguage(preferredLocale)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Limba $preferredLocale nu este disponibilă pe dispozitiv. Se folosește Locale.US.")
                engine.setLanguage(Locale.US)
            }
            engine.setSpeechRate(1.0f)
            engine.setPitch(1.0f)

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    notifyUtteranceFinished(utteranceId)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    notifyUtteranceFinished(utteranceId)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    notifyUtteranceFinished(utteranceId)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    notifyUtteranceFinished(utteranceId)
                }
            })

            isReady = true
            initFailed = false

            val textToSpeak = pendingSpeechText
            val callback = pendingOnFinished
            pendingSpeechText = null
            pendingOnFinished = null
            if (textToSpeak != null) {
                speakText(textToSpeak, callback ?: {})
            }
        } else {
            Log.e(TAG, "Inițializarea Android TextToSpeech a eșuat cu status=$status")
            initFailed = true
            isReady = false
            val callback = pendingOnFinished
            pendingSpeechText = null
            pendingOnFinished = null
            if (callback != null) {
                dispatchOnMainThread(callback)
            }
        }
    }

    private fun notifyUtteranceFinished(utteranceId: String?) {
        if (utteranceId == null) return
        val callback = utteranceCallbacks.remove(utteranceId) ?: return
        dispatchOnMainThread(callback)
    }

    private fun dispatchOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    /**
     * Construiește și redă vocal feedback-ul complet pentru un [DrillResult].
     *
     * @param result Rezultatul DTW al sesiunii.
     * @param onFinished Callback invocat pe firul principal (Main Thread) imediat ce rostirea s-a încheiat
     *                   sau dacă motorul TTS nu este disponibil.
     */
    fun speakDrillResult(
        result: DrillResult,
        onFinished: () -> Unit = {}
    ) {
        val feedbackMessage = buildFeedbackText(result)
        speakText(feedbackMessage, onFinished)
    }

    /**
     * Redă un text arbitrar prin motorul TTS. Dacă motorul încă se inițializează,
     * păstrează textul și callback-ul în coadă și le execută imediat ce `onInit` confirmă disponibilitatea.
     */
    fun speakText(
        text: String,
        onFinished: () -> Unit = {}
    ) {
        if (text.isBlank()) {
            dispatchOnMainThread(onFinished)
            return
        }

        val engine = tts
        if (initFailed || engine == null) {
            dispatchOnMainThread(onFinished)
            return
        }

        if (!isReady) {
            // Dacă exista un callback anterior în așteptare, îl eliberăm
            pendingOnFinished?.let { dispatchOnMainThread(it) }
            pendingSpeechText = text
            pendingOnFinished = onFinished
            return
        }

        val utteranceId = "dance_with_me_${UUID.randomUUID()}"
        utteranceCallbacks[utteranceId] = onFinished

        val speakStatus = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (speakStatus == TextToSpeech.ERROR) {
            utteranceCallbacks.remove(utteranceId)
            dispatchOnMainThread(onFinished)
        }
    }

    /**
     * Oprește redarea curentă și eliberează resursele native ale serviciului [TextToSpeech].
     */
    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (t: Throwable) {
            Log.w(TAG, "Eroare la închiderea TextToSpeech: ${t.message}")
        } finally {
            utteranceCallbacks.clear()
            pendingSpeechText = null
            pendingOnFinished = null
            tts = null
            isReady = false
        }
    }

    companion object {
        private const val TAG = "TtsFeedbackHelper"
        private const val ACTIONABLE_ERROR_THRESHOLD_DEG = 8.0f

        /**
         * Generează textul de feedback în limba română pe baza scorului DTW, a erorilor absolute
         * și a erorilor cu semn ([DrillResult.signedJointErrors]).
         * Această funcție este pură (fără dependențe Android) și poate fi testată unitar.
         */
        fun buildFeedbackText(result: DrillResult): String {
            val roundedTotal = result.totalScore.roundToInt()
            val roundedForm = result.formScore.roundToInt()
            val roundedTiming = result.timingScore.roundToInt()

            val worstJointEntry = result.jointErrors.maxByOrNull { it.value }

            val intro = when {
                roundedTotal >= 85 -> "Excelent! Ai obținut un scor total de $roundedTotal la sută."
                roundedTotal >= 65 -> "Foarte bine! Scorul tău total este $roundedTotal la sută."
                else -> "Antrenament finalizat cu un scor de $roundedTotal la sută."
            }

            val details = "Scorul pentru formă este $roundedForm la sută, iar sincronizarea $roundedTiming la sută."

            val jointAdvice = if (worstJointEntry != null && worstJointEntry.value >= ACTIONABLE_ERROR_THRESHOLD_DEG) {
                val jointName = worstJointEntry.key
                val errDeg = worstJointEntry.value.roundToInt()
                val signedError = result.signedJointErrors[jointName] ?: 0f
                val actionableHint = buildActionableJointHint(jointName, signedError)

                "Atenție la $jointName, unde ai avut o abatere medie de $errDeg grade. $actionableHint"
            } else {
                "Toate unghiurile articulare au fost foarte precise. Bravo!"
            }

            return "$intro $details $jointAdvice"
        }

        /**
         * Construiește sfatul biomecanic concret în funcție de articulație și semnul abaterii:
         * - `signedError > 0`: unghiul utilizatorului a fost mai mare decât referința (membrul prea întins).
         * - `signedError < 0`: unghiul utilizatorului a fost mai mic decât referința (membrul prea îndoit).
         */
        internal fun buildActionableJointHint(jointName: String, signedError: Float): String {
            if (kotlin.math.abs(signedError) < 1e-2f) {
                return "Menține unghiul mai constant pe ritmul coregrafiei."
            }
            val isTooExtended = signedError > 0f

            return when (jointName) {
                AngleVector.JOINT_LEFT_KNEE -> if (isTooExtended) {
                    "Îndoaie mai mult genunchiul stâng."
                } else {
                    "Întinde mai mult piciorul stâng."
                }

                AngleVector.JOINT_RIGHT_KNEE -> if (isTooExtended) {
                    "Îndoaie mai mult genunchiul drept."
                } else {
                    "Întinde mai mult piciorul drept."
                }

                AngleVector.JOINT_LEFT_ELBOW -> if (isTooExtended) {
                    "Îndoaie mai mult brațul stâng."
                } else {
                    "Întinde mai mult brațul stâng."
                }

                AngleVector.JOINT_RIGHT_ELBOW -> if (isTooExtended) {
                    "Îndoaie mai mult brațul drept."
                } else {
                    "Întinde mai mult brațul drept."
                }

                AngleVector.JOINT_LEFT_HIP -> if (isTooExtended) {
                    "Ridică mai sus piciorul din șoldul stâng."
                } else {
                    "Îndreaptă postura din șoldul stâng."
                }

                AngleVector.JOINT_RIGHT_HIP -> if (isTooExtended) {
                    "Ridică mai sus piciorul din șoldul drept."
                } else {
                    "Îndreaptă postura din șoldul drept."
                }

                AngleVector.JOINT_LEFT_SHOULDER -> if (isTooExtended) {
                    "Coboară puțin brațul stâng."
                } else {
                    "Ridică mai sus brațul stâng."
                }

                AngleVector.JOINT_RIGHT_SHOULDER -> if (isTooExtended) {
                    "Coboară puțin brațul drept."
                } else {
                    "Ridică mai sus brațul drept."
                }

                else -> if (isTooExtended) {
                    "Flexează mai mult articulația."
                } else {
                    "Extinde mai mult articulația."
                }
            }
        }
    }
}
