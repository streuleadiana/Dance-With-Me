package com.dancewithme.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dancewithme.app.audio.TtsFeedbackHelper
import com.dancewithme.app.audio.VoskVoiceCommandManager
import com.dancewithme.app.data.AngleVector
import com.dancewithme.app.data.DrillResult
import kotlinx.coroutines.delay

/**
 * Ecranul de rezultate (`ResultScreen`).
 * Afișează:
 * - Scorul total, scorul de formă, scorul de sincronizare (Timing) și badge-ul pentru Nou Record Personal.
 * - Mini-silueta schematică (`MiniErrorSilhouetteCanvas`) cu articulația cea mai problematică evidențiată în roșu.
 * - Cardul cu textul indicației vocale acționabile generat de [TtsFeedbackHelper.buildFeedbackText].
 * - Abaterea medie în grade pentru fiecare articulație.
 * - Butoanele de acțiune: „Repetă”, „Următorul” și „Meniu Principal”, plus control vocal hands-free Vosk („Repeat” / „Next”)
 *   sincronizat astfel încât microfonul pornește abia după ce TTS-ul termină de vorbit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    danceTitle: String,
    result: DrillResult,
    onSpeakResult: (DrillResult, () -> Unit) -> Unit,
    onRetryDrill: () -> Unit,
    onNextDance: () -> Unit = {},
    onBackToMenu: () -> Unit,
    isNewBestScore: Boolean = false,
    bestScore: Float? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentOnRetryDrill by rememberUpdatedState(onRetryDrill)
    val currentOnNextDance by rememberUpdatedState(onNextDance)

    val voskManager = remember(context) {
        VoskVoiceCommandManager(
            context = context,
            onRepeatCommandDetected = { currentOnRetryDrill() },
            onNextCommandDetected = { currentOnNextDance() }
        )
    }

    var isTtsSpeaking by remember { mutableStateOf(true) }
    var isVoskListening by remember { mutableStateOf(false) }
    var speechRequestTrigger by remember { mutableIntStateOf(0) }

    val startVoskAfterTts = remember(voskManager) {
        {
            isTtsSpeaking = false
            if (!isVoskListening) {
                isVoskListening = true
                voskManager.startListening()
            }
        }
    }

    // Eliberăm Vosk la părăsirea ResultScreen (fără a porni ascultarea înainte de finalizarea TTS)
    DisposableEffect(voskManager) {
        onDispose {
            isVoskListening = false
            voskManager.shutdown()
        }
    }

    // Sincronizare TTS -> Vosk:
    // 1. Oprim microfonul Vosk cât timp vorbește TTS-ul.
    // 2. Pornim Vosk imediat ce `onSpeakResult` invocă `onFinished()`.
    // 3. Safety timeout de 8.000 ms în caz că dispozitivul/emulatorul nu are motor TTS activ.
    LaunchedEffect(result, speechRequestTrigger) {
        isTtsSpeaking = true
        if (isVoskListening) {
            isVoskListening = false
            voskManager.stopListening()
        }

        onSpeakResult(result) {
            startVoskAfterTts()
        }

        delay(TTS_SAFETY_TIMEOUT_MS)
        if (isTtsSpeaking) {
            startVoskAfterTts()
        }
    }

    val feedbackText = remember(result) {
        TtsFeedbackHelper.buildFeedbackText(result)
    }

    val worstJointEntry = remember(result) {
        result.jointErrors.maxByOrNull { it.value }
    }
    val highlightedWorstJoint = if (worstJointEntry != null && worstJointEntry.value >= 8.0f) {
        worstJointEntry.key
    } else {
        null
    }

    val scoreColor = when {
        result.totalScore >= 80f -> Color(0xFF2E7D32)
        result.totalScore >= 55f -> Color(0xFFEF6C00)
        else -> Color(0xFFC62828)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Rezultate Antrenament (DTW)") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Card Scor Principal + Mini-siluetă cu membrul greșit marcat
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = danceTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${result.totalScore}%",
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = scoreColor
                            )
                            Text(
                                text = "Scor General DTW",
                                style = MaterialTheme.typography.labelLarge
                            )

                            if (isNewBestScore) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Surface(
                                    color = Color(0xFF00E676),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        text = "🏆 NOU RECORD PERSONAL!",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF003919),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            } else if (bestScore != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Record personal: $bestScore%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Mini-siluetă schematică cu articulația cu cea mai mare eroare marcată în roșu
                        MiniErrorSilhouetteCanvas(
                            highlightedJoint = highlightedWorstJoint,
                            modifier = Modifier
                                .width(100.dp)
                                .height(120.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ScoreMetricColumn(
                            label = "Formă (Unghiuri)",
                            score = result.formScore
                        )
                        ScoreMetricColumn(
                            label = "Timing (Ritm)",
                            score = result.timingScore
                        )
                    }
                }
            }

            // Card cu textul feedback-ului vocal și vizual (Auzi / Vezi)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "🔊 Feedback Antrenor (Auzi / Vezi):",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = feedbackText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = TextAlign.Start
                    )
                }
            }

            // Card Erori pe Articulații
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Abaterea Medie pe Articulații (în grade):",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    result.jointErrors.forEach { (jointName, errorDeg) ->
                        JointErrorRow(jointName = jointName, errorDegrees = errorDeg)
                    }
                }
            }

            // Buton pentru re-ascultarea feedback-ului vocal TTS (oprește Vosk pe durata redării și îl repornește la final)
            OutlinedButton(
                onClick = { speechRequestTrigger++ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Redă din nou Feedback-ul Audio (TTS)")
            }

            // Rândul cu butoanele Repetă și Următorul (conform wireframe-ului)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onRetryDrill,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Repetă")
                }
                Button(
                    onClick = onNextDance,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Următorul")
                }
            }

            OutlinedButton(
                onClick = onBackToMenu,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Meniu Principal")
            }

            Text(
                text = if (isTtsSpeaking) {
                    "🔊 Ascultă feedback-ul..."
                } else {
                    "🎤 Hands-free activ: Spune «Repeat» sau «Next»"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )
        }
    }
}

private const val TTS_SAFETY_TIMEOUT_MS = 8_000L

/**
 * Desenează o mini-siluetă schematică statică (120.dp înălțime) care marchează cu roșu
 * și un cerc de highlight articulația cu cea mai mare abatere unghiulară ([highlightedJoint]).
 */
@Composable
private fun MiniErrorSilhouetteCanvas(
    highlightedJoint: String?,
    modifier: Modifier = Modifier
) {
    val okColor = Color(0xFF2E7D32)
    val errColor = Color(0xFFD32F2F)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val head = Offset(w * 0.50f, h * 0.14f)
        val neck = Offset(w * 0.50f, h * 0.24f)
        val leftShoulder = Offset(w * 0.32f, h * 0.26f)
        val rightShoulder = Offset(w * 0.68f, h * 0.26f)
        val leftElbow = Offset(w * 0.20f, h * 0.42f)
        val rightElbow = Offset(w * 0.80f, h * 0.42f)
        val leftWrist = Offset(w * 0.14f, h * 0.56f)
        val rightWrist = Offset(w * 0.86f, h * 0.56f)
        val leftHip = Offset(w * 0.38f, h * 0.56f)
        val rightHip = Offset(w * 0.62f, h * 0.56f)
        val leftKnee = Offset(w * 0.35f, h * 0.76f)
        val rightKnee = Offset(w * 0.65f, h * 0.76f)
        val leftAnkle = Offset(w * 0.33f, h * 0.94f)
        val rightAnkle = Offset(w * 0.67f, h * 0.94f)

        val isLeftShoulderErr = highlightedJoint == AngleVector.JOINT_LEFT_SHOULDER
        val isRightShoulderErr = highlightedJoint == AngleVector.JOINT_RIGHT_SHOULDER
        val isLeftElbowErr = highlightedJoint == AngleVector.JOINT_LEFT_ELBOW
        val isRightElbowErr = highlightedJoint == AngleVector.JOINT_RIGHT_ELBOW
        val isLeftHipErr = highlightedJoint == AngleVector.JOINT_LEFT_HIP
        val isRightHipErr = highlightedJoint == AngleVector.JOINT_RIGHT_HIP
        val isLeftKneeErr = highlightedJoint == AngleVector.JOINT_LEFT_KNEE
        val isRightKneeErr = highlightedJoint == AngleVector.JOINT_RIGHT_KNEE

        val strokePx = 4.dp.toPx()

        // Cap și trunchi
        drawCircle(color = okColor, radius = 9.dp.toPx(), center = head)
        drawLine(okColor, neck, head, strokePx, StrokeCap.Round)
        drawLine(okColor, leftShoulder, rightShoulder, strokePx, StrokeCap.Round)
        drawLine(okColor, leftShoulder, leftHip, strokePx, StrokeCap.Round)
        drawLine(okColor, rightShoulder, rightHip, strokePx, StrokeCap.Round)
        drawLine(okColor, leftHip, rightHip, strokePx, StrokeCap.Round)

        // Braț stâng (umăr + cot)
        val leftUpperArmCol = if (isLeftShoulderErr || isLeftElbowErr) errColor else okColor
        val leftForearmCol = if (isLeftElbowErr) errColor else okColor
        drawLine(leftUpperArmCol, leftShoulder, leftElbow, strokePx, StrokeCap.Round)
        drawLine(leftForearmCol, leftElbow, leftWrist, strokePx, StrokeCap.Round)

        // Braț drept (umăr + cot)
        val rightUpperArmCol = if (isRightShoulderErr || isRightElbowErr) errColor else okColor
        val rightForearmCol = if (isRightElbowErr) errColor else okColor
        drawLine(rightUpperArmCol, rightShoulder, rightElbow, strokePx, StrokeCap.Round)
        drawLine(rightForearmCol, rightElbow, rightWrist, strokePx, StrokeCap.Round)

        // Picior stâng
        val leftThighCol = if (isLeftHipErr || isLeftKneeErr) errColor else okColor
        val leftShinCol = if (isLeftKneeErr) errColor else okColor
        drawLine(leftThighCol, leftHip, leftKnee, strokePx, StrokeCap.Round)
        drawLine(leftShinCol, leftKnee, leftAnkle, strokePx, StrokeCap.Round)

        // Picior drept
        val rightThighCol = if (isRightHipErr || isRightKneeErr) errColor else okColor
        val rightShinCol = if (isRightKneeErr) errColor else okColor
        drawLine(rightThighCol, rightHip, rightKnee, strokePx, StrokeCap.Round)
        drawLine(rightShinCol, rightKnee, rightAnkle, strokePx, StrokeCap.Round)

        // Cerc de highlight pe articulația cu cea mai mare eroare
        val highlightOffset: Offset? = when (highlightedJoint) {
            AngleVector.JOINT_LEFT_SHOULDER -> leftShoulder
            AngleVector.JOINT_RIGHT_SHOULDER -> rightShoulder
            AngleVector.JOINT_LEFT_ELBOW -> leftElbow
            AngleVector.JOINT_RIGHT_ELBOW -> rightElbow
            AngleVector.JOINT_LEFT_HIP -> leftHip
            AngleVector.JOINT_RIGHT_HIP -> rightHip
            AngleVector.JOINT_LEFT_KNEE -> leftKnee
            AngleVector.JOINT_RIGHT_KNEE -> rightKnee
            else -> null
        }

        if (highlightOffset != null) {
            drawCircle(
                color = errColor,
                radius = 7.dp.toPx(),
                center = highlightOffset
            )
            drawCircle(
                color = errColor.copy(alpha = 0.45f),
                radius = 13.dp.toPx(),
                center = highlightOffset,
                style = Stroke(width = 2.5.dp.toPx())
            )
        }
    }
}

@Composable
private fun ScoreMetricColumn(
    label: String,
    score: Float
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "${score}%",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun JointErrorRow(
    jointName: String,
    errorDegrees: Float
) {
    // 0° este perfect, 45°+ este eroare mare
    val normalizedError = (errorDegrees / 45f).coerceIn(0f, 1f)
    val barColor = when {
        errorDegrees <= 10f -> Color(0xFF2E7D32)
        errorDegrees <= 22f -> Color(0xFFEF6C00)
        else -> Color(0xFFC62828)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = jointName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "±${errorDegrees}°",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = barColor
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { 1f - normalizedError },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = barColor
        )
    }
}
