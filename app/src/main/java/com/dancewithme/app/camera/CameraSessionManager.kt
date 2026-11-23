package com.dancewithme.app.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Manager dedicat pentru configurarea și legarea la ciclu de viață (Lifecycle) a sesiunii CameraX.
 *
 * Responsabilități:
 * - Configurează `Preview` pentru afișarea pe ecran (`PreviewView`).
 * - Configurează `ImageAnalysis` cu `STRATEGY_KEEP_ONLY_LATEST` și `OUTPUT_IMAGE_FORMAT_RGBA_8888`
 *   pentru latență minimă la 30 FPS către MediaPipe.
 * - Rulează `PoseAnalyzer` pe un `SingleThreadExecutor` de background dedicat pentru a nu bloca firul UI.
 */
class CameraSessionManager(
    private val context: Context
) {
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DanceWithMe-PoseAnalyzerThread").apply {
            priority = Thread.NORM_PRIORITY
        }
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var activeAnalyzer: PoseAnalyzer? = null

    /**
     * Pornește sesiunea CameraX și atașează atât [PreviewView] cât și [PoseAnalyzer].
     */
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        poseAnalyzer: PoseAnalyzer,
        useFrontCamera: Boolean = true,
        onError: (Throwable) -> Unit = {}
    ) {
        activeAnalyzer = poseAnalyzer
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener(
            {
                try {
                    val provider = cameraProviderFuture.get()
                    cameraProvider = provider

                    val resolutionSelector = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()

                    val preview = Preview.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .build()
                        .also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                        .also { analysisUseCase ->
                            analysisUseCase.setAnalyzer(analysisExecutor, poseAnalyzer)
                        }

                    val cameraSelector = if (useFrontCamera && provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "Eroare la legarea sesiunii CameraX la Lifecycle: ${t.message}", t)
                    onError(t)
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    /**
     * Oprește sesiunea curentă și eliberează resursele camerei și firul de background.
     */
    fun shutdown() {
        try {
            cameraProvider?.unbindAll()
            activeAnalyzer?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Eroare la oprirea CameraSessionManager: ${t.message}")
        } finally {
            analysisExecutor.shutdown()
        }
    }

    companion object {
        private const val TAG = "CameraSessionManager"
    }
}
