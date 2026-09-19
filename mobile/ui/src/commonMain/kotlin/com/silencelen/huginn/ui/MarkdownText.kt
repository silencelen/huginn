package com.silencelen.huginn.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull
import com.silencelen.huginn.ui.theme.LocalMonoStyle
import com.silencelen.huginn.ui.theme.LocalSyntaxColors

/**
 * What Claude writes, rendered — on the phone and on the desktop, from here.
 *
 * The parser ([Markdown.parse]) and the tokenizer ([Syntax.highlight]) already
 * lived in `:core`; this is the drawing, which used to exist twice. The two
 * copies had diverged in exactly the way a duplicated renderer does: different
 * heading scales, different bullet metrics, a quote drawn as a rule on one and as
 * a `▏` glyph on the other, and a copy button on only one of them. The phone's
 * shape wins throughout — it is the version eight audit rounds have been over —
 * and the two differences that were REAL are parameters:
 *
 *  * **Code size** comes from `LocalMonoStyle`, which the theme sets per client
 *    (11sp phone, 13sp desktop). Reading distance is not a fork.
 *  * **[onCopy]** is nullable. A surface with nowhere to put text — a preview, a
 *    future read-only pane — passes null and gets no button, rather than a button
 *    that does nothing.
 *
 * @param onCopy given the code of the block whose copy button was pressed.
 */
@Composable
fun MarkdownText(
    text: String,
    onCopy: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Links are painted in the theme's primary, which `:core` cannot know, so
    // the styles are a parameter and the parse is keyed on them as well as on
    // the source. A streaming answer re-parses on every delta otherwise, and
    // Claude writes thousands of them per turn.
    val scheme = MaterialTheme.colorScheme
    val linkStyles = remember(scheme) {
        TextLinkStyles(
            style = SpanStyle(color = scheme.primary, textDecoration = TextDecoration.Underline),
            hoveredStyle = SpanStyle(
                color = scheme.primary,
                textDecoration = TextDecoration.Underline,
                background = scheme.primary.copy(alpha = 0.10f),
            ),
        )
    }
    val blocks = remember(text, linkStyles) { Markdown.parse(text, linkStyles) }
    // Paths the answer NAMED rather than embedded. Drawn after the prose, in the
    // order written — see ImageMentions for why the rules are so narrow.
    val mentions = remember(text) { ImageMentions.paths(text) }
    val viewer = remember { ImageViewerState() }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Paragraph -> LinkableText(b.text, MaterialTheme.typography.bodyMedium)
                is MdBlock.Heading -> LinkableText(
                    b.text,
                    style = when (b.level) {
                        1 -> MaterialTheme.typography.titleMedium
                        2 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.bodyLarge
                    },
                    fontWeight = FontWeight.Bold,
                )
                is MdBlock.Bullet -> Row(Modifier.fillMaxWidth()) {
                    // A fixed marker column, wider for "10." than for "•", so the
                    // text of a list starts on one vertical line.
                    Text(
                        b.ordinal ?: "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(if (b.ordinal != null) 22.dp else 14.dp),
                    )
                    LinkableText(b.text, MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
                // A left mark and nothing else. NOT an accent bar on a card — the
                // house rule bans those; this is punctuation inside a paragraph.
                is MdBlock.Quote -> Row(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .height(18.dp)
                            .background(MaterialTheme.colorScheme.outline)
                    )
                    Spacer(Modifier.width(8.dp))
                    LinkableText(
                        b.text,
                        MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is MdBlock.Table -> TableGrid(b)
                is MdBlock.Image -> PathImage(b.src, b.alt, viewer)
                is MdBlock.Code -> CodeCard(b, onCopy)
                MdBlock.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        mentions.forEach { PathImage(it, it, viewer) }
    }
    FullImageViewer(viewer)
}

/**
 * A picture the answer pointed at, drawn small enough to sit inside a turn and
 * tappable for the full size.
 *
 * Every failure — no loader, no such route on an older daemon, a 403 from
 * outside the daemon's roots, a 404, bytes that will not decode — lands on the
 * same muted placeholder carrying what was written. Never a broken-image glyph,
 * and never a throw into composition: a missing thumbnail must cost the reader
 * nothing but the thumbnail.
 */
@Composable
private fun PathImage(src: String, alt: String, viewer: ImageViewerState) {
    val loader = LocalAttachmentImages.current
    val session = LocalImageSession.current
    var bitmap by remember(src) { mutableStateOf<ImageBitmap?>(null) }
    var settled by remember(src) { mutableStateOf(false) }
    LaunchedEffect(src, loader, session) {
        bitmap = loader?.loadPath(src, session)
        settled = true
    }
    val shown = bitmap
    when {
        shown != null -> Image(
            bitmap = shown,
            contentDescription = alt.ifBlank { src },
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .heightIn(max = THUMB_MAX)
                .widthIn(max = THUMB_MAX)
                .clip(RoundedCornerShape(10.dp))
                .clickable { viewer.open(src, shown) },
        )
        // Settled and still nothing: say which file, quietly.
        settled -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                alt.ifBlank { src },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
        // Still loading: draw nothing at all. A placeholder that is replaced by a
        // picture of a different height moves the text under the reader's eye,
        // and the transcript is auto-following while it happens.
        else -> Unit
    }
}

private val THUMB_MAX = 260.dp

/**
 * A table, drawn as a compact grid.
 *
 * ⚠ IT SCROLLS SIDEWAYS INSIDE ITSELF, and that is the whole shape of this
 * composable. A table is the one thing Claude writes that has no honest narrow
 * form: wrapping a row turns a grid into a paragraph, and letting it set its own
 * width would make the TRANSCRIPT scroll horizontally — every message on the
 * screen dragged sideways to read one table. The same answer the code card
 * reached, for the same reason.
 *
 * The header is bold with a rule under it and nothing else: the columns are the
 * grid, so ruling every row would be drawing the table twice.
 */
@Composable
private fun TableGrid(b: MdBlock.Table) {
    val widths = remember(b) { columnWidths(b.rows) }
    val total = remember(widths) { widths.fold(0.dp) { acc, w -> acc + w } }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // ⚠ EVERY CHILD IN HERE IS EXPLICITLY WIDTH-SET. Inside a horizontal
        // scroll the incoming maxWidth is infinite, so a `fillMaxWidth()` child —
        // the divider was one — has nothing to fill.
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
                b.rows.forEachIndexed { r, row ->
                    Row {
                        row.forEachIndexed { c, cell ->
                            Text(
                                cell,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (b.header && r == 0) FontWeight.Bold else null,
                                color = if (b.header && r == 0) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .width(widths.getOrElse(c) { TABLE_COL_MIN })
                                    .padding(horizontal = 5.dp, vertical = 3.dp),
                            )
                        }
                    }
                    if (r == 0 && b.header) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.width(total),
                        )
                    }
                }
            }
        }
    }
}

private val TABLE_COL_MIN = 44.dp
private val TABLE_COL_MAX = 210.dp

/** Rough width of one character of `bodySmall`, plus a cell's own padding. */
private const val TABLE_CHAR_DP = 6.6f
private const val TABLE_CELL_PAD_DP = 12f

/**
 * How wide a table column is drawn, from the longest cell in it.
 *
 * AN ESTIMATE, DELIBERATELY. Measuring text to size a column needs a
 * SubcomposeLayout and two passes over every cell, on a surface that re-renders
 * on every streamed delta. The cost of being wrong is bounded in the direction
 * that matters: a cell that overruns its estimate WRAPS inside its column, so a
 * bad guess buys a taller row and never a cut word.
 */
fun tableColumnWidth(longestCellChars: Int): Dp =
    (longestCellChars * TABLE_CHAR_DP + TABLE_CELL_PAD_DP).dp.coerceIn(TABLE_COL_MIN, TABLE_COL_MAX)

/** One width per column, from the longest cell anywhere in that column. */
internal fun columnWidths(rows: List<List<androidx.compose.ui.text.AnnotatedString>>): List<Dp> {
    val columns = rows.firstOrNull()?.size ?: 0
    return List(columns) { c ->
        tableColumnWidth(rows.maxOfOrNull { it.getOrNull(c)?.text?.length ?: 0 } ?: 0)
    }
}

/**
 * Text that may carry link spans, plus the reveal of where a link goes.
 *
 * Compose routes a click on a [LinkAnnotation.Url] to `LocalUriHandler`, which
 * each shell replaces with an http(s)-only opener — so this composable only has
 * to answer the other question, "where does that go", WITHOUT costing anything
 * when the text has no links at all (which is most of them).
 *
 * ⚠ THE POINTER LOOP MUST NOT CONSUME. The link click handler lives inside
 * [Text] and sees the Main pass first; a `detectTapGestures` wrapped around it
 * eats the down event and the link stops opening. This observes on the Initial
 * pass and consumes nothing, which is why the long-press branch is written out
 * by hand instead of reusing the gesture detector.
 */
@Composable
private fun LinkableText(
    text: AnnotatedString,
    style: TextStyle,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    modifier: Modifier = Modifier,
) {
    val hasLinks = remember(text) { text.hasLinkAnnotations(0, text.length) }
    if (!hasLinks) {
        Text(text, style = style, color = color, fontWeight = fontWeight, modifier = modifier)
        return
    }
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    var peeked by remember(text) { mutableStateOf<String?>(null) }

    fun urlAt(at: Offset): String? {
        val l = layout ?: return null
        if (at.x < 0f || at.y < 0f || at.x > l.size.width || at.y > l.size.height) return null
        val offset = l.getOffsetForPosition(at)
        return (text.getLinkAnnotations(offset, offset).firstOrNull()?.item as? LinkAnnotation.Url)?.url
    }

    val watch = Modifier.pointerInput(text) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.lastOrNull()
                val next = when (event.type) {
                    PointerEventType.Exit -> null
                    PointerEventType.Press -> {
                        // Held rather than tapped: the phone's answer to hover.
                        val at = change?.position
                        val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                if (e.type == PointerEventType.Release) break
                            }
                        }
                        if (released == null && at != null) urlAt(at) else peeked
                    }
                    // A mouse moving over the text; a finger dragging is a press,
                    // and a press that is not held is a click, not a question.
                    PointerEventType.Move, PointerEventType.Enter ->
                        if (change?.pressed == true) peeked else change?.position?.let(::urlAt)
                    else -> peeked
                }
                if (next != peeked) peeked = next
            }
        }
    }

    LocalLinkPeek.current.Wrap(peeked) {
        Text(
            text,
            style = style,
            color = color,
            fontWeight = fontWeight,
            onTextLayout = { layout = it },
            modifier = modifier.then(watch),
        )
    }
}

/**
 * How a shell reveals the URL under the pointer. The default puts it on a muted
 * line under the text — which is the right answer after a deliberate long press
 * on a phone, and the wrong one on a desktop, where the line would appear and
 * disappear under a moving mouse and shift the paragraph being read. The desktop
 * shell replaces this with a tooltip.
 */
fun interface LinkPeek {
    @Composable fun Wrap(url: String?, content: @Composable () -> Unit)
}

val LocalLinkPeek = staticCompositionLocalOf<LinkPeek> {
    LinkPeek { url, content ->
        Column {
            content()
            if (url != null) {
                Text(
                    url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CodeCard(b: MdBlock.Code, onCopy: ((String) -> Unit)?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    b.lang ?: "code",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                onCopy?.let { copy ->
                    IconButton(onClick = { copy(b.code) }, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = "Copy code",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // Code does not wrap: a wrapped shell command is a shell command you
            // cannot read. It scrolls sideways instead.
            Box(Modifier.horizontalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, bottom = 8.dp)) {
                Text(
                    highlighted(b.code, b.lang),
                    style = LocalMonoStyle.current,
                    softWrap = false,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * Applies syntax colour to a code string. The tokenizer is a lexer, so a missed
 * keyword costs a colour and nothing else; text is always rendered whole.
 */
@Composable
fun highlighted(code: String, lang: String?): AnnotatedString {
    val c = LocalSyntaxColors.current
    return remember(code, lang, c) {
        val spans = Syntax.highlight(code, lang)
        if (spans.isEmpty()) return@remember AnnotatedString(code)
        buildAnnotatedString {
            append(code)
            spans.forEach { s ->
                val color = when (s.tok) {
                    Syntax.Tok.KEYWORD -> c.keyword
                    Syntax.Tok.STRING -> c.string
                    Syntax.Tok.NUMBER -> c.number
                    Syntax.Tok.COMMENT -> c.comment
                    Syntax.Tok.FUNCTION -> c.function
                    Syntax.Tok.META -> c.meta
                    Syntax.Tok.ADDED -> c.added
                    Syntax.Tok.REMOVED -> c.removed
                    Syntax.Tok.PLAIN, Syntax.Tok.PUNCT -> null
                } ?: return@forEach
                // Defensive: a stale span from a race would crash the render.
                if (s.start in 0..code.length && s.end in s.start..code.length) {
                    addStyle(SpanStyle(color = color), s.start, s.end)
                }
            }
        }
    }
}
