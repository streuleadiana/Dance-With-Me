package com.dancewithme.app.ui

import com.dancewithme.app.data.AngleVector
import com.dancewithme.app.data.DanceProgress
import com.dancewithme.app.data.DrillResult
import com.dancewithme.app.data.Point3D
import com.dancewithme.app.data.ReferenceDance
import com.dancewithme.app.engine.FramingStatus

/**
 * Fazele prin care trece o sesiune de dans (Drill).
 */
enum class DrillPhase {
    /** Așteaptă comanda vocală "Start", trecerea mâinii peste senzorul de proximitate sau apăsarea butonului. */
    READY,

    /** Numărătoare inversă de pregătire (3, 2, 1) înainte de înregistrarea cadrelor. */
    COUNTDOWN_TO_START,

    /** Sesiunea activă de 20 de secunde (colectare buffer de până la 600 de cadre @ 30 FPS). */
    RECORDING,

    /** Calculul asincron al scorului prin algoritmul DTW pe un fir de background. */
    EVALUATING,

    /** Sesiunea s-a încheiat și rezultatul DTW este gata de afișare și redare TTS. */
    COMPLETED
}

/**
 * Evenimente one-shot emise de [DrillViewModel] prin `SharedFlow` pentru navigare și feedback.
 */
sealed interface DrillEvent {
    data class NavigateToResults(val result: DrillResult) : DrillEvent
    data class ShowMessage(val message: String) : DrillEvent
}

/**
 * Starea completă și imutabilă a interfeței (MVI / MVVM State) gestionată de [DrillViewModel].
 */
data class DrillUiState(
    val availableDances: List<ReferenceDance> = emptyList(),
    val selectedDance: ReferenceDance? = null,
    val danceProgressMap: Map<String, DanceProgress> = emptyMap(),
    val isNewBestScore: Boolean = false,
    val phase: DrillPhase = DrillPhase.READY,
    val preDrillCountdownSec: Int = 3,
    val remainingSeconds: Int = 20,
    val capturedFramesCount: Int = 0,
    val maxFramesCapacity: Int = 600,
    val liveNormalizedLandmarks: List<Point3D> = emptyList(),
    val sourceFrameWidth: Int = 480,
    val sourceFrameHeight: Int = 640,
    val currentGhostLandmarks: List<Point3D> = emptyList(),
    val showGhostOverlay: Boolean = true,
    val isMetronomeEnabled: Boolean = true,
    val liveAngleVector: AngleVector? = null,
    val liveJointAccuracies: Map<String, Boolean> = emptyMap(),
    val liveJointVisibilities: Map<String, Boolean> = emptyMap(),
    val framingStatus: FramingStatus = FramingStatus.OK,
    val framingHint: String? = null,
    val isLiveFormAccurate: Boolean = true,
    val currentInstantErrorDeg: Float = 0f,
    val isSimulationMode: Boolean = false,
    val isCalibrated: Boolean = false,
    val currentPhoneTiltDeg: Float = 0f,
    val lastDrillResult: DrillResult? = null,
    val errorMessage: String? = null
)
