package com.dancewithme.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dancewithme.app.ai.PoseExtractionOutput
import com.dancewithme.app.ai.PoseLandmarkerHelper
import com.dancewithme.app.audio.RhythmMetronomeHelper
import com.dancewithme.app.camera.CameraSessionManager
import com.dancewithme.app.camera.PoseAnalyzer
import com.dancewithme.app.data.AngleVector
import com.dancewithme.app.data.Point3D
import com.dancewithme.app.data.PoseFrame
import com.dancewithme.app.engine.BodyFramingEvaluator
import com.dancewithme.app.engine.JointAngleCalculator
import com.dancewithme.app.engine.LandmarkSmoother
import kotlinx.coroutines.delay

/**
 * Fazele ecranului de înregistrare a unei coregrafii personalizate (`RecordDanceScreen`).
 */
private enum class RecordDancePhase {
    READY,
    COUNTDOWN,
    RECORDING
}

private val PRESET_BPMS = listOf(60, 90, 110, 132)
private const val MAX_CUSTOM_FRAMES = 600
private const val MIN_FRAMES_TO_SAVE = 30
private const val RECORD_DURATION_SEC = 20

/**
 * Ecranul „Creează-ți Propriul Dans” (`RecordDanceScreen`):
 * - Permite utilizatorului să își înregistreze propria coregrafie 3D cu camera frontală (până la 20s / 600 cadre @ 30 FPS).
 * - Folosește [LandmarkSmoother] (EMA), [JointAngleCalculator] (8 unghiuri articulare), [BodyFramingEvaluator]
 *   pentru verificarea încadrării în cadru și [RhythmMetronomeHelper] pentru semnalele 3-2-1 și ghidajul BPM.
 * - La finalizare, salvează atât vectorii de unghiuri (`List<AngleVector>`) cât și coordonatele scheletului
 *   (`ghostFrames: List<List<Point3D>>`), astfel încât dansul să poată fi exersat ulterior cu Tutorial 3D și Ghost Skeleton.
 */
@Composable
fun RecordDanceScreen(
    onSaveCustomDance: (
        title: String,
        bpm: Int,
        frames: List<AngleVector>,
        ghostFrames: List<List<Point3D>>
    ) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentView = LocalView.current

    // Menținem ecranul pornit pe durata înregistrării coregrafiei
    DisposableEffect(currentView) {
        currentView.keepScreenOn = true
        onDispose {
            currentView.keepScreenOn = false
        }
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var danceTitle by remember { mutableStateOf("Dansul Meu") }
    var selectedBpm by remember { mutableIntStateOf(90) }
    var phase by remember { mutableStateOf(RecordDancePhase.READY) }
    var countdownSec by remember { mutableIntStateOf(3) }
    var remainingSec by remember { mutableIntStateOf(RECORD_DURATION_SEC) }

    val poseSmoother = remember { LandmarkSmoother() }
    val screenSmoother = remember { LandmarkSmoother() }

    var liveLandmarks by remember { mutableStateOf<List<Point3D>>(emptyList()) }
    var liveVisibilities by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var framingHint by remember { mutableStateOf<String?>(null) }
    var liveSourceWidth by remember { mutableIntStateOf(480) }
    var liveSourceHeight by remember { mutableIntStateOf(640) }

    val capturedAngles = remember { mutableStateListOf<AngleVector>() }
    val capturedGhostFrames = remember { mutableStateListOf<List<Point3D>>() }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val hasSavedRecording = remember { AtomicBoolean(false) }

    fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    val metronome = remember { RhythmMetronomeHelper() }
    DisposableEffect(metronome) {
        onDispose {
            metronome.release()
        }
    }

    fun finishAndSaveRecording() {
        if (capturedAngles.isNotEmpty() && hasSavedRecording.compareAndSet(false, true)) {
            onSaveCustomDance(
                danceTitle.trim().ifBlank { "Dansul Meu" },
                selectedBpm,
                capturedAngles.toList(),
                capturedGhostFrames.toList()
            )
        }
    }

    fun processPoseOutput(output: PoseExtractionOutput) {
        val rawLandmarks = output.poseFrame.landmarks
        if (rawLandmarks.size < PoseFrame.REQUIRED_LANDMARKS_COUNT) return

        val framing = BodyFramingEvaluator.evaluate(rawLandmarks)
        val smoothed3D = poseSmoother.smooth(rawLandmarks)
        val smoothedScreen = screenSmoother.smooth(output.normalizedLandmarks)
        val angleVector = JointAngleCalculator.extractAngleVector(
            output.poseFrame.copy(landmarks = smoothed3D)
        )

        runOnMainThread {
            framingHint = framing.hintMessage
            liveVisibilities = framing.visibleJoints
            liveLandmarks = smoothedScreen
            if (output.sourceWidth > 0 && output.sourceHeight > 0) {
                liveSourceWidth = output.sourceWidth
                liveSourceHeight = output.sourceHeight
            }

            if (phase == RecordDancePhase.RECORDING && capturedAngles.size < MAX_CUSTOM_FRAMES) {
                capturedAngles.add(angleVector)
                capturedGhostFrames.add(smoothedScreen)

                if (capturedAngles.size >= MAX_CUSTOM_FRAMES) {
                    phase = RecordDancePhase.READY
                    finishAndSaveRecording()
                }
            }
        }
    }

    // Fallback generator la 30 FPS pentru emulatoare fără cameră activă
    var lastCameraFrameTimeMs by remember {
        mutableLongStateOf(android.os.SystemClock.uptimeMillis())
    }
    LaunchedEffect(phase, hasCameraPermission) {
        while (true) {
            delay(33L)
            val now = android.os.SystemClock.uptimeMillis()
            if (!hasCameraPermission || (now - lastCameraFrameTimeMs) > 2500L) {
                val simulated = PoseLandmarkerHelper.generateSimulatedPoseOutput(now)
                processPoseOutput(simulated)
            }
        }
    }

    // Gestionarea numărătorii 3..2..1 și a cronometrului de 20 secunde
    LaunchedEffect(phase) {
        when (phase) {
            RecordDancePhase.COUNTDOWN -> {
                hasSavedRecording.set(false)
                poseSmoother.reset()
                screenSmoother.reset()
                capturedAngles.clear()
                capturedGhostFrames.clear()
                remainingSec = RECORD_DURATION_SEC
                for (cnt in 3 downTo 1) {
                    countdownSec = cnt
                    metronome.playCountdownBeep(isGoSignal = false)
                    delay(1_000L)
                }
                phase = RecordDancePhase.RECORDING
            }
            RecordDancePhase.RECORDING -> {
                metronome.playCountdownBeep(isGoSignal = true)
                for (sec in RECORD_DURATION_SEC downTo 1) {
                    remainingSec = sec
                    delay(1_000L)
                    if (phase != RecordDancePhase.RECORDING) return@LaunchedEffect
                }
                remainingSec = 0
                if (phase == RecordDancePhase.RECORDING) {
                    phase = RecordDancePhase.READY
                    finishAndSaveRecording()
                }
            }
            RecordDancePhase.READY -> Unit
        }
    }

    // Pulsul ritmic de metronom în timpul înregistrării
    LaunchedEffect(phase, selectedBpm) {
        if (phase == RecordDancePhase.RECORDING) {
            val intervalMs = RhythmMetronomeHelper.bpmToIntervalMs(selectedBpm)
            var beatIdx = 0
            while (true) {
                delay(intervalMs)
                metronome.playMetronomeBeat(isAccent = (beatIdx % 4 == 0))
                beatIdx++
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. CameraX PreviewView
        if (hasCameraPermission) {
            val previewView = remember {
                PreviewView(context).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            }
            val cameraSessionManager = remember { CameraSessionManager(context) }

            DisposableEffect(lifecycleOwner, previewView) {
                val analyzer = PoseAnalyzer.create(
                    context = context,
                    isFrontCamera = true,
                    onPoseFrame = {},
                    onPoseOutput = { output ->
                        val now = android.os.SystemClock.uptimeMillis()
                        runOnMainThread {
                            lastCameraFrameTimeMs = now
                        }
                        processPoseOutput(output)
                    }
                )
                cameraSessionManager.startCamera(
                    lifecycleOwner = lifecycleOwner,
                    previewView = previewView,
                    poseAnalyzer = analyzer,
                    useFrontCamera = true
                )
                onDispose {
                    cameraSessionManager.shutdown()
                }
            }

            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 2. Scheletul utilizatorului în timp real
        StickmanOverlayCanvas(
            landmarks = liveLandmarks,
            isFormAccurate = true,
            jointVisibilities = liveVisibilities,
            sourceWidth = liveSourceWidth,
            sourceHeight = liveSourceHeight,
            modifier = Modifier.fillMaxSize()
        )

        // 3. HUD-ul de înregistrare
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Partea de sus: Configurare (în READY) sau Status Înregistrare + Framing Banner
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Black.copy(alpha = 0.78f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🎥 Creează-ți Propriul Dans",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Surface(
                                color = if (phase == RecordDancePhase.RECORDING) {
                                    Color(0xFFD32F2F)
                                } else {
                                    Color(0xFF00897B)
                                },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    text = if (phase == RecordDancePhase.RECORDING) {
                                        "● REC ${remainingSec}s"
                                    } else {
                                        "$selectedBpm BPM"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }

                        if (phase == RecordDancePhase.READY) {
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(
                                value = danceTitle,
                                onValueChange = { danceTitle = it },
                                label = { Text("Numele dansului") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF00E5FF),
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                                    focusedLabelColor = Color(0xFF00E5FF),
                                    unfocusedLabelColor = Color.White.copy(alpha = 0.7f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Alege ritmul metronomului (BPM):",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF80CBC4)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                PRESET_BPMS.forEach { bpmOption ->
                                    FilterChip(
                                        selected = selectedBpm == bpmOption,
                                        onClick = { selectedBpm = bpmOption },
                                        label = {
                                            Text(
                                                text = "$bpmOption BPM",
                                                fontWeight = FontWeight.Bold
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFF00B8D4),
                                            selectedLabelColor = Color.Black,
                                            containerColor = Color.DarkGray.copy(alpha = 0.6f),
                                            labelColor = Color.White
                                        )
                                    )
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.height(10.dp))
                            val progress = (capturedAngles.size.toFloat() / MAX_CUSTOM_FRAMES.toFloat())
                                .coerceIn(0f, 1f)
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                color = Color(0xFF00E676),
                                trackColor = Color.White.copy(alpha = 0.25f)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Cadre 3D + Ghost capturate: ${capturedAngles.size} / $MAX_CUSTOM_FRAMES",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White
                            )
                        }
                    }
                }

                // Avertizare Full-Body Framing în faza READY
                if (phase == RecordDancePhase.READY && framingHint != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFAB00).copy(alpha = 0.92f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "⚠️ $framingHint",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1B1B1B),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
            }

            // Numărătoarea inversă centrală 3..2..1
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (phase == RecordDancePhase.COUNTDOWN) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.78f),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Text(
                            text = countdownSec.toString(),
                            style = MaterialTheme.typography.displayLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF00E5FF),
                            modifier = Modifier.padding(horizontal = 40.dp, vertical = 20.dp)
                        )
                    }
                }
            }

            // Footer: Butoane Înapoi / Înregistrează / Oprește & Salvează
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.78f)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            phase = RecordDancePhase.READY
                            onBack()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Înapoi", color = Color.White)
                    }

                    if (phase == RecordDancePhase.READY) {
                        Button(
                            onClick = { phase = RecordDancePhase.COUNTDOWN },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFD32F2F)
                            ),
                            modifier = Modifier.weight(2f)
                        ) {
                            Text(
                                text = "🔴 Înregistrează Coregrafia (20s)",
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    } else if (phase == RecordDancePhase.RECORDING) {
                        val canSave = capturedAngles.size >= MIN_FRAMES_TO_SAVE
                        Button(
                            onClick = {
                                phase = RecordDancePhase.READY
                                finishAndSaveRecording()
                            },
                            enabled = canSave,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00C853)
                            ),
                            modifier = Modifier.weight(2f)
                        ) {
                            Text(
                                text = if (canSave) {
                                    "Oprește & Salvează (${capturedAngles.size})"
                                } else {
                                    "Minim $MIN_FRAMES_TO_SAVE cadre..."
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
