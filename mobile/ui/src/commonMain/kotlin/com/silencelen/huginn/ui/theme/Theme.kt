package com.silencelen.huginn.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

// Warm near-black with a parchment ink: a terminal that does not look like a
// terminal emulator. Dark is the primary target (this app is read at night, on a
// phone, next to a laptop running the same sessions) but light is fully styled
// because Android will hand us either.
//
// ONE declaration, for both clients. These eighteen colours previously existed
// twice — once in app/ui/theme and once in app-desktop/desktop/theme — and the
// second copy's own header said it would drift. Drift here is invisible until
// someone puts a phone next to the laptop, which is the one comparison the whole
// migration exists to make favourable.

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

/**
 * Syntax colours. Restrained on purpose: five hues that sit inside the app's warm
 * palette rather than a rainbow, chosen so the classes that carry meaning
 * (comment, string, keyword, number) separate at a glance without the code block
 * turning into decoration.
 */
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

val LightSyntax = SyntaxColors(
    keyword = Color(0xFF7A3E96),
    string = Color(0xFF2F6B33),
    number = Color(0xFF8A5A00),
    comment = Color(0xFF7A7168),
    function = Color(0xFF1F5FA8),
    meta = Color(0xFF10666A),
    added = Color(0xFF2F6B33),
    removed = Color(0xFFB3261E),
)

val LocalSyntaxColors = staticCompositionLocalOf { DarkSyntax }

/**
 * Terminal + code text. `FontFamily.Monospace` resolves to whatever mono face the
 * platform has.
 *
 * The two clients genuinely disagree about the SIZE and only about the size, so
 * it is a theme parameter rather than a second renderer: a phone is read at
 * reading distance, a desktop at arm's length. Everything downstream reads
 * [LocalMonoStyle] and neither renderer knows which one it got.
 *
 * 15sp of leading, not the 14 the phone's `MonoStyle` declared: that constant was
 * never referenced by anything on the phone, while the code card it would now
 * feed has always drawn at 11/15 inline. Taking the value that was actually on
 * screen is what makes this move invisible.
 */
val MonoStylePhone = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
val MonoStyleDesktop = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 17.sp)

val LocalMonoStyle = staticCompositionLocalOf { MonoStylePhone }

private val HuginnTypography = Typography()

/**
 * @param darkTheme the phone follows the system; the desktop passes `true`
 *   outright, because a light scheme nobody has asked for is a scheme nobody has
 *   checked.
 * @param monoStyle [MonoStylePhone] or [MonoStyleDesktop]; see above.
 * @param rootSurface wrap the content in a [Surface]. **Load-bearing on desktop
 *   and silent when missing.** Material3's `Text` falls back to
 *   `LocalContentColor`, whose default is BLACK — `MaterialTheme` does not
 *   provide it, `Surface` does — so without a Surface somewhere above it the
 *   whole window renders black ink on a near-black background. The phone's root
 *   is a `Scaffold`, which is a Surface already, so it passes `false` and keeps
 *   exactly the layering it shipped.
 */
@Composable
fun HuginnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    monoStyle: TextStyle = MonoStylePhone,
    rootSurface: Boolean = false,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalSyntaxColors provides if (darkTheme) DarkSyntax else LightSyntax,
        LocalMonoStyle provides monoStyle,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = HuginnTypography,
        ) {
            if (rootSurface) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    content = content,
                )
            } else {
                content()
            }
        }
    }
}
