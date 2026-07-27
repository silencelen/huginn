package com.silencelen.huginn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

// Warm near-black with a parchment ink: a terminal that does not look like a
// terminal emulator. Dark is the primary target (this app is read at night, on a
// phone, next to a laptop running the same sessions) but light is fully styled
// because Android will hand us either.

private val Ink = Color(0xFFE8E2DA)
private val Bg = Color(0xFF12100F)
private val Surface1 = Color(0xFF1B1817)
private val Surface2 = Color(0xFF241F1D)
private val Accent = Color(0xFFC8A45C)      // rune-gold: huginn's one accent
private val AccentInk = Color(0xFF241C09)
private val Muted = Color(0xFF8A8177)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = AccentInk,
    primaryContainer = Surface2,
    onPrimaryContainer = Ink,
    secondary = Color(0xFF7DAFEA),
    onSecondary = Color(0xFF0B1622),
    background = Bg,
    onBackground = Ink,
    surface = Bg,
    onSurface = Ink,
    surfaceVariant = Surface1,
    onSurfaceVariant = Muted,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    outline = Color(0xFF3A3431),
    outlineVariant = Color(0xFF2A2523),
    error = Color(0xFFE8736D),
    onError = Color(0xFF2A0E0C),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF7A5A16),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFFAF7F2),
    onBackground = Color(0xFF1B1817),
    surface = Color(0xFFFAF7F2),
    onSurface = Color(0xFF1B1817),
    surfaceVariant = Color(0xFFEFE9E0),
    onSurfaceVariant = Color(0xFF5C544B),
    outline = Color(0xFFD3CAC0),
    error = Color(0xFFB3261E),
)

/** Terminal + code text. FontFamily.Monospace resolves to the device mono face. */
val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)

private val HuginnTypography = Typography()

@Composable
fun HuginnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = HuginnTypography,
        content = content,
    )
}
