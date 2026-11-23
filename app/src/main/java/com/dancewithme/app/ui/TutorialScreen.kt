package com.dancewithme.app.ui

import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dancewithme.app.data.DefaultDanceRepository
import com.dancewithme.app.data.Point3D
import com.dancewithme.app.data.ReferenceDance
import kotlinx.coroutines.delay
import java.io.File

/**
 * Ecranul de învățare a coregrafiei (`TutorialScreen`) plasat între `MainMenuScreen` și `CalibrationScreen`.
 *
 * Oferă:
 * - Titlul și descrierea pas cu pas a coregrafiei (`dance.description`).
 * - Previzualizare vizuală interactivă:
 *   - Redare Video în buclă dacă există fișierul în `assets/videos/{videoAssetName}`.
 *   - Animație 3D la 30 FPS pe `Canvas` a scheletului de referință (`dance.ghostFrames`), cu
 *     buton de **Play / Pause** și selector de viteză **0.5x (Slow-Motion) / 1.0x (Normal)**.
 * - Buton principal **„Am înțeles — Calibrează Telefonul”** și buton **„Înapoi”**.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorialScreen(
    dance: ReferenceDance,
    onProceedToCalibration: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Verificăm dacă există un videoclip real în assets/videos/{videoAssetName}
    val cachedVideoFile: File? = remember(context, dance.videoAssetName) {
        val assetName = dance.videoAssetName
        if (assetName.isNullOrBlank()) {
            null
        } else {
            runCatching {
                val availableVideos = context.assets.list("videos").orEmpty()
                if (assetName in availableVideos) {
                    val outFile = File(context.cacheDir, "tutorial_$assetName")
                    if (!outFile.exists() || outFile.length() == 0L) {
                        context.assets.open("videos/$assetName").use { input ->
                            outFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    outFile
                } else {
                    null
                }
            }.getOrNull()
        }
    }

    var showVideoTab by remember(cachedVideoFile) {
        mutableStateOf(cachedVideoFile != null)
    }

    // Fallback la ghostFrames built-in dacă JSON-ul încărcat nu avea ghostFrames
    val ghostFrames: List<List<Point3D>> = remember(dance) {
        if (dance.ghostFrames.isNotEmpty()) {
            dance.ghostFrames
        } else {
            DefaultDanceRepository.builtInReferenceDances()
                .find { it.id == dance.id }
                ?.ghostFrames
                ?: DefaultDanceRepository.builtInReferenceDances().first().ghostFrames
        }
    }

    var isPlaying by remember { mutableStateOf(true) }
    var isSlowMotion by remember { mutableStateOf(false) } // false = 1.0x (30 FPS), true = 0.5x (15 FPS)
    var currentFrameIndex by remember(dance.id) { mutableIntStateOf(0) }

    // Loop de animație la 30 FPS (33ms) pentru 1.0x sau 15 FPS (66ms) pentru 0.5x Slow-Motion
    LaunchedEffect(isPlaying, isSlowMotion, ghostFrames.size, showVideoTab) {
        if (ghostFrames.isEmpty() || showVideoTab) return@LaunchedEffect
        val frameDelayMs = if (isSlowMotion) 66L else 33L
        while (isPlaying) {
            delay(frameDelayMs)
            currentFrameIndex = (currentFrameIndex + 1) % ghostFrames.size
        }
    }

    val effectiveDescription = dance.description.ifBlank {
        "Urmărește mișcarea scheletului de referință și aliniază-ți brațele și genunchii în același ritm."
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Învață Dansul: ${dance.title}") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Card Descriere Pași
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "📖 Cum se execută (${dance.durationSec} secunde):",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = effectiveDescription,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // Tab-uri de comutare Video vs. Schelet 3D
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !showVideoTab,
                    onClick = { showVideoTab = false },
                    label = { Text("🦴 Schelet 3D (600 Cadre)") }
                )
                if (cachedVideoFile != null) {
                    FilterChip(
                        selected = showVideoTab,
                        onClick = { showVideoTab = true },
                        label = { Text("🎬 Video Demonstrativ") }
                    )
                }
            }

            // Zona de Previzualizare Vizuală (Video sau Animație Ghost Skeleton 30 FPS)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(310.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF0D1B2A)),
                contentAlignment = Alignment.Center
            ) {
                if (showVideoTab && cachedVideoFile != null) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoPath(cachedVideoFile.absolutePath)
                                setOnPreparedListener { mediaPlayer ->
                                    mediaPlayer.isLooping = true
                                    start()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    val currentLandmarks = ghostFrames.getOrElse(currentFrameIndex) { emptyList() }
                    GhostStickmanOverlayCanvas(
                        landmarks = currentLandmarks,
                        alpha = 0.90f,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Badge colț stânga-sus cu starea vitezei și timpul curent
                    Surface(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                    ) {
                        val currentSec = currentFrameIndex / 30
                        Text(
                            text = "👻 Demonstrație 3D • ${currentSec}s / ${dance.durationSec}s",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF00E5FF),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // Controale Play/Pause & Viteză 0.5x / 1.0x pentru Scheletul 3D
            if (!showVideoTab && ghostFrames.isNotEmpty()) {
                val progress = (currentFrameIndex.toFloat() / ghostFrames.size.coerceAtLeast(1).toFloat())
                    .coerceIn(0f, 1f)

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = Color(0xFF00B8D4)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { isPlaying = !isPlaying },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (isPlaying) "⏸ Pauză" else "▶ Redă",
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    OutlinedButton(
                        onClick = { isSlowMotion = !isSlowMotion },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (isSlowMotion) "🐢 Viteză: 0.5x (Slow)" else "⚡ Viteză: 1.0x (Normal)",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Butonul Principal: Am înțeles — Calibrează Telefonul
            Button(
                onClick = onProceedToCalibration,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Am înțeles — Calibrează Telefonul",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Înapoi")
            }
        }
    }
}
