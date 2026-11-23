package com.dancewithme.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
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
import com.dancewithme.app.sensors.GravitySensorManager

/**
 * Ecranul de calibrare (`CalibrationScreen`).
 * Citește înclinarea telefonului de la [GravitySensorManager] și ghidează utilizatorul să
 * poziționeze telefonul la ~90 de grade (±8°) înainte de a intra în `DrillScreen`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(
    danceTitle: String,
    currentTiltDeg: Float,
    isCalibrated: Boolean,
    onTiltUpdated: (Float) -> Unit,
    onProceedToDrill: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val gravitySensorManager = remember(context) {
        GravitySensorManager(context = context, onTiltChanged = onTiltUpdated)
    }

    DisposableEffect(gravitySensorManager) {
        gravitySensorManager.startListening()
        onDispose {
            gravitySensorManager.stopListening()
        }
    }

    val indicatorColor = if (isCalibrated) Color(0xFF2E7D32) else Color(0xFFD32F2F)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Calibrare Senzor Gravitațional") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = danceTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Așază telefonul vertical pe un suport (~90°) astfel încât camera să-ți surprindă întregul corp.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Indicator vizual circular pentru unghiul de înclinare
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(220.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val radius = size.minDimension / 2.2f
                    // Cercul exterior
                    drawCircle(
                        color = indicatorColor.copy(alpha = 0.2f),
                        radius = radius,
                        style = Stroke(width = 16.dp.toPx())
                    )
                    // Linia orizontală de referință (ținta de 90°)
                    drawLine(
                        color = Color.Gray.copy(alpha = 0.5f),
                        start = Offset(center.x - radius, center.y),
                        end = Offset(center.x + radius, center.y),
                        strokeWidth = 4.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    // Nivelă dinamică în funcție de abaterea față de 90°
                    val offsetFactor = ((currentTiltDeg - 90f) / 90f).coerceIn(-1f, 1f)
                    val bubbleY = center.y + (offsetFactor * radius * 0.75f)
                    drawCircle(
                        color = indicatorColor,
                        radius = 22.dp.toPx(),
                        center = Offset(center.x, bubbleY)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${currentTiltDeg}°",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = indicatorColor
                    )
                    Text(
                        text = if (isCalibrated) "CALIBRAT (Țintă: 90°)" else "Înclină spre 90°",
                        style = MaterialTheme.typography.labelLarge,
                        color = indicatorColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Slider ajutător pentru testare rapidă pe Emulator (sau ajustare manuală)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Simulare / Ajustare Unghi (pentru Emulator): ${currentTiltDeg.toInt()}°",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Slider(
                        value = currentTiltDeg,
                        onValueChange = onTiltUpdated,
                        valueRange = 0f..180f
                    )
                    OutlinedButton(
                        onClick = { onTiltUpdated(90f) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Setează Instant la 90° (Calibrare Rapidă)")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Înapoi")
                }
                Button(
                    onClick = onProceedToDrill,
                    enabled = isCalibrated,
                    modifier = Modifier.weight(2f)
                ) {
                    Text(if (isCalibrated) "Continuă spre Dans" else "Aștept ~90°...")
                }
            }
        }
    }
}
