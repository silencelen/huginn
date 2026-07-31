package com.silencelen.huginn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    // Keyed on the source: a streaming answer re-parses on every delta otherwise,
    // and Claude writes thousands of them per turn.
    val blocks = remember(text) { Markdown.parse(text) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Paragraph -> Text(b.text, style = MaterialTheme.typography.bodyMedium)
                is MdBlock.Heading -> Text(
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
                    Text(b.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
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
                    Text(
                        b.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is MdBlock.Code -> CodeCard(b, onCopy)
                MdBlock.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
