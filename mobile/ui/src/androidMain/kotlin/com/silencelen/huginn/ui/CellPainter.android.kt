package com.silencelen.huginn.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

/**
 * The phone's glyph blit: `android.graphics.Paint` straight onto the native
 * canvas, exactly as it has been since the terminal shipped.
 *
 * This is deliberately the SAME code the app already ran — the class moved and
 * grew an interface, the drawing did not change — because the terminal is the
 * feature the owner uses this app for, there is no device or emulator on this
 * host to check it on, and a rewritten text path is not something to find out
 * about from a phone.
 *
 * `Paint` rather than Compose's `TextMeasurer`: a full screen is a few hundred
 * runs per frame and TextMeasurer lays out (and caches only eight of) them.
 */
class AndroidCellPainter(textSizePx: Float) : CellPainter {

    private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = textSizePx
    }
    private val boldPaint: Paint = Paint(paint).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    override val cellWidth: Float = paint.measureText("M")
    private val fm = paint.fontMetrics
    override val cellHeight: Float = (fm.descent - fm.ascent) * 1.06f
    override val baseline: Float = -fm.ascent

    private fun paintFor(bold: Boolean, italic: Boolean): Paint = when {
        bold && italic -> Paint(boldPaint).apply { textSkewX = -0.25f }
        bold -> boldPaint
        italic -> Paint(paint).apply { textSkewX = -0.25f }
        else -> paint
    }

    override fun advanceOf(text: String, bold: Boolean, italic: Boolean): Float =
        paintFor(bold, italic).measureText(text)

    override fun drawRun(
        scope: DrawScope,
        text: String,
        x: Float,
        baseline: Float,
        color: Color,
        bold: Boolean,
        italic: Boolean,
    ) {
        val p = paintFor(bold, italic).apply { this.color = color.toArgb() }
        scope.drawIntoCanvas { it.nativeCanvas.drawText(text, x, baseline, p) }
    }
}
