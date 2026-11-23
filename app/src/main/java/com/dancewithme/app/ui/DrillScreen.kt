package com.dancewithme.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dancewithme.app.ai.PoseExtractionOutput
import com.dancewithme.app.ai.PoseLandmarkerHelper
import com.dancewithme.app.audio.RhythmMetronomeHelper
import com.dancewithme.app.audio.VoskVoiceCommandManager
import com.dancewithme.app.camera.CameraSessionManager
import com.dancewithme.app.camera.PoseAnalyzer
import com.dancewithme.app.data.AngleVector
import com.dancewithme.app.data.Point3D
import com.dancewithme.app.data.PoseFrame
import com.dancewithme.app.sensors.ProximitySensorManager
import kotlinx.coroutines.delay

/**
 * Ecranul de antrenament (`DrillScreen`):
 * - Menține ecranul pornit (`keepScreenOn = true`) pe toată durata sesiunii hands-free.
 * - Cere permisiunile `CAMERA` și `RECORD_AUDIO` (pentru Vosk).
 * - Pornește `VoskVoiceCommandManager` și `ProximitySensorManager` în faza `READY` pentru a detecta
 *   comanda vocală offline `"Start"` sau trecerea mâinii peste senzorul de proximitate.
 * - Randează `CameraPreview` (`PreviewView` din CameraX).
 * - Randează opțional `GhostStickmanOverlayCanvas` (scheletul de referință semi-transparent Cyan `#00E5FF`,
 *   `alpha = 0.45f`) sub scheletul utilizatorului pentru ghidaj vizual în timp real.
 * - Randează un `Canvas` peste cameră care desenează în timp real scheletul (*stickman*-ul) utilizatorului
 *   cu **fiecare membru colorat independent** în **VERDE** (când unghiul acelui membru este corect)
 *   sau **ROȘU** (când membrul depășește pragul de eroare).
 * - Afișează un badge de status al motorului AI (`AI: MediaPipe LIVE` vs. `AI: Simulare`), un buton toggle
 *   pentru ghidul fantomă (`👻 Ghid: ON / OFF`), numărătoarea inversă de 20 de secunde și progresul bufferului de 600 de cadre.
 */
@Composable
fun DrillScreen(
    uiState: DrillUiState,
    onPoseOutput: (PoseExtractionOutput) -> Unit,
    onStartDrill: () -> Unit,
    onStopAndEvaluate: () -> Unit,
    onBack: () -> Unit,
    onToggleGhostOverlay: () -> Unit = {},
    onToggleMetronome: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentView = LocalView.current

    // Menținem ecranul deschis pe durata antrenamentului hands-free
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

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val voskManager = remember(context) {
        VoskVoiceCommandManager(
            context = context,
            onStartCommandDetected = onStartDrill
        )
    }
    val voiceStatusLabel by voskManager.statusMessage.collectAsStateWithLifecycle()

    // Senzorul de proximitate ca declanșator hands-free alternativ (trecerea mâinii pe deasupra telefonului)
    val proximitySensorManager = remember(context) {
        ProximitySensorManager(
            context = context,
            onHandWaveDetected = onStartDrill
        )
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasCameraPermission = result[Manifest.permission.CAMERA] ?: hasCameraPermission
        hasAudioPermission = result[Manifest.permission.RECORD_AUDIO] ?: hasAudioPermission
        if (hasAudioPermission && uiState.phase == DrillPhase.READY) {
            voskManager.startListening()
        }
    }

    LaunchedEffect(Unit) {
        val needed = mutableListOf<String>()
        if (!hasCameraPermission) needed.add(Manifest.permission.CAMERA)
        if (!hasAudioPermission) needed.add(Manifest.permission.RECORD_AUDIO)
        if (needed.isNotEmpty()) {
            permissionsLauncher.launch(needed.toTypedArray())
        }
    }

    // Gestionăm ciclul de viață al recunoașterii vocale Vosk și al Senzorului de Proximitate:
    // Active doar în faza READY și oprite automat când începe dansul sau la părăsirea ecranului.
    DisposableEffect(voskManager, proximitySensorManager, uiState.phase, hasAudioPermission) {
        if (uiState.phase == DrillPhase.READY) {
            voskManager.startListening()
            proximitySensorManager.startListening()
        } else {
            voskManager.stopListening()
            proximitySensorManager.stopListening()
        }
        onDispose {
            voskManager.stopListening()
            proximitySensorManager.stopListening()
        }
    }

    DisposableEffect(voskManager) {
        onDispose {
            voskManager.shutdown()
        }
    }

    // Metronom audio (BPM) și semnale sonore 3-2-1 + GO
    val metronome = remember { RhythmMetronomeHelper() }
    DisposableEffect(metronome) {
        onDispose {
            metronome.release()
        }
    }

    // Bip-uri sonore la numărătoarea inversă 3-2-1 înainte de start
    LaunchedEffect(uiState.phase, uiState.preDrillCountdownSec, uiState.isMetronomeEnabled) {
        if (
            uiState.isMetronomeEnabled &&
            uiState.phase == DrillPhase.COUNTDOWN_TO_START &&
            uiState.preDrillCountdownSec > 0
        ) {
            metronome.playCountdownBeep(isGoSignal = false)
        }
    }

    // Semnal sonor GO la intrarea în RECORDING + puls ritmic sincronizat cu BPM-ul dansului
    LaunchedEffect(uiState.phase, uiState.isMetronomeEnabled, uiState.selectedDance?.bpm) {
        if (uiState.phase == DrillPhase.RECORDING && uiState.isMetronomeEnabled) {
            metronome.playCountdownBeep(isGoSignal = true)
            val bpm = uiState.selectedDance?.bpm ?: 90
            val intervalMs = RhythmMetronomeHelper.bpmToIntervalMs(bpm)
            var beatIndex = 0
            while (true) {
                delay(intervalMs)
                metronome.playMetronomeBeat(isAccent = (beatIndex % 4 == 0))
                beatIndex++
            }
        }
    }

    // Fallback generator la 30 FPS: așteaptă 2500ms la inițializarea GPU-ului MediaPipe pe telefonul real
    // înainte de a presupune că rulăm pe un emulator fără cameră activă.
    var lastCameraFrameTimeMs by remember {
        mutableLongStateOf(android.os.SystemClock.uptimeMillis())
    }
    LaunchedEffect(uiState.phase, hasCameraPermission) {
        while (true) {
            delay(33L) // ~30 FPS
            val now = android.os.SystemClock.uptimeMillis()
            if (!hasCameraPermission || (now - lastCameraFrameTimeMs) > 2500L) {
                val simulated = PoseLandmarkerHelper.generateSimulatedPoseOutput(now)
                onPoseOutput(simulated)
            }
        }
    }

    val hudPromptText = if (uiState.phase == DrillPhase.READY) {
        "Spune START, treci mâna peste senzor sau apasă butonul"
    } else {
        voiceStatusLabel
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. Stratul de bază: CameraX PreviewView
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
                        lastCameraFrameTimeMs = android.os.SystemClock.uptimeMillis()
                        onPoseOutput(output)
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

        // 2A. Stratul Ghost Skeleton (scheletul de referință semi-transparent Cyan #00E5FF) desenat SUB scheletul utilizatorului
        if (uiState.showGhostOverlay && uiState.currentGhostLandmarks.size >= PoseFrame.REQUIRED_LANDMARKS_COUNT) {
            GhostStickmanOverlayCanvas(
                landmarks = uiState.currentGhostLandmarks,
                alpha = 0.45f,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 2B. Stratul Stickman-ului utilizatorului (colorare independentă Verde/Roșu/Gri pe fiecare membru)
        StickmanOverlayCanvas(
            landmarks = uiState.liveNormalizedLandmarks,
            isFormAccurate = uiState.isLiveFormAccurate,
            jointAccuracies = uiState.liveJointAccuracies,
            jointVisibilities = uiState.liveJointVisibilities,
            sourceWidth = uiState.sourceFrameWidth,
            sourceHeight = uiState.sourceFrameHeight,
            modifier = Modifier.fillMaxSize()
        )

        // 3. Stratul superior (HUD): Numărătoare inversă, Progres 600 cadre și Butoane de control + Vosk + Proximitate
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Grupul superior HUD: Header HUD + Banner Avertizare Încadrare (dacă este cazul în faza READY)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
            // Header HUD
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.72f)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Rândul 1: Titlul dansului în stânga + Cronometrul (20s) în dreapta
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = uiState.selectedDance?.title ?: "Antrenament Dans",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp)
                        )

                        // Badge Timp Rămas (20s -> 0s)
                        Surface(
                            color = if (uiState.phase == DrillPhase.RECORDING) {
                                Color(0xFFD32F2F)
                            } else {
                                Color(0xFF1976D2)
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "${uiState.remainingSeconds}s",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Rândul 2: Badge-urile AI Engine, Ghost Skeleton Toggle și Metronom BPM așezate cu FlowRow
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Badge status AI Engine (MediaPipe LIVE vs. Simulare)
                        Surface(
                            color = if (!uiState.isSimulationMode) {
                                Color(0xFF00C853)
                            } else {
                                Color(0xFFFF6D00)
                            },
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = if (!uiState.isSimulationMode) {
                                    "AI: MediaPipe LIVE"
                                } else {
                                    "AI: Simulare"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        // Buton Toggle Ghost Skeleton Overlay (👻 Ghid: ON / OFF)
                        Surface(
                            color = if (uiState.showGhostOverlay) {
                                Color(0xFF00B8D4)
                            } else {
                                Color(0xFF455A64)
                            },
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.clickable { onToggleGhostOverlay() }
                        ) {
                            Text(
                                text = if (uiState.showGhostOverlay) {
                                    "👻 Ghid: ON"
                                } else {
                                    "👻 Ghid: OFF"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        // Buton Toggle Metronom Audio BPM (🎵 Ritm: ON / 🔇 Ritm: OFF)
                        Surface(
                            color = if (uiState.isMetronomeEnabled) {
                                Color(0xFF00897B)
                            } else {
                                Color(0xFF455A64)
                            },
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.clickable { onToggleMetronome() }
                        ) {
                            Text(
                                text = if (uiState.isMetronomeEnabled) {
                                    "🎵 Ritm: ON"
                                } else {
                                    "🔇 Ritm: OFF"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Rândul 3: Mesajul de status hands-free
                    Text(
                        text = "🎤🖐️ $hudPromptText",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF80CBC4)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Bara de progres pentru bufferul de 600 de cadre (20s * 30 FPS)
                    val frameProgress = (uiState.capturedFramesCount.toFloat() / uiState.maxFramesCapacity.toFloat())
                        .coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { frameProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = if (uiState.isLiveFormAccurate) Color(0xFF00E676) else Color(0xFFFF5252),
                        trackColor = Color.White.copy(alpha = 0.25f)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Cadre: ${uiState.capturedFramesCount} / ${uiState.maxFramesCapacity}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White
                        )
                        Text(
                            text = if (uiState.isLiveFormAccurate) {
                                "Postură: CORECTĂ (Verde)"
                            } else {
                                "Postură: CORECTEAZĂ MEMBRELE ROȘII"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (uiState.isLiveFormAccurate) Color(0xFF00E676) else Color(0xFFFF5252)
                        )
                    }
                }
            }

            // Banner de Avertizare Încadrare (Full-Body Framing Check) afișat în faza READY dacă utilizatorul nu este complet în cadru
            if (uiState.phase == DrillPhase.READY && uiState.framingHint != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFAB00).copy(alpha = 0.92f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "⚠️ ${uiState.framingHint}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1B1B1B),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
            }

            // Indicator central dacă suntem în numărătoarea 3..2..1 sau în calculul DTW
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                when (uiState.phase) {
                    DrillPhase.COUNTDOWN_TO_START -> {
                        Surface(
                            color = Color.Black.copy(alpha = 0.75f),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Text(
                                text = uiState.preDrillCountdownSec.toString(),
                                style = MaterialTheme.typography.displayLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF00E676),
                                modifier = Modifier.padding(horizontal = 40.dp, vertical = 20.dp)
                            )
                        }
                    }
                    DrillPhase.EVALUATING -> {
                        Surface(
                            color = Color.Black.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                CircularProgressIndicator(color = Color(0xFF00E676))
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Se calculează scorul DTW...",
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                    }
                    else -> Unit
                }
            }

            // Footer: Butoane de Start / Comandă Vocală Vosk / Evaluare rapidă / Înapoi
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.72f)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (uiState.phase == DrillPhase.READY) {
                        OutlinedButton(
                            onClick = { voskManager.triggerSimulatedVoiceStart() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "🎤 Simulează Comanda Vocală Vosk: „START”",
                                color = Color(0xFF80CBC4),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onBack,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Înapoi", color = Color.White)
                        }

                        if (uiState.phase == DrillPhase.READY) {
                            Button(
                                onClick = onStartDrill,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF00C853)
                                ),
                                modifier = Modifier.weight(2f)
                            ) {
                                Text(
                                    text = "START DANS (20s)",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else if (uiState.phase == DrillPhase.RECORDING) {
                            Button(
                                onClick = onStopAndEvaluate,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFF6D00)
                                ),
                                modifier = Modifier.weight(2f)
                            ) {
                                Text(
                                    text = "Finalizează & Calculează DTW",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Desenează "Ghost Skeleton"-ul de referință (culoare Cyan `#00E5FF`, semi-transparent cu [alpha] = `0.45f`
 * pe cameră sau `0.90f` în `TutorialScreen`), ca ghid vizual peste care utilizatorul își aliniază corpul.
 */
@Composable
fun GhostStickmanOverlayCanvas(
    landmarks: List<Point3D>,
    alpha: Float = 0.45f,
    modifier: Modifier = Modifier
) {
    val ghostCyan = Color(0xFF00E5FF).copy(alpha = alpha)
    val ghostJointFill = Color.White.copy(alpha = (alpha + 0.15f).coerceAtMost(1f))

    Canvas(modifier = modifier) {
        if (landmarks.size < PoseFrame.REQUIRED_LANDMARKS_COUNT) return@Canvas

        fun Point3D.toScreenOffset(): Offset = Offset(
            x = x.coerceIn(0f, 1f) * size.width,
            y = y.coerceIn(0f, 1f) * size.height
        )

        val leftShoulder = landmarks[PoseFrame.LEFT_SHOULDER].toScreenOffset()
        val rightShoulder = landmarks[PoseFrame.RIGHT_SHOULDER].toScreenOffset()
        val shoulderMid = Offset(
            x = (leftShoulder.x + rightShoulder.x) * 0.5f,
            y = (leftShoulder.y + rightShoulder.y) * 0.5f
        )
        val headCenter = landmarks[PoseFrame.NOSE].toScreenOffset()

        // Gât și contur cap fantomă
        drawLine(
            color = ghostCyan,
            start = shoulderMid,
            end = headCenter,
            strokeWidth = 8.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawCircle(
            color = ghostCyan,
            radius = 23.dp.toPx(),
            center = headCenter,
            style = Stroke(width = 5.dp.toPx())
        )

        val connections = listOf(
            PoseFrame.LEFT_SHOULDER to PoseFrame.RIGHT_SHOULDER,
            PoseFrame.LEFT_SHOULDER to PoseFrame.LEFT_HIP,
            PoseFrame.RIGHT_SHOULDER to PoseFrame.RIGHT_HIP,
            PoseFrame.LEFT_HIP to PoseFrame.RIGHT_HIP,
            PoseFrame.LEFT_SHOULDER to PoseFrame.LEFT_ELBOW,
            PoseFrame.LEFT_ELBOW to PoseFrame.LEFT_WRIST,
            PoseFrame.RIGHT_SHOULDER to PoseFrame.RIGHT_ELBOW,
            PoseFrame.RIGHT_ELBOW to PoseFrame.RIGHT_WRIST,
            PoseFrame.LEFT_HIP to PoseFrame.LEFT_KNEE,
            PoseFrame.LEFT_KNEE to PoseFrame.LEFT_ANKLE,
            PoseFrame.RIGHT_HIP to PoseFrame.RIGHT_KNEE,
            PoseFrame.RIGHT_KNEE to PoseFrame.RIGHT_ANKLE
        )

        for ((startIdx, endIdx) in connections) {
            drawLine(
                color = ghostCyan,
                start = landmarks[startIdx].toScreenOffset(),
                end = landmarks[endIdx].toScreenOffset(),
                strokeWidth = 10.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        val joints = listOf(
            PoseFrame.LEFT_SHOULDER,
            PoseFrame.RIGHT_SHOULDER,
            PoseFrame.LEFT_ELBOW,
            PoseFrame.RIGHT_ELBOW,
            PoseFrame.LEFT_WRIST,
            PoseFrame.RIGHT_WRIST,
            PoseFrame.LEFT_HIP,
            PoseFrame.RIGHT_HIP,
            PoseFrame.LEFT_KNEE,
            PoseFrame.RIGHT_KNEE,
            PoseFrame.LEFT_ANKLE,
            PoseFrame.RIGHT_ANKLE
        )

        for (idx in joints) {
            val offset = landmarks[idx].toScreenOffset()
            drawCircle(
                color = ghostCyan,
                radius = 10.dp.toPx(),
                center = offset
            )
            drawCircle(
                color = ghostJointFill,
                radius = 4.dp.toPx(),
                center = offset
            )
        }
    }
}

/**
 * Desenează scheletul (Stickman-ul) utilizatorului peste fluxul video folosind coordonatele normalizate [0..1],
 * colorând independent fiecare membru în funcție de [jointAccuracies] (8 articulații):
 * - Picior stâng (șold-genunchi-gleznă) -> `JOINT_LEFT_HIP` & `JOINT_LEFT_KNEE`
 * - Picior drept (șold-genunchi-gleznă) -> `JOINT_RIGHT_HIP` & `JOINT_RIGHT_KNEE`
 * - Braț stâng superior (umăr-cot) & umăr -> `JOINT_LEFT_SHOULDER`
 * - Antebraț stâng (cot-încheietură) & cot -> `JOINT_LEFT_ELBOW`
 * - Braț drept superior (umăr-cot) & umăr -> `JOINT_RIGHT_SHOULDER`
 * - Antebraț drept (cot-încheietură) & cot -> `JOINT_RIGHT_ELBOW`
 * - Trunchiul și capul sunt verzi dacă majoritatea articulațiilor sunt corecte ([isFormAccurate]), altfel roșii.
 */
@Composable
fun StickmanOverlayCanvas(
    landmarks: List<Point3D>,
    isFormAccurate: Boolean,
    jointAccuracies: Map<String, Boolean> = emptyMap(),
    jointVisibilities: Map<String, Boolean> = emptyMap(),
    sourceWidth: Int = 480,
    sourceHeight: Int = 640,
    modifier: Modifier = Modifier
) {
    val colorGreen = Color(0xFF00E676)
    val colorRed = Color(0xFFFF1744)
    val colorOccluded = Color.Gray.copy(alpha = 0.55f)
    val jointCenterColor = Color.White

    fun limbColor(isAccurate: Boolean, isVisible: Boolean = true): Color = when {
        !isVisible -> colorOccluded
        isAccurate -> colorGreen
        else -> colorRed
    }

    val leftShoulderOk = jointAccuracies[AngleVector.JOINT_LEFT_SHOULDER] ?: isFormAccurate
    val rightShoulderOk = jointAccuracies[AngleVector.JOINT_RIGHT_SHOULDER] ?: isFormAccurate
    val leftElbowOk = jointAccuracies[AngleVector.JOINT_LEFT_ELBOW] ?: isFormAccurate
    val rightElbowOk = jointAccuracies[AngleVector.JOINT_RIGHT_ELBOW] ?: isFormAccurate
    val leftHipOk = jointAccuracies[AngleVector.JOINT_LEFT_HIP] ?: isFormAccurate
    val rightHipOk = jointAccuracies[AngleVector.JOINT_RIGHT_HIP] ?: isFormAccurate
    val leftKneeOk = jointAccuracies[AngleVector.JOINT_LEFT_KNEE] ?: isFormAccurate
    val rightKneeOk = jointAccuracies[AngleVector.JOINT_RIGHT_KNEE] ?: isFormAccurate

    val leftShoulderVis = jointVisibilities[AngleVector.JOINT_LEFT_SHOULDER] ?: true
    val rightShoulderVis = jointVisibilities[AngleVector.JOINT_RIGHT_SHOULDER] ?: true
    val leftElbowVis = jointVisibilities[AngleVector.JOINT_LEFT_ELBOW] ?: true
    val rightElbowVis = jointVisibilities[AngleVector.JOINT_RIGHT_ELBOW] ?: true
    val leftHipVis = jointVisibilities[AngleVector.JOINT_LEFT_HIP] ?: true
    val rightHipVis = jointVisibilities[AngleVector.JOINT_RIGHT_HIP] ?: true
    val leftKneeVis = jointVisibilities[AngleVector.JOINT_LEFT_KNEE] ?: true
    val rightKneeVis = jointVisibilities[AngleVector.JOINT_RIGHT_KNEE] ?: true

    // Trunchiul și capul rămân verzi dacă majoritatea articulațiilor sunt corecte
    val torsoAccurate = if (jointAccuracies.isNotEmpty()) {
        jointAccuracies.values.count { it } >= (AngleVector.JOINT_COUNT / 2)
    } else {
        isFormAccurate
    }
    val torsoVisible = (leftShoulderVis && rightShoulderVis) || (leftHipVis && rightHipVis)

    val torsoColor = limbColor(torsoAccurate, torsoVisible)
    val leftUpperArmColor = limbColor(leftShoulderOk, leftShoulderVis)
    val rightUpperArmColor = limbColor(rightShoulderOk, rightShoulderVis)
    val leftForearmColor = limbColor(leftElbowOk, leftElbowVis)
    val rightForearmColor = limbColor(rightElbowOk, rightElbowVis)
    val leftThighColor = limbColor(leftHipOk && leftKneeOk, leftHipVis && leftKneeVis)
    val leftShinColor = limbColor(leftKneeOk, leftKneeVis)
    val rightThighColor = limbColor(rightHipOk && rightKneeOk, rightHipVis && rightKneeVis)
    val rightShinColor = limbColor(rightKneeOk, rightKneeVis)

    Canvas(modifier = modifier) {
        if (landmarks.size < PoseFrame.REQUIRED_LANDMARKS_COUNT) return@Canvas

        fun Point3D.toScreenOffset(): Offset {
            val (px, py) = CoordinateMapper.mapFillCenter(
                normX = x.coerceIn(0f, 1f),
                normY = y.coerceIn(0f, 1f),
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                viewWidth = size.width,
                viewHeight = size.height
            )
            return Offset(x = px, y = py)
        }

        // Desenăm gâtul și capul (de la mijlocul umerilor la nas)
        val leftShoulder = landmarks[PoseFrame.LEFT_SHOULDER].toScreenOffset()
        val rightShoulder = landmarks[PoseFrame.RIGHT_SHOULDER].toScreenOffset()
        val shoulderMid = Offset(
            x = (leftShoulder.x + rightShoulder.x) * 0.5f,
            y = (leftShoulder.y + rightShoulder.y) * 0.5f
        )
        val headCenter = landmarks[PoseFrame.NOSE].toScreenOffset()

        drawLine(
            color = torsoColor,
            start = shoulderMid,
            end = headCenter,
            strokeWidth = 6.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawCircle(
            color = torsoColor,
            radius = 22.dp.toPx(),
            center = headCenter
        )

        // Conexiunile anatomice cu culoarea specifică fiecărui membru
        val coloredConnections = listOf(
            // Trunchi
            Triple(PoseFrame.LEFT_SHOULDER, PoseFrame.RIGHT_SHOULDER, torsoColor),
            Triple(PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_HIP, torsoColor),
            Triple(PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_HIP, torsoColor),
            Triple(PoseFrame.LEFT_HIP, PoseFrame.RIGHT_HIP, torsoColor),
            // Brațul stâng (braț superior după umăr, antebraț după cot)
            Triple(PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_ELBOW, leftUpperArmColor),
            Triple(PoseFrame.LEFT_ELBOW, PoseFrame.LEFT_WRIST, leftForearmColor),
            // Brațul drept (braț superior după umăr, antebraț după cot)
            Triple(PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_ELBOW, rightUpperArmColor),
            Triple(PoseFrame.RIGHT_ELBOW, PoseFrame.RIGHT_WRIST, rightForearmColor),
            // Piciorul stâng (coapsă + gambă)
            Triple(PoseFrame.LEFT_HIP, PoseFrame.LEFT_KNEE, leftThighColor),
            Triple(PoseFrame.LEFT_KNEE, PoseFrame.LEFT_ANKLE, leftShinColor),
            // Piciorul drept (coapsă + gambă)
            Triple(PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_KNEE, rightThighColor),
            Triple(PoseFrame.RIGHT_KNEE, PoseFrame.RIGHT_ANKLE, rightShinColor)
        )

        for ((startIdx, endIdx, segmentColor) in coloredConnections) {
            val startPt = landmarks[startIdx]
            val endPt = landmarks[endIdx]
            drawLine(
                color = segmentColor,
                start = startPt.toScreenOffset(),
                end = endPt.toScreenOffset(),
                strokeWidth = 7.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // Desenăm articulațiile principale cu culoarea asociată fiecărei încheieturi
        val coloredJoints = listOf(
            PoseFrame.LEFT_SHOULDER to leftUpperArmColor,
            PoseFrame.RIGHT_SHOULDER to rightUpperArmColor,
            PoseFrame.LEFT_ELBOW to leftForearmColor,
            PoseFrame.RIGHT_ELBOW to rightForearmColor,
            PoseFrame.LEFT_WRIST to leftForearmColor,
            PoseFrame.RIGHT_WRIST to rightForearmColor,
            PoseFrame.LEFT_HIP to limbColor(leftHipOk, leftHipVis),
            PoseFrame.RIGHT_HIP to limbColor(rightHipOk, rightHipVis),
            PoseFrame.LEFT_KNEE to leftShinColor,
            PoseFrame.RIGHT_KNEE to rightShinColor,
            PoseFrame.LEFT_ANKLE to leftShinColor,
            PoseFrame.RIGHT_ANKLE to rightShinColor
        )

        for ((idx, jointRingColor) in coloredJoints) {
            val offset = landmarks[idx].toScreenOffset()
            drawCircle(
                color = jointRingColor,
                radius = 9.dp.toPx(),
                center = offset
            )
            drawCircle(
                color = jointCenterColor,
                radius = 5.dp.toPx(),
                center = offset
            )
        }
    }
}
