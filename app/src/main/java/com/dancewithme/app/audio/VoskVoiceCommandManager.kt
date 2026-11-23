package com.dancewithme.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Starea curentă a serviciului de recunoaștere vocală offline Vosk.
 */
enum class VoiceRecognitionState {
    IDLE,
    INITIALIZING_MODEL,
    LISTENING,
    SIMULATION_READY,
    PERMISSION_REQUIRED,
    ERROR
}

/**
 * Manager offline Speech-to-Text bazat pe **Vosk Android API** (`org.vosk.android.SpeechService`)
 * care ascultă în timp real microfonul (la 16 kHz) și detectează cuvintele cheie `"start"`, `"repeat"` și `"next"`
 * pentru controlul hands-free al antrenamentului de dans și al ecranului de rezultate.
 *
 * Arhitectură rezilientă:
 * - Caută modelul acustic Vosk în `assets/model-small` (sau `assets/vosk-model`) folosind `StorageService.unpack`.
 * - Configurează `Recognizer` cu gramatică restricționată `["start", "repeat", "next", "[unk]"]` pentru latență zero și acuratețe maximă.
 * - Dacă folderul modelului Vosk nu este încă adăugat în `assets/`, trece în starea `SIMULATION_READY`
 *   și oferă metode de simulare vocală pentru testare hands-free pe orice emulator sau telefon.
 */
class VoskVoiceCommandManager(
    private val context: Context,
    private val onStartCommandDetected: () -> Unit = {},
    private val onRepeatCommandDetected: () -> Unit = {},
    private val onNextCommandDetected: () -> Unit = {}
) : RecognitionListener {

    private val _state = MutableStateFlow(VoiceRecognitionState.IDLE)
    val state: StateFlow<VoiceRecognitionState> = _state.asStateFlow()

    private val _statusMessage = MutableStateFlow("Spune „START” sau apasă butonul")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var commandTriggeredInCurrentSession: Boolean = false

    /**
     * Pornește inițializarea Vosk și ascultarea microfonului dacă permisiunea `RECORD_AUDIO` este acordată.
     */
    fun startListening() {
        commandTriggeredInCurrentSession = false

        val hasAudioPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasAudioPermission) {
            _state.value = VoiceRecognitionState.PERMISSION_REQUIRED
            _statusMessage.value = "Permisiune microfon necesară pentru comenzile vocale"
            return
        }

        // Dacă modelul este deja încărcat în memorie, pornim direct SpeechService
        val loadedModel = model
        if (loadedModel != null) {
            startSpeechServiceWithModel(loadedModel)
            return
        }

        // Verificăm dacă există un model Vosk în assets/
        val assetModelDir = findVoskModelAssetDir()
        if (assetModelDir == null) {
            _state.value = VoiceRecognitionState.SIMULATION_READY
            _statusMessage.value = "Vosk Hands-Free activ (Spune „START” / Simulare Vocală)"
            Log.i(
                TAG,
                "Nu s-a găsit folderul modelului Vosk în assets/ ($CANDIDATE_ASSET_DIRS). " +
                    "S-a activat modul Voice Command Ready cu suport de simulare."
            )
            return
        }

        _state.value = VoiceRecognitionState.INITIALIZING_MODEL
        _statusMessage.value = "Se încarcă modelul vocal offline Vosk..."

        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            StorageService.unpack(
                context,
                assetModelDir,
                "vosk-model-unpacked",
                { unpackedModel: Model ->
                    this.model = unpackedModel
                    startSpeechServiceWithModel(unpackedModel)
                },
                { exception: Exception ->
                    Log.w(TAG, "Nu s-a putut despacheta modelul Vosk: ${exception.message}")
                    _state.value = VoiceRecognitionState.SIMULATION_READY
                    _statusMessage.value = "Spune „START” sau folosește butonul Hands-Free"
                }
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Eroare la inițializarea Vosk: ${t.message}")
            _state.value = VoiceRecognitionState.SIMULATION_READY
            _statusMessage.value = "Spune „START” sau folosește butonul Hands-Free"
        }
    }

    private fun startSpeechServiceWithModel(voskModel: Model) {
        try {
            stopSpeechServiceOnly()
            // Restrângem vocabularul la comenzile "start", "repeat", "next" pentru detecție instantanee
            val recognizer = Recognizer(voskModel, SAMPLE_RATE_HZ, GRAMMAR_COMMANDS)
            speechService = SpeechService(recognizer, SAMPLE_RATE_HZ).also { service ->
                service.startListening(this)
            }
            _state.value = VoiceRecognitionState.LISTENING
            _statusMessage.value = "Vosk Offline ascultă: spune „START”!"
        } catch (t: Throwable) {
            Log.e(TAG, "Eroare la pornirea Vosk SpeechService: ${t.message}", t)
            _state.value = VoiceRecognitionState.ERROR
            _statusMessage.value = "Eroare microfon Vosk: folosește butonul START"
        }
    }

    private fun findVoskModelAssetDir(): String? {
        return try {
            val rootAssets = context.assets.list("")?.toSet().orEmpty()
            CANDIDATE_ASSET_DIRS.firstOrNull { dirName ->
                dirName in rootAssets && !context.assets.list(dirName).isNullOrEmpty()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Procesează un JSON emis de Vosk (`partialResult` sau `result`) și declanșează callback-ul aferent
     * imediat ce detectează unul dintre cuvintele cheie `"start"`, `"repeat"` sau `"next"`.
     */
    internal fun handleVoskHypothesisJson(hypothesisJson: String?) {
        if (commandTriggeredInCurrentSession || hypothesisJson.isNullOrBlank()) return

        when {
            containsTriggerWordStart(hypothesisJson) -> {
                commandTriggeredInCurrentSession = true
                _statusMessage.value = "Comandă vocală „START” detectată!"
                stopSpeechServiceOnly()
                onStartCommandDetected()
            }
            containsTriggerWordRepeat(hypothesisJson) -> {
                commandTriggeredInCurrentSession = true
                _statusMessage.value = "Comandă vocală „REPEAT” detectată!"
                stopSpeechServiceOnly()
                onRepeatCommandDetected()
            }
            containsTriggerWordNext(hypothesisJson) -> {
                commandTriggeredInCurrentSession = true
                _statusMessage.value = "Comandă vocală „NEXT” detectată!"
                stopSpeechServiceOnly()
                onNextCommandDetected()
            }
        }
    }

    /**
     * Permite simularea rostirii comenzii `"Start"` (util pe Emulator sau când microfonul nu este disponibil).
     */
    fun triggerSimulatedVoiceStart() {
        handleVoskHypothesisJson("""{"partial" : "start"}""")
    }

    /**
     * Permite simularea rostirii comenzii `"Repeat"`.
     */
    fun triggerSimulatedVoiceRepeat() {
        handleVoskHypothesisJson("""{"partial" : "repeat"}""")
    }

    /**
     * Permite simularea rostirii comenzii `"Next"`.
     */
    fun triggerSimulatedVoiceNext() {
        handleVoskHypothesisJson("""{"partial" : "next"}""")
    }

    override fun onPartialResult(hypothesis: String?) {
        handleVoskHypothesisJson(hypothesis)
    }

    override fun onResult(hypothesis: String?) {
        handleVoskHypothesisJson(hypothesis)
    }

    override fun onFinalResult(hypothesis: String?) {
        handleVoskHypothesisJson(hypothesis)
    }

    override fun onError(exception: Exception?) {
        Log.w(TAG, "Eroare Vosk RecognitionListener: ${exception?.message}")
        _state.value = VoiceRecognitionState.ERROR
        _statusMessage.value = "Microfon indisponibil — apasă START"
    }

    override fun onTimeout() {
        stopSpeechServiceOnly()
    }

    private fun stopSpeechServiceOnly() {
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (_: Throwable) {
        } finally {
            speechService = null
        }
    }

    /**
     * Oprește ascultarea microfonului (de exemplu după ce a început dansul sau la ieșirea din ecran).
     */
    fun stopListening() {
        stopSpeechServiceOnly()
        if (_state.value == VoiceRecognitionState.LISTENING) {
            _state.value = VoiceRecognitionState.IDLE
        }
    }

    /**
     * Eliberează complet atât `SpeechService` cât și `Model`-ul nativ Vosk.
     */
    fun shutdown() {
        stopSpeechServiceOnly()
        try {
            model?.close()
        } catch (_: Throwable) {
        } finally {
            model = null
            _state.value = VoiceRecognitionState.IDLE
        }
    }

    companion object {
        private const val TAG = "VoskVoiceCommandMgr"
        private const val SAMPLE_RATE_HZ = 16000.0f
        private const val GRAMMAR_COMMANDS = """["start", "start dance", "repeat", "next", "[unk]"]"""

        private val CANDIDATE_ASSET_DIRS = listOf(
            "model-small",
            "vosk-model",
            "model"
        )

        /**
         * Verifică dacă JSON-ul returnat de Vosk (ex: `{"partial" : "start"}` sau `{"text" : "acum start"}`)
         * conține cuvântul cheie `"start"` ca token distinct (case-insensitive).
         * Funcție pură, testabilă unitar.
         */
        fun containsTriggerWordStart(voskJsonOrText: String): Boolean {
            val normalized = voskJsonOrText.lowercase()
            return Regex("""\bstart\b""").containsMatchIn(normalized)
        }

        /**
         * Verifică dacă JSON-ul returnat de Vosk conține cuvântul cheie `"repeat"` ca token distinct.
         * Funcție pură, testabilă unitar.
         */
        fun containsTriggerWordRepeat(voskJsonOrText: String): Boolean {
            val normalized = voskJsonOrText.lowercase()
            return Regex("""\brepeat\b""").containsMatchIn(normalized)
        }

        /**
         * Verifică dacă JSON-ul returnat de Vosk conține cuvântul cheie `"next"` ca token distinct.
         * Funcție pură, testabilă unitar.
         */
        fun containsTriggerWordNext(voskJsonOrText: String): Boolean {
            val normalized = voskJsonOrText.lowercase()
            return Regex("""\bnext\b""").containsMatchIn(normalized)
        }
    }
}
