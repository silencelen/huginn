package com.silencelen.huginn.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import org.jetbrains.skia.Data
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Typeface

/**
 * The desktop's glyph blit: skia directly, the same layer the phone reaches
 * through `android.graphics`.
 *
 * Skia and not Compose's `TextMeasurer`, for the reason the phone gives: a
 * 130x50 screen is a few hundred style runs per frame and a text measurer lays
 * each one out through a cache that holds eight. Reaching the native canvas is
 * what makes a terminal a terminal on both platforms; the interface above it is
 * what makes it one terminal.
 *
 * **Emboldening is synthetic on purpose.** A real bold face is free to advance
 * differently from its regular, and a character grid where bold text is a
 * fraction of a pixel wider per glyph drifts out of its columns across a long
 * line. Skewing for italic is synthetic for the same reason.
 */
class SkiaCellPainter(textSizePx: Float) : CellPainter {

    // The bundled IBM Plex Mono first (BRAND.md section 5: the same glyphs on
    // the phone and the laptop, box and block sets included), then the named
    // families, then whatever the platform calls its default.
    private val typeface: Typeface = bundledPlexMono()
        ?: MONO_FAMILIES.firstNotNullOfOrNull {
            FontMgr.default.matchFamilyStyle(it, FontStyle.NORMAL)
        } ?: FontMgr.default.matchFamilyStyle(null, FontStyle.NORMAL)
        ?: Typeface.makeEmpty()

    private val font = Font(typeface, textSizePx)
    private val boldFont = Font(typeface, textSizePx).apply { isEmboldened = true }
    private val italicFont = Font(typeface, textSizePx).apply { skewX = -0.25f }
    private val boldItalicFont = Font(typeface, textSizePx).apply {
        isEmboldened = true
        skewX = -0.25f
    }

    override val cellWidth: Float = font.measureTextWidth("M")
    private val fm = font.metrics
    override val cellHeight: Float = (fm.descent - fm.ascent) * 1.06f
    override val baseline: Float = -fm.ascent

    private val paint = Paint()

    private fun fontFor(bold: Boolean, italic: Boolean): Font = when {
        bold && italic -> boldItalicFont
        bold -> boldFont
        italic -> italicFont
        else -> font
    }

    override fun advanceOf(text: String, bold: Boolean, italic: Boolean): Float =
        fontFor(bold, italic).measureTextWidth(text)

    override fun drawRun(
        scope: DrawScope,
        text: String,
        x: Float,
        baseline: Float,
        color: Color,
        bold: Boolean,
        italic: Boolean,
    ) {
        paint.color = color.toArgb()
        scope.drawIntoCanvas {
            it.nativeCanvas.drawString(text, x, baseline, fontFor(bold, italic), paint)
        }
    }

    // No ligatures, and none are possible: `drawString` blits glyph by glyph
    // without shaping, so a `->` or `!=` is never fused across two cells. That is
    // the brand's "disable ligatures in the terminal view", met by construction.

    private companion object {
        /**
         * The Plex Mono TTF from :ui's compose resources, read straight off the
         * classpath. Synchronous on purpose (the painter is built inside a
         * `remember`, and the generated `Res.readBytes` is a suspend function);
         * the path is the compose-resources layout, `composeResources/<package of
         * Res>/font/…`, with `packageOfResClass` set in ui/build.gradle.kts.
         * Null, never a throw, when the file is not there: the family list below
         * still draws a terminal.
         */
        fun bundledPlexMono(): Typeface? = runCatching {
            val path = "composeResources/com.silencelen.huginn.ui.resources/font/ibm_plex_mono_regular.ttf"
            val loader = SkiaCellPainter::class.java.classLoader ?: return null
            val bytes = loader.getResourceAsStream(path)?.use { it.readBytes() } ?: return null
            FontMgr.default.makeFromData(Data.makeFromBytes(bytes))
        }.getOrNull()

        /**
         * In preference order, then whatever the platform calls its default. A
         * terminal drawn in a proportional face is not a terminal, so the fallback
         * chain is worth spelling out rather than trusting one name to exist.
         */
        val MONO_FAMILIES = listOf(
            "IBM Plex Mono",
            "JetBrains Mono",
            "DejaVu Sans Mono",
            "Liberation Mono",
            "Noto Sans Mono",
            "Courier New",
            "monospace",
            "Monospaced",
        )
    }
}
