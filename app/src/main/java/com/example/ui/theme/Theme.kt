package com.example.ui.theme

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
    primary = TachoCyan,
    onPrimary = Color(0xFF0F172A),
    primaryContainer = Color(0xFF0C4A6E),
    onPrimaryContainer = Color(0xFFBAE6FD),
    secondary = TachoAmber,
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF78350F),
    onSecondaryContainer = Color(0xFFFEF3C7),
    tertiary = TachoGreen,
    onTertiary = Color.White,
    background = TachoDarkBg,
    onBackground = Color(0xFFF1F5F9),
    surface = TachoDarkSurface,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = TachoDarkCard,
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = TachoDarkCardBorder,
    error = TachoRed
)

private val LightColorScheme = lightColorScheme(
    primary = TachoCyanDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0369A1),
    secondary = TachoAmberDark,
    onSecondary = Color.White,
    tertiary = TachoGreen,
    onTertiary = Color.White,
    background = TachoLightBg,
    onBackground = TachoLightText,
    surface = TachoLightSurface,
    onSurface = TachoLightText,
    surfaceVariant = TachoLightCard,
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    error = TachoRed
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Default to cockpit dark theme for automotive visibility
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
