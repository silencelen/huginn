package com.silencelen.huginn.desktop.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/**
 * The phone's palette, on a bigger screen.
 *
 * Copied VALUES, not a copied file: this is a second declaration of the same
 * eighteen colours and it will drift, which is exactly the argument for the `:ui`
 * module in phase 3b — the theme is the first thing that should move there,
 * because it is the one file where drift is invisible until someone puts the two
 * clients side by side.
 *
 * Dark only for now. The phone styles light because Android hands it either;
 * this window is opened deliberately, at night, beside a terminal, and a light
 * scheme nobody has asked for is a scheme nobody has checked.
 */
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

/** Same five restrained hues the phone uses; see the phone's Theme.kt for why. */
data class SyntaxColors(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val function: Color,
    val meta: Color,
    val added: Color,
    val removed: Color,
)

val DarkSyntax = SyntaxColors(
    keyword = Color(0xFFC495DC),
    string = Color(0xFF8CCB7B),
    number = Color(0xFFE3C169),
    comment = Color(0xFF8A8177),
    function = Color(0xFF7DAFEA),
    meta = Color(0xFF6FC4C7),
    added = Color(0xFF8CCB7B),
    removed = Color(0xFFE8736D),
)

val LocalSyntaxColors = staticCompositionLocalOf { DarkSyntax }

/**
 * Terminal + code text. 13sp rather than the phone's 11: a desktop is read at
 * arm's length, not at reading distance.
 */
val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 17.sp)

@Composable
fun HuginnDesktopTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSyntaxColors provides DarkSyntax) {
        MaterialTheme(colorScheme = DarkColors, typography = Typography()) {
            // THE SURFACE IS LOAD-BEARING, and its absence is silent. Material3's
            // `Text` falls back to `LocalContentColor`, whose default is BLACK —
            // `MaterialTheme` does not provide it, `Surface` does. Without this
            // wrapper the whole window renders black ink on a near-black
            // background: legible enough in a screenshot to look like a font
            // problem, and completely unreadable in use. The phone never hit it
            // because its root has always been a Surface.
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                content = content,
            )
        }
    }
}
