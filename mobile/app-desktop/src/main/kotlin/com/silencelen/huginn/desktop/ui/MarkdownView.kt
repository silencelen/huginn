package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silencelen.huginn.desktop.theme.LocalSyntaxColors
import com.silencelen.huginn.desktop.theme.MonoStyle
import com.silencelen.huginn.desktop.theme.SyntaxColors
import com.silencelen.huginn.ui.MdBlock
import com.silencelen.huginn.ui.Markdown
import com.silencelen.huginn.ui.Syntax

/**
 * Renders what Claude writes, using :core's parser and :core's tokenizer.
 *
 * This composable is the only new code here — [Markdown.parse] and
 * [Syntax.highlight] are the SAME functions the phone calls, on the same
 * `AnnotatedString`/`Color` types, with no shim. That is the concrete payoff of
 * Compose Multiplatform publishing `androidx.compose.ui.*` under the same package
 * names, and it is why the Electron client had to re-implement both by hand.
 *
 * A candidate to move into `:ui` in phase 3b: the phone's version of this file
 * differs only in paddings.
 */
@Composable
fun MarkdownView(source: String, modifier: Modifier = Modifier) {
    // Keyed on the source: a streaming answer re-parses on every delta otherwise,
    // and Claude writes thousands of them per turn.
    val blocks = remember(source) { Markdown.parse(source) }
    Column(modifier) {
        blocks.forEach { block -> BlockView(block) }
    }
}

@Composable
private fun BlockView(block: MdBlock) {
    when (block) {
        is MdBlock.Paragraph -> Text(
            block.text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 3.dp),
        )

        is MdBlock.Heading -> Text(
            block.text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            fontSize = when (block.level) { 1 -> 17.sp; 2 -> 15.sp; else -> 14.sp },
            modifier = Modifier.padding(top = 10.dp, bottom = 3.dp),
        )

        is MdBlock.Bullet -> Row(Modifier.padding(vertical = 1.dp)) {
            Text(
                block.ordinal ?: "•",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp, start = 4.dp),
            )
            Text(block.text, style = MaterialTheme.typography.bodyMedium)
        }

        is MdBlock.Code -> CodeCard(block)

        // Quotes get a left mark and nothing else. NOT an accent bar on a card —
        // the house rule bans those; this is punctuation inside a paragraph.
        is MdBlock.Quote -> Row(Modifier.padding(vertical = 3.dp)) {
            Text(
                "▏",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp),
            )
            Text(
                block.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        MdBlock.Rule -> HorizontalDivider(
            Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun CodeCard(block: MdBlock.Code) {
    val syntax = LocalSyntaxColors.current
    val text = remember(block.code, block.lang) { highlight(block.code, block.lang, syntax) }
    Column(
        Modifier.fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp),
    ) {
        block.lang?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        // Code does not wrap: a wrapped shell command is a shell command you
        // cannot read. It scrolls sideways instead.
        Text(text, style = MonoStyle, softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState()))
    }
}

private fun highlight(code: String, lang: String?, colors: SyntaxColors): AnnotatedString =
    buildAnnotatedString {
        append(code)
        Syntax.highlight(code, lang).forEach { span ->
            val color = when (span.tok) {
                Syntax.Tok.KEYWORD -> colors.keyword
                Syntax.Tok.STRING -> colors.string
                Syntax.Tok.NUMBER -> colors.number
                Syntax.Tok.COMMENT -> colors.comment
                Syntax.Tok.FUNCTION -> colors.function
                Syntax.Tok.META -> colors.meta
                Syntax.Tok.ADDED -> colors.added
                Syntax.Tok.REMOVED -> colors.removed
                Syntax.Tok.PLAIN, Syntax.Tok.PUNCT -> Color.Unspecified
            }
            if (color != Color.Unspecified) {
                addStyle(SpanStyle(color = color), span.start, span.end.coerceAtMost(code.length))
            }
        }
    }
