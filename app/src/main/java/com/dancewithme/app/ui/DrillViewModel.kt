package com.dancewithme.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dancewithme.app.ai.PoseExtractionOutput
import com.dancewithme.app.data.AngleVector
import com.dancewithme.app.data.DanceRepository
import com.dancewithme.app.data.DefaultDanceRepository
import com.dancewithme.app.data.DrillResult
import com.dancewithme.app.data.InMemoryScoreHistoryRepository
import com.dancewithme.app.data.Point3D
import com.dancewithme.app.data.PoseFrame
import com.dancewithme.app.data.ReferenceDance
import com.dancewithme.app.data.ScoreHistoryRepository
import com.dancewithme.app.engine.BodyFramingEvaluator
import com.dancewithme.app.engine.DtwEngine
import com.dancewithme.app.engine.EngineConfig
import com.dancewithme.app.engine.FramingStatus
import com.dancewithme.app.engine.JointAngleCalculator
import com.dancewithme.app.engine.LandmarkSmoother
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * ViewModel central (MVVM / MVI) care coordonează:
 * - Selecția coregrafiei de referință (`ReferenceDance`).
 * - Persistența locală a recordurilor personale și a numărului de încercări prin [ScoreHistoryRepository].
 * - Starea de calibrare a înclinării telefonului (~90°).
 * - Filtrarea temporală (EMA) a reperelor 3D și 2D prin [LandmarkSmoother] pentru eliminarea jitter-ului.
 * - Colectarea în timp real a unui buffer de până la 600 de cadre (`List<AngleVector>`, 20s @ 30 FPS).
 * - Sincronizarea în timp real a "Ghost Skeleton"-ului de referință (`currentGhostLandmarks`) peste cameră.
 * - Evaluarea în timp real a posturii pe fiecare articulație (`liveJointAccuracies`) pentru colorarea
 *   independentă a membrelor stickman-ului în verde/roșu pe `Canvas`.
 * - Lansarea algoritmului matematic DTW în `viewModelScope` pe `defaultDispatcher` (`Dispatchers.Default`)
 *   la expirarea celor 20 de secunde sau la umplerea bufferului de 600 de cadre, fără a bloca UI-ul.
 */
class DrillViewModel(
    private val repository: DanceRepository = DefaultDanceRepository(),
    private val scoreHistoryRepository: ScoreHistoryRepository = InMemoryScoreHistoryRepository(),
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val poseLandmarkSmoother: LandmarkSmoother = LandmarkSmoother(),
    private val screenLandmarkSmoother: LandmarkSmoother = LandmarkSmoother(),
    private val timeProviderMs: () -> Long = { System.currentTimeMillis() }
) : ViewModel() {

    private val _uiState = MutableStateFlow(DrillUiState())
    val uiState: StateFlow<DrillUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<DrillEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<DrillEvent> = _events.asSharedFlow()

    /**
     * Bufferul intern thread-safe pentru stocarea secvenței de [AngleVector] a utilizatorului
     * (capacitate maximă: 600 cadre = 20 secunde * 30 FPS).
     */
    private val bufferLock = Any()
    private val angleBuffer = ArrayList<AngleVector>(MAX_BUFFER_FRAMES)

    @Volatile
    private var recordingStartTimeMs: Long = 0L

    private var timerJob: Job? = null
    private var evaluationJob: Job? = null

    init {
        loadAvailableDances()
    }

    /**
     * Încarcă lista de dansuri de referință din [DanceRepository] și istoricul scorurilor din [ScoreHistoryRepository].
     */
    fun loadAvailableDances() {
        viewModelScope.launch {
            val dances = repository.getAvailableDances()
            val savedProgress = scoreHistoryRepository.getAllProgress()
            _uiState.update { current ->
                val activeDance = current.selectedDance ?: dances.firstOrNull()
                current.copy(
                    availableDances = dances,
                    selectedDance = activeDance,
                    danceProgressMap = savedProgress,
                    currentGhostLandmarks = activeDance?.ghostFrames?.firstOrNull().orEmpty()
                )
            }
        }
    }

    /**
     * Selectează dansul curent după ID (ex: din `MainMenuScreen`).
     */
    fun selectDance(danceId: String) {
        val found = _uiState.value.availableDances.find { it.id == danceId }
        if (found != null) {
            _uiState.update {
                it.copy(
                    selectedDance = found,
                    remainingSeconds = found.durationSec,
                    phase = DrillPhase.READY,
                    capturedFramesCount = 0,
                    isNewBestScore = false,
                    currentGhostLandmarks = found.ghostFrames.firstOrNull().orEmpty()
                )
            }
        } else {
            viewModelScope.launch {
                val dance = repository.getDanceById(danceId)
                if (dance != null) {
                    _uiState.update {
                        it.copy(
                            selectedDance = dance,
                            remainingSeconds = dance.durationSec,
                            phase = DrillPhase.READY,
                            capturedFramesCount = 0,
                            isNewBestScore = false,
                            currentGhostLandmarks = dance.ghostFrames.firstOrNull().orEmpty()
                        )
                    }
                }
            }
        }
    }

    /**
     * Activează sau dezactivează afișarea scheletului fantomă de referință ("Ghost Skeleton")
     * suprapus peste fluxul video din `DrillScreen`.
     */
    fun toggleGhostOverlay() {
        _uiState.update { state ->
            state.copy(showGhostOverlay = !state.showGhostOverlay)
        }
    }

    /**
     * Activează sau dezactivează metronomul audio (BPM) și semnalele sonore 3-2-1 în `DrillScreen`.
     */
    fun toggleMetronome() {
        _uiState.update { state ->
            state.copy(isMetronomeEnabled = !state.isMetronomeEnabled)
        }
    }

    /**
     * Salvează o coregrafie personalizată înregistrată de utilizator (cu toate cele 8 unghiuri articulare
     * și `ghostFrames`) în [DanceRepository] și reîncarcă lista de dansuri disponibile.
     */
    fun saveCustomDance(
        title: String,
        bpm: Int,
        frames: List<AngleVector>,
        ghostFrames: List<List<Point3D>>,
        onSaved: () -> Unit = {}
    ) {
        if (frames.isEmpty()) return

        val customId = "${DefaultDanceRepository.CUSTOM_DANCE_ID_PREFIX}${System.currentTimeMillis()}"
        val cleanTitle = title.trim().ifBlank { "Coregrafia Mea" }
        val durationSec = (frames.size / 30).coerceIn(1, 20)

        val newDance = ReferenceDance(
            id = customId,
            title = cleanTitle,
            durationSec = durationSec,
            bpm = bpm,
            frames = frames,
            description = "Coregrafie personalizată înregistrată local cu Edge AI (8 unghiuri articulare).",
            videoAssetName = null,
            ghostFrames = ghostFrames
        )

        viewModelScope.launch {
            repository.saveCustomDance(newDance)
            val dances = repository.getAvailableDances()
            val savedProgress = scoreHistoryRepository.getAllProgress()
            _uiState.update { current ->
                current.copy(
                    availableDances = dances,
                    selectedDance = newDance,
                    danceProgressMap = savedProgress,
                    currentGhostLandmarks = newDance.ghostFrames.firstOrNull().orEmpty()
                )
            }
            onSaved()
        }
    }

    /**
     * Șterge o coregrafie personalizată din [DanceRepository] și actualizează lista de dansuri disponibile.
     */
    fun deleteCustomDance(danceId: String) {
        if (!DefaultDanceRepository.isCustomDance(danceId)) return
        viewModelScope.launch {
            repository.deleteCustomDance(danceId)
            val dances = repository.getAvailableDances()
            val savedProgress = scoreHistoryRepository.getAllProgress()
            _uiState.update { current ->
                val nextSelected = if (current.selectedDance?.id == danceId) {
                    dances.firstOrNull()
                } else {
                    current.selectedDance ?: dances.firstOrNull()
                }
                current.copy(
                    availableDances = dances,
                    selectedDance = nextSelected,
                    danceProgressMap = savedProgress,
                    currentGhostLandmarks = nextSelected?.ghostFrames?.firstOrNull().orEmpty()
                )
            }
        }
    }

    /**
     * Actualizează unghiul de înclinare al telefonului citit de la `GravitySensorManager` (în `CalibrationScreen`).
     * Telefonul este considerat calibrat când este aproape vertical (~90° ± [CALIBRATION_TOLERANCE_DEG]).
     */
    fun updatePhoneTilt(tiltDegrees: Float) {
        val isWithinTarget = abs(tiltDegrees - TARGET_TILT_DEG) <= CALIBRATION_TOLERANCE_DEG
        _uiState.update {
            it.copy(
                currentPhoneTiltDeg = tiltDegrees,
                isCalibrated = isWithinTarget
            )
        }
    }

    /**
     * Pornește sesiunea de dans (Drill). Poate fi apelat manual din UI, prin comanda
     * vocală offline Vosk ("Start") sau prin senzorul de proximitate (trecerea mâinii).
     *
     * @param withPreCountdown Dacă este `true`, numără invers 3..2..1 secunde înainte de a porni înregistrarea.
     */
    fun startDrill(withPreCountdown: Boolean = false) {
        val currentPhase = _uiState.value.phase
        if (currentPhase == DrillPhase.RECORDING || currentPhase == DrillPhase.EVALUATING) {
            return
        }

        timerJob?.cancel()
        evaluationJob?.cancel()

        poseLandmarkSmoother.reset()
        screenLandmarkSmoother.reset()

        synchronized(bufferLock) {
            angleBuffer.clear()
        }

        val durationSec = _uiState.value.selectedDance?.durationSec ?: EngineConfig.DRILL_DURATION_SECONDS
        val initialGhost = _uiState.value.selectedDance?.ghostFrames?.firstOrNull().orEmpty()

        if (withPreCountdown) {
            recordingStartTimeMs = 0L
            _uiState.update {
                it.copy(
                    phase = DrillPhase.COUNTDOWN_TO_START,
                    preDrillCountdownSec = PRE_COUNTDOWN_SECONDS,
                    remainingSeconds = durationSec,
                    capturedFramesCount = 0,
                    isNewBestScore = false,
                    currentGhostLandmarks = initialGhost,
                    errorMessage = null
                )
            }
        } else {
            recordingStartTimeMs = timeProviderMs()
            _uiState.update {
                it.copy(
                    phase = DrillPhase.RECORDING,
                    preDrillCountdownSec = 0,
                    remainingSeconds = durationSec,
                    capturedFramesCount = 0,
                    isNewBestScore = false,
                    currentGhostLandmarks = initialGhost,
                    errorMessage = null
                )
            }
        }

        timerJob = viewModelScope.launch {
            if (withPreCountdown) {
                for (cnt in PRE_COUNTDOWN_SECONDS downTo 1) {
                    _uiState.update { it.copy(preDrillCountdownSec = cnt) }
                    delay(1_000L)
                }
                recordingStartTimeMs = timeProviderMs()
                _uiState.update {
                    it.copy(
                        phase = DrillPhase.RECORDING,
                        preDrillCountdownSec = 0,
                        remainingSeconds = durationSec,
                        capturedFramesCount = 0
                    )
                }
            }

            for (remaining in durationSec downTo 1) {
                _uiState.update { it.copy(remainingSeconds = remaining) }
                delay(1_000L)
                // Dacă bufferul de 600 de cadre s-a umplut deja și a declanșat DTW-ul, ieșim din timer
                if (_uiState.value.phase != DrillPhase.RECORDING) {
                    return@launch
                }
            }

            _uiState.update { it.copy(remainingSeconds = 0) }
            finishAndEvaluateDrill()
        }
    }

    /**
     * Primește output-ul complet de la [com.dancewithme.app.ai.PoseLandmarkerHelper] / [com.dancewithme.app.camera.PoseAnalyzer]
     * (atât cadrul 3D cât și reperele 2D normalizate și dimensiunile cadrului sursă pentru desenarea stickman-ului în `Canvas`).
     */
    fun onPoseExtractionOutput(output: PoseExtractionOutput) {
        if (output.sourceWidth > 0 && output.sourceHeight > 0) {
            _uiState.update { state ->
                if (state.sourceFrameWidth == output.sourceWidth && state.sourceFrameHeight == output.sourceHeight) {
                    state
                } else {
                    state.copy(
                        sourceFrameWidth = output.sourceWidth,
                        sourceFrameHeight = output.sourceHeight
                    )
                }
            }
        }
        onPoseFrameReceived(
            poseFrame = output.poseFrame,
            normalizedScreenLandmarks = output.normalizedLandmarks,
            isSimulated = output.isSimulated
        )
    }

    /**
     * Primește un [PoseFrame] de la cameră (la ~30 FPS), aplică filtrul de netezire [LandmarkSmoother],
     * extrage [AngleVector] și:
     * 1. Actualizează coordonatele netezite ale stickman-ului și acuratețea pe fiecare membru.
     * 2. Dacă sesiunea este în faza [DrillPhase.RECORDING], adaugă vectorul în bufferul de 600 de cadre.
     * 3. Dacă s-au acumulat toate cele 600 de cadre, declanșează automat evaluarea DTW.
     */
    fun onPoseFrameReceived(
        poseFrame: PoseFrame,
        normalizedScreenLandmarks: List<Point3D> = poseFrame.landmarks,
        isSimulated: Boolean = false
    ) {
        if (poseFrame.landmarks.size < PoseFrame.REQUIRED_LANDMARKS_COUNT) return

        val framingAssessment = BodyFramingEvaluator.evaluate(poseFrame.landmarks)
        _uiState.update { state ->
            state.copy(
                framingStatus = framingAssessment.status,
                framingHint = framingAssessment.hintMessage,
                liveJointVisibilities = framingAssessment.visibleJoints
            )
        }

        val smoothed3D = poseLandmarkSmoother.smooth(poseFrame.landmarks)
        val smoothedScreen = screenLandmarkSmoother.smooth(normalizedScreenLandmarks)

        val smoothedPoseFrame = poseFrame.copy(landmarks = smoothed3D)
        val extractedAngles = JointAngleCalculator.extractAngleVector(smoothedPoseFrame)

        onAngleVectorCaptured(
            angleVector = extractedAngles,
            normalizedScreenLandmarks = smoothedScreen,
            isSimulated = isSimulated,
            alreadySmoothedScreen = true
        )
    }

    /**
     * Procesează un [AngleVector] extras, evaluează acuratețea fiecărei articulații în timp real,
     * sincronizează cadrul corespunzător din `selectedDance.ghostFrames` și adaugă vectorul
     * în bufferul de 600 de cadre dacă drill-ul este activ.
     */
    fun onAngleVectorCaptured(
        angleVector: AngleVector,
        normalizedScreenLandmarks: List<Point3D> = _uiState.value.liveNormalizedLandmarks,
        isSimulated: Boolean = _uiState.value.isSimulationMode,
        alreadySmoothedScreen: Boolean = false
    ) {
        val currentState = _uiState.value
        val referenceFrames = currentState.selectedDance?.frames.orEmpty()
        val ghostFrames = currentState.selectedDance?.ghostFrames.orEmpty()

        val smoothedScreenLandmarks = if (alreadySmoothedScreen || normalizedScreenLandmarks.isEmpty()) {
            normalizedScreenLandmarks
        } else {
            screenLandmarkSmoother.smooth(normalizedScreenLandmarks)
        }

        var bufferSizeAfterAdd = currentState.capturedFramesCount
        var reachedFullCapacity = false

        if (currentState.phase == DrillPhase.RECORDING) {
            synchronized(bufferLock) {
                if (angleBuffer.size < MAX_BUFFER_FRAMES) {
                    angleBuffer.add(angleVector)
                    bufferSizeAfterAdd = angleBuffer.size
                    if (angleBuffer.size >= MAX_BUFFER_FRAMES) {
                        reachedFullCapacity = true
                    }
                }
            }
        }

        val durationSec = currentState.selectedDance?.durationSec ?: EngineConfig.DRILL_DURATION_SECONDS
        val elapsedMs = if (currentState.phase == DrillPhase.RECORDING && recordingStartTimeMs > 0L) {
            (timeProviderMs() - recordingStartTimeMs).coerceAtLeast(0L)
        } else {
            0L
        }

        // Identificăm cadrul de referință sincronizat temporal (independent de FPS-ul camerei)
        val referenceIndex = if (referenceFrames.isNotEmpty()) {
            if (currentState.phase == DrillPhase.RECORDING && recordingStartTimeMs > 0L) {
                computeTimeBasedFrameIndex(
                    elapsedMs = elapsedMs,
                    durationSec = durationSec,
                    totalFrames = referenceFrames.size
                )
            } else {
                0
            }
        } else {
            -1
        }

        // Identificăm cadrul Ghost Skeleton sincronizat temporal cu faza curentă
        val currentGhostFrame: List<Point3D> = if (ghostFrames.isNotEmpty()) {
            val ghostIdx = if (currentState.phase == DrillPhase.RECORDING && recordingStartTimeMs > 0L) {
                computeTimeBasedFrameIndex(
                    elapsedMs = elapsedMs,
                    durationSec = durationSec,
                    totalFrames = ghostFrames.size
                )
            } else {
                0
            }
            ghostFrames[ghostIdx]
        } else {
            emptyList()
        }

        val instantError: Float
        val jointAccuracies: Map<String, Boolean>
        val isAccurateNow: Boolean

        if (referenceIndex >= 0) {
            val refVector = referenceFrames[referenceIndex]
            instantError = DtwEngine.frameDistance(angleVector, refVector)
            jointAccuracies = computeJointAccuracies(angleVector, refVector)
            // Postura generală este considerată corectă dacă eroarea medie <= prag și majoritatea articulațiilor sunt verzi
            val accurateJointCount = jointAccuracies.values.count { it }
            isAccurateNow = instantError <= LIVE_FORM_ERROR_THRESHOLD_DEG &&
                accurateJointCount >= (AngleVector.JOINT_COUNT / 2)
        } else {
            instantError = 0f
            jointAccuracies = AngleVector.JOINT_NAMES.associateWith { true }
            isAccurateNow = true
        }

        _uiState.update { state ->
            state.copy(
                capturedFramesCount = bufferSizeAfterAdd,
                liveNormalizedLandmarks = smoothedScreenLandmarks,
                currentGhostLandmarks = currentGhostFrame,
                liveAngleVector = angleVector,
                liveJointAccuracies = jointAccuracies,
                isLiveFormAccurate = isAccurateNow,
                currentInstantErrorDeg = instantError,
                isSimulationMode = isSimulated
            )
        }

        if (reachedFullCapacity) {
            timerJob?.cancel()
            finishAndEvaluateDrill()
        }
    }

    private fun computeJointAccuracies(
        userAngle: AngleVector,
        refAngle: AngleVector
    ): Map<String, Boolean> = mapOf(
        AngleVector.JOINT_LEFT_KNEE to (abs(userAngle.genunchiStang - refAngle.genunchiStang) <= LIVE_FORM_ERROR_THRESHOLD_DEG),
        AngleVector.JOINT_RIGHT_KNEE to (abs(userAngle.genunchiDrept - refAngle.genunchiDrept) <= LIVE_FORM_ERROR_THRESHOLD_DEG),
        AngleVector.JOINT_LEFT_HIP to (abs(userAngle.soldStang - refAngle.soldStang) <= LIVE_FORM_ERROR_THRESHOLD_DEG),
        AngleVector.JOINT_RIGHT_HIP to (abs(userAngle.soldDrept - refAngle.soldDrept) <= LIVE_FORM_ERROR_THRESHOLD_DEG),
        AngleVector.JOINT_LEFT_ELBOW to (abs(userAngle.cotStang - refAngle.cotStang) <= LIVE_FORM_ERROR_THRESHOLD_DEG),
        AngleVector.JOINT_RIGHT_ELBOW to (abs(userAngle.cotDrept - refAngle.cotDrept) <= LIVE_FORM_ERROR_THRESHOLD_DEG),
        AngleVector.JOINT_LEFT_SHOULDER to (abs(userAngle.umarStang - refAngle.umarStang) <= LIVE_FORM_ERROR_THRESHOLD_DEG),
        AngleVector.JOINT_RIGHT_SHOULDER to (abs(userAngle.umarDrept - refAngle.umarDrept) <= LIVE_FORM_ERROR_THRESHOLD_DEG)
    )

    /**
     * Oprește înregistrarea, lansează calculul DTW în `viewModelScope` pe `defaultDispatcher`
     * pentru a nu bloca firul principal (UI) și salvează scorul obținut în [ScoreHistoryRepository].
     */
    fun finishAndEvaluateDrill() {
        val currentState = _uiState.value
        if (currentState.phase == DrillPhase.EVALUATING || currentState.phase == DrillPhase.COMPLETED) {
            return
        }

        timerJob?.cancel()

        val capturedSnapshot: List<AngleVector> = synchronized(bufferLock) {
            angleBuffer.toList()
        }

        val activeDance = currentState.selectedDance
            ?: DefaultDanceRepository.builtInReferenceDances().first()
        val referenceFrames: List<AngleVector> = activeDance.frames

        _uiState.update {
            it.copy(
                phase = DrillPhase.EVALUATING,
                remainingSeconds = 0,
                capturedFramesCount = capturedSnapshot.size
            )
        }

        evaluationJob = viewModelScope.launch {
            val result: DrillResult = withContext(defaultDispatcher) {
                val effectiveUserFrames = if (capturedSnapshot.isNotEmpty()) {
                    capturedSnapshot
                } else {
                    referenceFrames
                }

                DtwEngine.evaluateDrill(
                    userFrames = effectiveUserFrames,
                    referenceFrames = referenceFrames,
                    windowSize = DtwEngine.DEFAULT_SAKOE_CHIBA_BAND
                )
            }

            val recordAttemptResult = scoreHistoryRepository.recordAttempt(
                danceId = activeDance.id,
                score = result.totalScore
            )

            _uiState.update { state ->
                state.copy(
                    phase = DrillPhase.COMPLETED,
                    lastDrillResult = result,
                    danceProgressMap = state.danceProgressMap + (activeDance.id to recordAttemptResult.updatedProgress),
                    isNewBestScore = recordAttemptResult.isNewBestScore
                )
            }
            _events.tryEmit(DrillEvent.NavigateToResults(result))
        }
    }

    /**
     * Resetează starea sesiunii și filtrele de netezire pentru un nou antrenament (Drill).
     */
    fun resetDrill() {
        timerJob?.cancel()
        evaluationJob?.cancel()
        recordingStartTimeMs = 0L
        poseLandmarkSmoother.reset()
        screenLandmarkSmoother.reset()
        synchronized(bufferLock) {
            angleBuffer.clear()
        }
        val durationSec = _uiState.value.selectedDance?.durationSec ?: EngineConfig.DRILL_DURATION_SECONDS
        val initialGhost = _uiState.value.selectedDance?.ghostFrames?.firstOrNull().orEmpty()
        _uiState.update {
            it.copy(
                phase = DrillPhase.READY,
                remainingSeconds = durationSec,
                capturedFramesCount = 0,
                isNewBestScore = false,
                currentGhostLandmarks = initialGhost,
                currentInstantErrorDeg = 0f,
                liveJointAccuracies = emptyMap(),
                liveJointVisibilities = emptyMap(),
                framingStatus = FramingStatus.OK,
                framingHint = null,
                isLiveFormAccurate = true,
                errorMessage = null
            )
        }
    }

    /**
     * Returnează o copie nemodificabilă a bufferului curent de [AngleVector] (util pentru testare și debugging).
     */
    fun getCapturedBufferSnapshot(): List<AngleVector> = synchronized(bufferLock) {
        angleBuffer.toList()
    }

    companion object {
        const val MAX_BUFFER_FRAMES = EngineConfig.EXPECTED_FRAMES // 600 cadre (20s * 30 FPS)
        const val PRE_COUNTDOWN_SECONDS = 3
        const val TARGET_TILT_DEG = 90.0f
        const val CALIBRATION_TOLERANCE_DEG = 8.0f
        const val LIVE_FORM_ERROR_THRESHOLD_DEG = 22.0f

        /**
         * Calculează indexul cadrului de referință / Ghost Skeleton pe baza timpului real scurs (`elapsedMs`),
         * astfel încât viteza coregrafiei să rămână sincronizată cu cronometrul de 20s și cu metronomul BPM
         * chiar și pe telefoane unde MediaPipe rulează la 12–20 FPS în loc de 30 FPS.
         */
        fun computeTimeBasedFrameIndex(
            elapsedMs: Long,
            durationSec: Int,
            totalFrames: Int
        ): Int {
            if (totalFrames <= 0) return -1
            val totalDurationMs = (durationSec.coerceAtLeast(1) * 1_000L).toFloat()
            val progress = (elapsedMs.coerceAtLeast(0L).toFloat() / totalDurationMs).coerceIn(0f, 1f)
            return (progress * (totalFrames - 1)).toInt().coerceIn(0, totalFrames - 1)
        }

        /**
         * Factory pentru instanțierea [DrillViewModel] în Jetpack Compose (`viewModel(factory = ...)`).
         */
        fun provideFactory(
            repository: DanceRepository = DefaultDanceRepository(),
            scoreHistoryRepository: ScoreHistoryRepository = InMemoryScoreHistoryRepository()
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DrillViewModel(
                    repository = repository,
                    scoreHistoryRepository = scoreHistoryRepository
                ) as T
            }
        }
    }
}
