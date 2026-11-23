package com.dancewithme.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.dancewithme.app.data.Point3D
import com.dancewithme.app.data.PoseFrame
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rezultatul procesării unui cadru video de către [PoseLandmarkerHelper].
 *
 * @property poseFrame Cadrul 3D folosit pentru calculul matematic al unghiurilor articulare.
 * @property normalizedLandmarks Coordonatele 2D/3D normalizate în spațiul ecranului [0.0..1.0],
 *           folosite de UI (Canvas) pentru desenarea scheletului (stickman) peste CameraPreview.
 * @property inferenceTimeMs Durata inferenței pe acel cadru (în milisecunde).
 * @property isSimulated Indică dacă rezultatul provine din modul de simulare (fallback când modelul .task nu este în assets).
 */
data class PoseExtractionOutput(
    val poseFrame: PoseFrame,
    val normalizedLandmarks: List<Point3D>,
    val inferenceTimeMs: Long,
    val isSimulated: Boolean = false,
    val sourceWidth: Int = 480,
    val sourceHeight: Int = 640
)

/**
 * Wrapper peste Google MediaPipe Pose Landmarker (Tasks Vision API).
 *
 * Caracteristici arhitecturale:
 * - Încearcă inițializarea cu **GPU Delegate** (`Delegate.GPU`) pentru 30 FPS pe Edge AI.
 * - Dacă GPU-ul eșuează pe dispozitiv/emulator, face fallback automat pe `Delegate.CPU`.
 * - Dacă fișierul `pose_landmarker_lite.task` (sau `pose_landmarker_full.task`) lipsește din
 *   `app/src/main/assets/`, activează automat un generator sintetic de schelet 3D la 30 FPS
 *   astfel încât aplicația să poată fi testată cap-coadă imediat.
 */
class PoseLandmarkerHelper(
    private val context: Context,
    private val modelAssetPath: String = DEFAULT_MODEL_ASSET,
    private val minPoseDetectionConfidence: Float = DEFAULT_CONFIDENCE,
    private val minPoseTrackingConfidence: Float = DEFAULT_CONFIDENCE,
    private val minPosePresenceConfidence: Float = DEFAULT_CONFIDENCE,
    private val onResults: (PoseExtractionOutput) -> Unit,
    private val onError: (String, Throwable?) -> Unit = { msg, err ->
        Log.e(TAG, msg, err)
    }
) {
    private var poseLandmarker: PoseLandmarker? = null

    /**
     * Devine `true` dacă fișierul `.task` nu a fost găsit în `assets/`, permițând testarea
     * întregului pipeline CameraX -> Analyzer -> ViewModel -> DTW -> UI fără blocaje.
     */
    var isSimulationMode: Boolean = false
        private set

    var activeDelegate: Delegate = Delegate.GPU
        private set

    init {
        setupPoseLandmarker()
    }

    /**
     * Inițializează MediaPipe [PoseLandmarker] cu GPU delegate (și fallback la CPU).
     */
    @Synchronized
    fun setupPoseLandmarker() {
        close()

        val resolvedModel = resolveAvailableModelAsset()
        if (resolvedModel == null) {
            isSimulationMode = true
            Log.w(
                TAG,
                "Modelul MediaPipe ($modelAssetPath) nu a fost găsit în assets/. " +
                    "Se activează modul de simulare 3D în timp real pentru testare."
            )
            return
        }

        // Încercăm mai întâi Delegate.GPU conform arhitecturii Edge AI
        val initializedWithGpu = tryCreateLandmarker(resolvedModel, Delegate.GPU)
        if (!initializedWithGpu) {
            Log.w(TAG, "Inițializarea MediaPipe pe GPU a eșuat. Se comută pe Delegate.CPU.")
            val initializedWithCpu = tryCreateLandmarker(resolvedModel, Delegate.CPU)
            if (!initializedWithCpu) {
                isSimulationMode = true
                onError(
                    "MediaPipe nu a putut fi inițializat nici pe GPU, nici pe CPU. S-a activat modul de simulare.",
                    null
                )
            }
        }
    }

    private fun tryCreateLandmarker(assetName: String, delegate: Delegate): Boolean {
        return try {
            val baseOptions = BaseOptions.builder()
                .setDelegate(delegate)
                .setModelAssetPath(assetName)
                .build()

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinPoseDetectionConfidence(minPoseDetectionConfidence)
                .setMinTrackingConfidence(minPoseTrackingConfidence)
                .setMinPosePresenceConfidence(minPosePresenceConfidence)
                .setNumPoses(1)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener(this::onLiveStreamResult)
                .setErrorListener(this::onLiveStreamError)
                .build()

            poseLandmarker = PoseLandmarker.createFromOptions(context, options)
            activeDelegate = delegate
            isSimulationMode = false
            Log.i(TAG, "MediaPipe PoseLandmarker inițializat cu succes ($assetName, delegate=$delegate)")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Eroare la crearea PoseLandmarker cu delegate=$delegate: ${t.message}")
            false
        }
    }

    private fun resolveAvailableModelAsset(): String? {
        return try {
            val rootAssets = context.assets.list("")?.toSet().orEmpty()
            CANDIDATE_MODELS.firstOrNull { candidate ->
                candidate in rootAssets || canOpenAsset(candidate)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun canOpenAsset(assetName: String): Boolean {
        return try {
            context.assets.open(assetName).close()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Primește un [Bitmap] gata rotit și oglindit (dacă e camera frontală) de la `PoseAnalyzer`
     * și lansează detecția asincronă pe firul de execuție de background.
     */
    fun detectLiveStream(bitmap: Bitmap, frameTimestampMs: Long = SystemClock.uptimeMillis()) {
        if (isSimulationMode) {
            val startMs = SystemClock.uptimeMillis()
            val simulated = generateSimulatedPoseOutput(frameTimestampMs, SystemClock.uptimeMillis() - startMs)
            onResults(simulated)
            return
        }

        val landmarker = poseLandmarker ?: return
        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            landmarker.detectAsync(mpImage, frameTimestampMs)
        } catch (t: Throwable) {
            onError("Eroare în detectLiveStream: ${t.message}", t)
        }
    }

    private fun onLiveStreamResult(result: PoseLandmarkerResult, input: MPImage) {
        val timestampMs = result.timestampMs()
        val inferenceTimeMs = SystemClock.uptimeMillis() - timestampMs

        val normalizedList = result.landmarks().firstOrNull()
        if (normalizedList == null || normalizedList.size < PoseFrame.REQUIRED_LANDMARKS_COUNT) {
            return
        }

        // Convertim reperele 2D/3D normalizate în [0..1] (pentru desenarea stickman-ului în Canvas)
        val screenLandmarks = normalizedList.map { lm ->
            Point3D(
                x = lm.x(),
                y = lm.y(),
                z = lm.z(),
                visibility = lm.visibility().orElse(1.0f)
            )
        }

        // Pentru calculul unghiurilor în spațiul 3D real, preferăm worldLandmarks() (în metri),
        // cu fallback la reperele normalizate dacă worldLandmarks nu sunt prezente.
        val worldList = result.worldLandmarks().firstOrNull()
        val poseLandmarks3D = if (worldList != null && worldList.size >= PoseFrame.REQUIRED_LANDMARKS_COUNT) {
            worldList.map { wlm ->
                Point3D(
                    x = wlm.x(),
                    y = wlm.y(),
                    z = wlm.z(),
                    visibility = wlm.visibility().orElse(1.0f)
                )
            }
        } else {
            screenLandmarks
        }

        val poseFrame = PoseFrame(
            timestamp = timestampMs,
            landmarks = poseLandmarks3D
        )

        onResults(
            PoseExtractionOutput(
                poseFrame = poseFrame,
                normalizedLandmarks = screenLandmarks,
                inferenceTimeMs = inferenceTimeMs.coerceAtLeast(1L),
                isSimulated = false,
                sourceWidth = input.width,
                sourceHeight = input.height
            )
        )
    }

    private fun onLiveStreamError(error: RuntimeException) {
        onError("Eroare MediaPipe LIVE_STREAM: ${error.message}", error)
    }

    /**
     * Eliberează resursele native MediaPipe. Trebuie apelat la închiderea analizorului / ecranului.
     */
    @Synchronized
    fun close() {
        try {
            poseLandmarker?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Eroare la închiderea PoseLandmarker: ${t.message}")
        } finally {
            poseLandmarker = null
        }
    }

    companion object {
        private const val TAG = "PoseLandmarkerHelper"
        const val DEFAULT_MODEL_ASSET = "pose_landmarker_lite.task"
        const val DEFAULT_CONFIDENCE = 0.5f

        private val CANDIDATE_MODELS = listOf(
            "pose_landmarker_lite.task",
            "pose_landmarker_full.task",
            "pose_landmarker_heavy.task"
        )

        /**
         * Generează un schelet uman 3D animat realist (33 de puncte) în coordonate normalizate [0..1],
         * utilizat atât în teste cât și ca fallback când fișierul `.task` nu este încă adăugat în `assets/`.
         */
        fun generateSimulatedPoseOutput(
            timestampMs: Long,
            inferenceTimeMs: Long = 8L
        ): PoseExtractionOutput {
            val tSec = timestampMs / 1000.0
            val wave = sin(tSec * 2.0 * Math.PI).toFloat() // oscilație [-1..1] la 1 Hz
            val bounce = (0.03f * cos(tSec * 4.0 * Math.PI)).toFloat()

            val landmarks = MutableList(PoseFrame.REQUIRED_LANDMARKS_COUNT) {
                Point3D(x = 0.5f, y = 0.5f, z = 0f, visibility = 0.99f)
            }

            // Cap (Nas = 0)
            landmarks[PoseFrame.NOSE] = Point3D(0.50f, 0.18f + bounce, 0.0f, 0.99f)

            // Umeri (11 = Stâng, 12 = Drept)
            landmarks[PoseFrame.LEFT_SHOULDER] = Point3D(0.38f, 0.30f + bounce, 0.0f, 0.99f)
            landmarks[PoseFrame.RIGHT_SHOULDER] = Point3D(0.62f, 0.30f + bounce, 0.0f, 0.99f)

            // Coate (13 = Stâng, 14 = Drept)
            landmarks[PoseFrame.LEFT_ELBOW] = Point3D(0.28f, 0.42f + bounce - 0.05f * wave, -0.05f, 0.98f)
            landmarks[PoseFrame.RIGHT_ELBOW] = Point3D(0.72f, 0.42f + bounce + 0.05f * wave, -0.05f, 0.98f)

            // Încheieturi (15 = Stângă, 16 = Dreaptă) - mișcare de dans ritmică
            landmarks[PoseFrame.LEFT_WRIST] = Point3D(0.24f + 0.06f * wave, 0.32f - 0.10f * wave, -0.10f, 0.97f)
            landmarks[PoseFrame.RIGHT_WRIST] = Point3D(0.76f - 0.06f * wave, 0.32f + 0.10f * wave, -0.10f, 0.97f)

            // Șolduri (23 = Stâng, 24 = Drept)
            landmarks[PoseFrame.LEFT_HIP] = Point3D(0.42f + 0.02f * wave, 0.54f + bounce, 0.0f, 0.99f)
            landmarks[PoseFrame.RIGHT_HIP] = Point3D(0.58f + 0.02f * wave, 0.54f + bounce, 0.0f, 0.99f)

            // Genunchi (25 = Stâng, 26 = Drept)
            landmarks[PoseFrame.LEFT_KNEE] = Point3D(0.40f - 0.03f * wave, 0.72f + bounce * 0.5f, -0.03f, 0.98f)
            landmarks[PoseFrame.RIGHT_KNEE] = Point3D(0.60f + 0.03f * wave, 0.72f + bounce * 0.5f, -0.03f, 0.98f)

            // Glezne (27 = Stângă, 28 = Dreaptă)
            landmarks[PoseFrame.LEFT_ANKLE] = Point3D(0.41f, 0.90f, 0.0f, 0.98f)
            landmarks[PoseFrame.RIGHT_ANKLE] = Point3D(0.59f, 0.90f, 0.0f, 0.98f)

            val poseFrame = PoseFrame(
                timestamp = timestampMs,
                landmarks = landmarks
            )

            return PoseExtractionOutput(
                poseFrame = poseFrame,
                normalizedLandmarks = landmarks,
                inferenceTimeMs = inferenceTimeMs,
                isSimulated = true
            )
        }
    }
}
