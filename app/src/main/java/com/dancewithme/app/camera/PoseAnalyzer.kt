package com.dancewithme.app.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.dancewithme.app.ai.PoseExtractionOutput
import com.dancewithme.app.ai.PoseLandmarkerHelper
import com.dancewithme.app.data.PoseFrame
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Implementare [ImageAnalysis.Analyzer] din CameraX care preia cadrele video (`ImageProxy`)
 * pe un fir de execuție de background, le rotește/oglindește conform camerei și le trimite
 * către [PoseLandmarkerHelper] pentru extragerea unui [PoseFrame] la ~30 FPS.
 *
 * Include management explicit al memoriei pentru `Bitmap`-urile intermediare (`recycle()`)
 * pentru a preveni presiunea pe Garbage Collector și supraîncălzirea dispozitivului la 30 FPS.
 */
class PoseAnalyzer(
    private val poseLandmarkerHelper: PoseLandmarkerHelper,
    private val isFrontCamera: Boolean = true
) : ImageAnalysis.Analyzer {

    private val isClosed = AtomicBoolean(false)
    private val transformMatrix = Matrix()

    /**
     * Analizează un cadru [ImageProxy] primit de la CameraX.
     * Această metodă este apelată exclusiv pe `backgroundExecutor`-ul configurat în CameraX.
     */
    override fun analyze(imageProxy: ImageProxy) {
        if (isClosed.get()) {
            imageProxy.close()
            return
        }

        var rawBitmap: Bitmap? = null
        var processedBitmap: Bitmap? = null

        try {
            // Convertim ImageProxy (RGBA_8888 sau YUV_420_888) în Bitmap
            rawBitmap = imageProxy.toBitmap()
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            processedBitmap = transformBitmapIfNeeded(
                source = rawBitmap,
                rotationDegrees = rotationDegrees,
                mirrorHorizontally = isFrontCamera
            )

            // Dacă transformBitmapIfNeeded a alocat un Bitmap nou (rotit / oglindit),
            // eliberăm imediat rawBitmap-ul intermediar pentru a evita acumularea pe heap la 30 FPS
            if (processedBitmap !== rawBitmap && !rawBitmap.isRecycled) {
                rawBitmap.recycle()
            }

            // Timestamp-ul monoton în milisecunde cerut de MediaPipe LIVE_STREAM
            val frameTimestampMs = SystemClock.uptimeMillis()

            poseLandmarkerHelper.detectLiveStream(
                bitmap = processedBitmap,
                frameTimestampMs = frameTimestampMs
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Eroare la procesarea cadrului ImageProxy: ${t.message}", t)
            if (rawBitmap != null && rawBitmap !== processedBitmap && !rawBitmap.isRecycled) {
                rawBitmap.recycle()
            }
        } finally {
            // CRITIC: Închidem întotdeauna ImageProxy pentru a nu bloca pipeline-ul CameraX la 30 FPS
            imageProxy.close()
        }
    }

    /**
     * Rotește și oglindește [Bitmap]-ul conform orientării senzorului camerei și tipului de lentilă
     * (camera frontală necesită oglindire orizontală pentru ca stickman-ul să corespundă cu utilizatorul).
     */
    internal fun transformBitmapIfNeeded(
        source: Bitmap,
        rotationDegrees: Int,
        mirrorHorizontally: Boolean
    ): Bitmap {
        if (rotationDegrees == 0 && !mirrorHorizontally) {
            return source
        }

        transformMatrix.reset()
        if (rotationDegrees != 0) {
            transformMatrix.postRotate(rotationDegrees.toFloat())
        }
        if (mirrorHorizontally) {
            transformMatrix.postScale(
                -1f,
                1f,
                source.width / 2f,
                source.height / 2f
            )
        }

        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            transformMatrix,
            true
        )
    }

    /**
     * Marchează analizorul ca închis și eliberează resursele native din [PoseLandmarkerHelper].
     */
    fun close() {
        if (isClosed.compareAndSet(false, true)) {
            poseLandmarkerHelper.close()
        }
    }

    companion object {
        private const val TAG = "PoseAnalyzer"

        /**
         * Factory method care construiește un [PoseAnalyzer] complet cablat cu [PoseLandmarkerHelper]
         * pentru a livra direct [PoseFrame] și [PoseExtractionOutput].
         */
        fun create(
            context: android.content.Context,
            isFrontCamera: Boolean = true,
            onPoseFrame: (PoseFrame) -> Unit,
            onPoseOutput: (PoseExtractionOutput) -> Unit = {},
            onError: (String, Throwable?) -> Unit = { _, _ -> }
        ): PoseAnalyzer {
            val helper = PoseLandmarkerHelper(
                context = context,
                onResults = { output ->
                    onPoseFrame(output.poseFrame)
                    onPoseOutput(output)
                },
                onError = onError
            )
            return PoseAnalyzer(
                poseLandmarkerHelper = helper,
                isFrontCamera = isFrontCamera
            )
        }
    }
}
