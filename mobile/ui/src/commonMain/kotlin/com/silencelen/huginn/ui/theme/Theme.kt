package com.silencelen.huginn.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.silencelen.huginn.ui.VerbTone

// Moss & Heather: a dark grey ground with a breath of green in it, cool bone ink,
// moss as the one accent, heather as the second voice. A terminal that does not
// look like a terminal emulator. Dark is the primary target (this app is read at
// night, on a phone, next to a laptop running the same sessions) but light is
// fully styled because Android will hand us either.
//
// Every value here is a token from assets/brand/explorations/color/
// color-04-moss-heather/tokens.json, proved by that folder's contrast.txt: text
// >= 4.5:1 on every tier it sits on, UI >= 3:1, the state ladder 12 L* apart.
// assets/brand/BRAND.md section 4 is the rule book; change the tokens there and
// regenerate, never here first.
//
// ONE declaration, for both clients. These colours previously existed twice —
// once in app/ui/theme and once in app-desktop/desktop/theme — and the second
// copy's own header said it would drift. Drift here is invisible until someone
// puts a phone next to the laptop, which is the one comparison the whole
// migration exists to make favourable.

private val Ink = Color(0xFFE2E6E1)          // onSurface
private val Bg = Color(0xFF141615)           // background
private val Surface0 = Color(0xFF0E100F)     // surfaceContainerLowest: code wells, the terminal block
private val Surface1 = Color(0xFF1C1F1D)     // surfaceContainerLow: list rows, chat bubbles
private val Surface2 = Color(0xFF242826)     // surfaceContainer: cards, sheets
private val Surface3 = Color(0xFF2D3230)     // surfaceContainerHigh: dialogs, menus
private val Surface4 = Color(0xFF383E3B)     // surfaceContainerHighest: chips, inputs on a dialog
private val Accent = Color(0xFF7CB98E)       // moss: huginn's one accent
private val AccentInk = Color(0xFF0B2316)
private val Muted = Color(0xFFA2ABA5)        // onSurfaceVariant

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = AccentInk,
    primaryContainer = Color(0xFF1F3D2C),
    onPrimaryContainer = Color(0xFFC2E6CF),
    secondary = Color(0xFFB49CDB),           // heather
    onSecondary = Color(0xFF221640),
    tertiary = Color(0xFFE6AEC6),            // the third voice: subagent markers, graph series
    background = Bg,
    onBackground = Ink,
    surface = Bg,
    onSurface = Ink,
    surfaceVariant = Surface1,
    onSurfaceVariant = Muted,
    surfaceContainerLowest = Surface0,
    surfaceContainerLow = Surface1,
    surfaceContainer = Surface2,
    surfaceContainerHigh = Surface3,
    surfaceContainerHighest = Surface4,
    outline = Color(0xFF747D77),             // 3:1 strokes
    outlineVariant = Color(0xFF3A423E),      // decorative edges
    error = Color(0xFFEE757D),
    onError = Color(0xFF2B0C10),
    errorContainer = Color(0xFF4C1C22),
    onErrorContainer = Color(0xFFF8C5CA),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F5A3A),             // evergreen
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDEAD6),
    onPrimaryContainer = Color(0xFF0A3320),
    secondary = Color(0xFF5B3E8A),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF5E1E3C),
    background = Color(0xFFF4F6F3),
    onBackground = Color(0xFF191C1A),
    surface = Color(0xFFF4F6F3),
    onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFEDF0EC),
    onSurfaceVariant = Color(0xFF535B56),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEDF0EC),
    surfaceContainer = Color(0xFFE6EAE6),
    surfaceContainerHigh = Color(0xFFDFE4DF),
    surfaceContainerHighest = Color(0xFFD6DCD6),
    outline = Color(0xFF78817B),
    outlineVariant = Color(0xFFC6CDC8),
    error = Color(0xFFB3262E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DBDC),
    onErrorContainer = Color(0xFF5E1418),
)

/**
 * The brand's own roles, the ones Material has no name for. Read through
 * [LocalHuginnColors]; the theme provides the scheme's twin.
 *
 * The three tray/list states are one family at three lightness steps, 12 L*
 * apart or more, so a monochrome tray and a colour-blind reader still tell them
 * apart. `needs you` is the accent lit up, not the error red: a question is not
 * a failure. Success, error and info are text semantics separated by hue.
 */
data class HuginnColors(
    val stateIdle: Color,
    val stateWorking: Color,
    val stateAttention: Color,
    val stateSuccess: Color,
    val stateError: Color,
    val stateInfo: Color,
    /** Dividers between rows: a tier below `outlineVariant`. */
    val outlineHairline: Color,
    /** The raven on light grounds (icon ink) and the icon tile. */
    val inkMark: Color,
    val boneTile: Color,
)

val DarkHuginnColors = HuginnColors(
    stateIdle = Color(0xFF848C87),
    stateWorking = Accent,
    stateAttention = Color(0xFFE8D07E),   // round 7: warmed toward gold
    stateSuccess = Color(0xFF80CFC9),     // round 7: teal, the brightCyan slot
    stateError = Color(0xFFEE757D),
    stateInfo = Color(0xFFB49CDB),        // round 7: heather is the info voice
    outlineHairline = Color(0xFF2A302D),
    inkMark = Color(0xFF141615),
    boneTile = Color(0xFFE2E6E1),
)

val LightHuginnColors = HuginnColors(
    stateIdle = Color(0xFF687069),
    stateWorking = Color(0xFF1F5A3A),
    stateAttention = Color(0xFF3D2B05),
    stateSuccess = Color(0xFF176B66),
    stateError = Color(0xFFB3262E),
    stateInfo = Color(0xFF5B3E8A),
    outlineHairline = Color(0xFFDDE2DE),
    inkMark = Color(0xFF141615),
    boneTile = Color(0xFFE2E6E1),
)

val LocalHuginnColors = staticCompositionLocalOf { DarkHuginnColors }

/**
 * How much of the error red a verb that ends something gently gets.
 *
 * ⚠ ONE TOKEN, DEFINED ONCE, and the reason is that the alternative was each
 * shell picking its own lighter red — which is the drift this whole module
 * exists to prevent, and the kind nobody sees until a phone is held up beside
 * the laptop running the same session.
 *
 * An alpha over [ColorScheme.error] rather than `errorContainer`: the container
 * is a surface (a banner, a toast), and a tint of the hard red is also the
 * honest description of what this means. It reads as the same red, quieter,
 * which is exactly the relationship "Wrap up" has to "Kill session".
 */
const val SOFT_VERB_ALPHA: Float = 0.7f

/**
 * The ink for a menu row or button, by how hard its verb lands.
 *
 * Takes the scheme rather than reading `MaterialTheme` so it is an ordinary
 * function a test can call: the pairing is a DECISION, and a decision expressed
 * only inside a composable is one nobody can assert.
 */
fun verbInk(tone: VerbTone, scheme: ColorScheme): Color = when (tone) {
    VerbTone.PLAIN -> scheme.onSurface
    VerbTone.SOFT -> scheme.error.copy(alpha = SOFT_VERB_ALPHA)
    VerbTone.DESTRUCTIVE -> scheme.error
}

/**
 * Syntax colours. Restrained on purpose: a few hues that sit inside the brand's
 * palette rather than a rainbow, chosen so the classes that carry meaning
 * (comment, string, keyword, number) separate at a glance without the code block
 * turning into decoration. Every one is >= 4.5:1 on the code ground
 * (`surfaceContainerLowest`), by the same proof as the scheme.
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
    keyword = Color(0xFFC3A8E8),
    string = Color(0xFF9FD3AD),
    number = Color(0xFFE3C58A),
    comment = Color(0xFF8C958F),
    function = Color(0xFF86B5E6),
    meta = Color(0xFF7FC9B8),
    added = Color(0xFF9FD3AD),
    removed = Color(0xFFEE757D),
)

val LightSyntax = SyntaxColors(
    keyword = Color(0xFF5B3E8A),
    string = Color(0xFF1F5A3A),
    number = Color(0xFF8A5A00),
    comment = Color(0xFF6B746E),
    function = Color(0xFF2A5FA5),
    meta = Color(0xFF176B66),
    added = Color(0xFF1F5A3A),
    removed = Color(0xFFB3262E),
)

val LocalSyntaxColors = staticCompositionLocalOf { DarkSyntax }

/**
 * Terminal + code text. `FontFamily.Monospace` resolves to whatever mono face the
 * platform has; the brand face is IBM Plex Mono (BRAND.md section 5), and
 * bundling it as a Compose resource is the step that makes the phone and the
 * laptop show the same glyphs. Until then the platform mono stands in.
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
        LocalHuginnColors provides if (darkTheme) DarkHuginnColors else LightHuginnColors,
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
