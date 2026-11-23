package com.example.dancewithme.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = Color(0xFF003640),
    primaryContainer = DarkCyanContainer,
    onPrimaryContainer = Color(0xFFB2EBF2),
    secondary = NeonEmerald,
    onSecondary = Color(0xFF003919),
    secondaryContainer = DarkEmeraldContainer,
    onSecondaryContainer = Color(0xFFB9F6CA),
    tertiary = CyberAmber,
    onTertiary = Color(0xFF3E2723),
    background = DarkSlateBackground,
    onBackground = OnDarkTextPrimary,
    surface = DarkSlateSurface,
    onSurface = OnDarkTextPrimary,
    surfaceVariant = DarkSlateSurfaceVariant,
    onSurfaceVariant = OnDarkTextSecondary
)

private val LightColorScheme = lightColorScheme(
    primary = PetrolBluePrimary,
    onPrimary = Color.White,
    primaryContainer = LightCyanContainer,
    onPrimaryContainer = Color(0xFF002025),
    secondary = DeepEmeraldSecondary,
    onSecondary = Color.White,
    secondaryContainer = LightEmeraldContainer,
    onSecondaryContainer = Color(0xFF00210D),
    tertiary = DeepCyanTertiary,
    onTertiary = Color.White,
    background = LightIceBackground,
    onBackground = OnLightTextPrimary,
    surface = LightPureSurface,
    onSurface = OnLightTextPrimary,
    surfaceVariant = LightSlateSurfaceVariant,
    onSurfaceVariant = OnLightTextSecondary
)

@Composable
fun DanceWithMETheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dezactivat implicit pentru a păstra identitatea vizuală Edge AI Fitness consistentă pe toate dispozitivele
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
