package com.silencelen.huginn.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
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

    private val typeface: Typeface = MONO_FAMILIES.firstNotNullOfOrNull {
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

    private companion object {
        /**
         * In preference order, then whatever the platform calls its default. A
         * terminal drawn in a proportional face is not a terminal, so the fallback
         * chain is worth spelling out rather than trusting one name to exist.
         */
        val MONO_FAMILIES = listOf(
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
