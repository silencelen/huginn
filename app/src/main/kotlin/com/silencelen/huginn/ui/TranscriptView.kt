package com.silencelen.huginn.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silencelen.huginn.data.TranscriptEvent

/**
 * Renders one normalized transcript event. Shared by the session view and the
 * chat view: both read the same Claude Code transcript, so both get thinking,
 * tool calls, subagent output and workflow runs from the same code. Adding a new
 * event kind means changing this file only.
 */
@Composable
fun TranscriptEventItem(
    ev: TranscriptEvent,
    onCopy: (String) -> Unit,
) {
    // Subagent output is indented under a marker rather than hidden: during a
    // fan-out it is most of what is happening, but it is not the main thread.
    val indent = if (ev.sidechain) 14.dp else 0.dp
    Box(Modifier.padding(start = indent)) {
        when (ev.kind) {
            "user" -> UserBubble(ev.text.orEmpty())
            "assistant" -> AssistantBlock(ev, onCopy)
            "thinking" -> ThinkingBlock(ev.text.orEmpty())
            "tool" -> ToolCard(ev)
            "tool_result" -> ToolResultOrphan(ev)
            "system" -> SystemNote(ev.text.orEmpty())
            else -> Unit
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            modifier = Modifier.fillMaxWidth(0.9f),
        ) {
            Text(
                text.trim(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun AssistantBlock(ev: TranscriptEvent, onCopy: (String) -> Unit) {
    val text = ev.text.orEmpty()
    Column(Modifier.fillMaxWidth()) {
        if (ev.sidechain) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AccountTree,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    "subagent",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(3.dp))
        }
        MarkdownText(text, onCopy = onCopy)
    }
}

/**
 * Thinking is collapsed by default and expandable. It is the single most useful
 * thing to have on a phone when a session is mid-turn ("what is it actually
 * doing?"), and also the longest, so it must not push the answer off-screen.
 */
@Composable
private fun ThinkingBlock(text: String) {
    var open by rememberSaveable(text.hashCode()) { mutableStateOf(false) }
    val firstLine = remember(text) {
        text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.take(110).orEmpty()
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable { open = !open },
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "thinking",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                if (!open) {
                    Text(
                        firstLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (open) "Collapse" else "Expand",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(open) {
                Text(
                    text.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * A tool call and its outcome as one card, with the output collapsed. Folding the
 * result into the call is what keeps a long tool-heavy turn readable: the
 * alternative is two cards per tool and a screen that is 90% plumbing.
 */
@Composable
private fun ToolCard(ev: TranscriptEvent) {
    var open by rememberSaveable(ev.seq) { mutableStateOf(false) }
    val hasResult = !ev.result.isNullOrBlank()
    val failed = ev.ok == false
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (hasResult) Modifier.clickable { open = !open } else Modifier),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (failed) Icons.Filled.ErrorOutline
                    else if (ev.name == "Workflow" || ev.name == "Agent" || ev.name == "Task") Icons.Filled.AccountTree
                    else Icons.Filled.Build,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    ev.name.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!ev.detail.isNullOrBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        ev.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (hasResult) {
                    Icon(
                        if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!ev.input.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                // A command must not wrap into ambiguity: scroll it instead.
                Box(Modifier.horizontalScroll(rememberScrollState())) {
                    Text(
                        ev.input,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                    )
                }
            }
            AnimatedVisibility(open && hasResult) {
                Column {
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                        Text(
                            ev.result.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolResultOrphan(ev: TranscriptEvent) {
    // A result whose call is above the loaded window. Shown plainly rather than
    // dropped, so a cold open at the tail is not silently missing output.
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.padding(10.dp).horizontalScroll(rememberScrollState())) {
            Text(
                ev.result.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = if (ev.ok == false) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SystemNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

/** Markdown-rendered body text, with code fences as copyable scrollable cards. */
@Composable
fun MarkdownText(text: String, onCopy: (String) -> Unit) {
    val blocks = remember(text) { Markdown.parse(text) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    Text(
                        b.ordinal ?: "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(if (b.ordinal != null) 22.dp else 14.dp),
                    )
                    Text(b.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
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
private fun CodeCard(b: MdBlock.Code, onCopy: (String) -> Unit) {
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
                IconButton(onClick = { onCopy(b.code) }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Copy code",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(Modifier.horizontalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, bottom = 8.dp)) {
                Text(
                    b.code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
