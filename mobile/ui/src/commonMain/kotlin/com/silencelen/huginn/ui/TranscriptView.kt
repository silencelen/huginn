package com.silencelen.huginn.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.TranscriptEvent

/**
 * The two measurements a transcript row cannot decide for itself, handed down by
 * whichever shell is drawing it.
 *
 * A PARAMETER OBJECT rather than `expect`/`actual` or a `compact` flag: nothing
 * here is a property of the operating system, it is a property of how wide the
 * pane happens to be, and a desktop window narrowed to a phone's width should get
 * the phone's answer. `expect`/`actual` could not express that; a composition
 * local set by the shell can.
 *
 * @param userBubbleFraction how much of the row a user's own message may take.
 *   0.9 on a phone reads as a bubble; 1.0 with a cap reads as a column.
 * @param userBubbleMaxWidth an absolute ceiling, or [Dp.Unspecified] for none.
 *   Unbounded on a 1280pt window, a two-word message becomes a 1200pt bar.
 * @param rowSpacing the gap between transcript rows. The same rhythm that reads
 *   as comfortable under a thumb reads as loose under a mouse, where the eye
 *   travels further per screen and the reader is scanning rather than dwelling.
 *   Defaults to the phone's value, so the phone is unchanged by this existing.
 * @param rowPadding the transcript's own vertical padding, for the same reason.
 */
data class TranscriptMetrics(
    val userBubbleFraction: Float = 0.9f,
    val userBubbleMaxWidth: Dp = Dp.Unspecified,
    val rowSpacing: Dp = 9.dp,
    val rowPadding: Dp = 8.dp,
)

val LocalTranscriptMetrics = staticCompositionLocalOf { TranscriptMetrics() }

/**
 * Where a long-press on a transcript row reports to.
 *
 * THE SEAM, and it is a composition local for the same reason the metrics are:
 * [TranscriptEventItem] is called from four places across two shells, and
 * threading a nullable handler through all of them to reach a gesture only one
 * client offers would change every signature in the module.
 *
 * NULL IS THE DESKTOP'S ANSWER, provided by simply not providing anything. That
 * client already has the gesture — right-click over a selection, with the same
 * four verbs — and adding a long-press to it would put a second, differently
 * shaped entry point on the same text. A default of null therefore means "this
 * shell has its own answer", not "this feature is broken here".
 */
fun interface TranscriptSelectionHost {
    /**
     * The whole row's text, as selected by a long press, and WHEN the row was
     * written — epoch seconds off the wire, null when the daemon had no
     * timestamp for it.
     *
     * THE STAMP TRAVELS RAW, unformatted. The bar the phone raises is where the
     * time is shown (its first line), and formatting it needs a clock, which
     * `:ui` deliberately does not have: every "3 minutes ago" on the phone is
     * drawn from the shell's ticking `screenClock`, not from a fresh read at
     * composition time. So the shell formats, through [TimeWords], with the
     * clock it already owns.
     */
    fun onLongPress(text: String, atSec: Long?)
}

val LocalTranscriptSelection = staticCompositionLocalOf<TranscriptSelectionHost?> { null }

/**
 * The desktop's hover reveal for a row's timestamp — and the seam that keeps it
 * out of `:ui`.
 *
 * `TooltipArea` is `androidx.compose.foundation` DESKTOP, not common, so this
 * module cannot call it. It also cannot format the tip: see
 * [TranscriptSelectionHost.onLongPress] — the clock lives in the shell. Both
 * problems have one answer: hand the shell the raw stamp and let it decide what
 * to wrap the row in.
 *
 * THE DEFAULT IS A NO-OP, which is the phone's answer given by not giving one.
 * A phone has no pointer, so there is nothing to hover; its reveal is the
 * long-press bar, which costs no layout either. That matters more than it
 * sounds: the transcript auto-follows, and a row that grows when the pointer
 * crosses it moves the thing being read.
 */
interface RowTimeTooltip {
    @Composable
    fun Wrap(atSec: Long?, content: @Composable () -> Unit)
}

/** Draws the row and nothing else. See [RowTimeTooltip]. */
object NoRowTimeTooltip : RowTimeTooltip {
    @Composable
    override fun Wrap(atSec: Long?, content: @Composable () -> Unit) = content()
}

val LocalRowTime = staticCompositionLocalOf<RowTimeTooltip> { NoRowTimeTooltip }

/**
 * The text a long-press on this row selects — the WHOLE row, not a word.
 *
 * A long press on a phone is a blunt instrument: it lands wherever the thumb did,
 * and the toolkit's own word-and-drag selection is the thing it is worse than.
 * What it is better at is "this message" — which is a unit the reader can see the
 * boundaries of before pressing, and the unit every quick action actually wants.
 *
 * Empty for a row with nothing to quote (a tool card whose result has not arrived,
 * a spinner), and an empty selection offers no verbs at all — [SelectionMode]
 * already holds that rule.
 */
fun rowSelectionText(ev: TranscriptEvent): String = when (ev.kind) {
    // The command and its output are one row on screen and one quote off it.
    "tool", "tool_result" -> listOfNotNull(
        ev.text?.takeIf { it.isNotBlank() },
        ev.result?.takeIf { it.isNotBlank() },
    ).joinToString("\n\n")
    else -> ev.text.orEmpty()
}.trim()

/**
 * Renders one normalized transcript event. Shared by the session view and the
 * chat view: both read the same Claude Code transcript, so both get thinking,
 * tool calls, subagent output and workflow runs from the same code. Adding a new
 * event kind means changing this file only.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TranscriptEventItem(
    ev: TranscriptEvent,
    onCopy: (String) -> Unit,
) {
    // Subagent output is indented under a marker rather than hidden: during a
    // fan-out it is most of what is happening, but it is not the main thread.
    val indent = if (ev.sidechain) 14.dp else 0.dp
    // The phone's long-press. Wrapped here, once, rather than on each row kind —
    // and only when a shell provided a host, so the desktop gains no gesture and
    // its right-click keeps being the only one.
    //
    // ⚠ It lives INSIDE the shell's SelectionContainer, so the toolkit's own
    // word-drag selection is still there for anyone who wants a phrase; this is
    // the coarser, faster answer for "this message", and it raises the action bar
    // the toolkit has no room to offer. No ripple and no onClick: the rows below
    // own their own taps (a tool card expands) and a
    // clickable parent would take the indication for gestures it never handles.
    val host = LocalTranscriptSelection.current
    val selectable = remember(ev.seq, ev.text, ev.result) {
        if (host == null) "" else rowSelectionText(ev)
    }
    val press = if (host == null || selectable.isEmpty()) Modifier else Modifier.combinedClickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onLongClickLabel = "Select this message",
        onLongClick = { host.onLongPress(selectable, ev.ts) },
        onClick = {},
    )
    // WHICH ROWS CARRY A TIME, decided here rather than row by row: the two that
    // are somebody SAYING something. A tool card, a thinking block, an ask card
    // and a system note are plumbing inside a turn — "when did that tool run" is
    // not a question anyone has, and their taps are already taken by the expand.
    val reveal = LocalRowTime.current
    Box(Modifier.padding(start = indent).then(press)) {
        when (ev.kind) {
            "user" -> reveal.Wrap(ev.ts) { UserBubble(ev.text.orEmpty(), ev.queued) }
            // A usage limit arrives AS an assistant record — Claude Code writes its
            // own error into the transcript the same way it writes an answer — so
            // the kind cannot separate them and the flag has to. See [isLimitNotice].
            "assistant" -> reveal.Wrap(ev.ts) {
                if (isLimitNotice(ev)) LimitNotice(ev) else AssistantBlock(ev, onCopy)
            }
            "thinking" -> ThinkingBlock(ev.text.orEmpty())
            "tool" -> if (ev.ask != null) AskCard(ev) else ToolCard(ev)
            "tool_result" -> ToolResultOrphan(ev)
            "command" -> CommandNote(ev.text.orEmpty(), isResult = false)
            "command_result" -> CommandNote(ev.text.orEmpty(), isResult = true)
            "system" -> SystemNote(ev.text.orEmpty())
            else -> Unit
        }
    }
}

/**
 * Renders one grouped row: an ordinary event, or a run of subagent work as a
 * single openable card. Both conversation surfaces (session and chat) render
 * through this, so subagents and workflows read the same everywhere.
 */
@Composable
fun TranscriptRowItem(row: TranscriptGroups.Row, onCopy: (String) -> Unit) {
    when (row) {
        is TranscriptGroups.Row.Single -> TranscriptEventItem(row.event, onCopy)
        is TranscriptGroups.Row.Subagents -> SubagentsCard(row, onCopy)
    }
}

/**
 * Delegated work as one unit. Closed, it answers "what was farmed out and how
 * much happened"; open, it is the full play-by-play — thinking, tools, results —
 * rendered by the same code as the main thread. Closed by default because during
 * a fan-out the sidechain outweighs the main thread, and the main thread is what
 * is being followed.
 */
@Composable
private fun SubagentsCard(group: TranscriptGroups.Row.Subagents, onCopy: (String) -> Unit) {
    var open by rememberSaveable(group.key) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable { open = !open },
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AccountTree,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Subagent",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${group.steps} step${if (group.steps == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (open) "Collapse" else "Expand",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The parent's own words for the task: the best one-line summary there is.
            group.task?.let { task ->
                Spacer(Modifier.height(3.dp))
                Text(
                    task,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (open) 4 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AnimatedVisibility(open) {
                Column(
                    Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    group.events.forEach { ev ->
                        // Inside the card the sidechain indent is redundant.
                        TranscriptEventItem(ev.copy(sidechain = false), onCopy)
                    }
                }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String, queued: Boolean = false) {
    val metrics = LocalTranscriptMetrics.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            // ORDER IS LOAD-BEARING, and the wrong way round fails silently:
            // `fillMaxWidth` hands DOWN fixed constraints, and a `widthIn` inside
            // fixed constraints can only coerce into them — so the cap is ignored
            // and the bubble spans the whole window. Measured on the desktop
            // client, which is exactly where the cap is the only thing stopping a
            // two-word message from becoming a 1200pt bar. Cap first, fill second.
            modifier = Modifier
                .then(
                    if (metrics.userBubbleMaxWidth == Dp.Unspecified) Modifier
                    else Modifier.widthIn(max = metrics.userBubbleMaxWidth)
                )
                .fillMaxWidth(metrics.userBubbleFraction),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                // A photo the message attached, as an actual thumbnail when the
                // shell provided a loader and the file is still on the host.
                // Loading and EVERY failure (pruned, offline, undecodable, old
                // daemon, no loader) fall back to the pill displayText renders —
                // the behavior this bubble always had.
                val loader = LocalAttachmentImages.current
                val imagePath = remember(text) { AttachmentText.imagePaths(text).firstOrNull() }
                var thumb by remember(imagePath) { mutableStateOf<ImageBitmap?>(null) }
                if (loader != null && imagePath != null) {
                    LaunchedEffect(imagePath) { thumb = loader.load(imagePath) }
                }
                val shown = thumb
                if (shown != null) {
                    Image(
                        bitmap = shown,
                        contentDescription = "Photo attachment",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .widthIn(max = 220.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                    // The words beside the photo, if the message had any; the
                    // thumbnail already says "photo attached".
                    val remainder = remember(text) {
                        AttachmentText.displayText(AttachmentText.stripImageMarkers(text.trim()))
                    }
                    if (remainder.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(remainder, style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    // Attachment markers render as what they mean, not where the
                    // file landed on the daemon.
                    Text(AttachmentText.displayText(text.trim()), style = MaterialTheme.typography.bodyMedium)
                }
                // Sent while Claude was mid-turn: it is waiting its turn, which is
                // worth saying so the message does not look ignored.
                if (queued) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "queued",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
    // Bound to locals: these are public properties of another module (:core), so
    // the compiler will not smart-cast them inside a null check. A local also
    // guarantees the null test and the read see the same value, which is what
    // the smart-cast rule is protecting against.
    val detail = ev.detail
    val input = ev.input
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
                if (!detail.isNullOrBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        detail,
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
            if (!input.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                // A command must not wrap into ambiguity: scroll it instead.
                Box(Modifier.horizontalScroll(rememberScrollState())) {
                    Text(
                        highlighted(input, langForTool(ev.name)),
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
                            highlighted(ev.result.orEmpty(), resultLang(ev.result.orEmpty())),
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

/**
 * An AskUserQuestion, as a question rather than as JSON. The options here are a
 * RECORD of what was asked — the live, tappable version is the prompt card fed
 * from the pane, which appears while the dialog is actually up. Once answered,
 * the chosen reply arrives in the result and is shown in place of the choices.
 */
@Composable
private fun AskCard(ev: TranscriptEvent) {
    val ask = ev.ask ?: return
    // Bound once: `result` is a public property of another module (:core) and so
    // will not smart-cast, and this card tests it three times — a local keeps all
    // three questions about the same value.
    val result = ev.result
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    ask.questions.firstOrNull()?.header ?: "Question",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            ask.questions.forEach { q ->
                Spacer(Modifier.height(5.dp))
                Text(q.question, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (result.isNullOrBlank() && q.options.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    q.options.forEach { opt ->
                        Text(
                            "•  $opt",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 1.dp),
                        )
                    }
                }
            }
            // Answered: what came back matters more than what was offered.
            if (!result.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "→ ${answeredSummary(result)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Waiting for your answer — buttons below, or on the Screen tab",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The chosen option, dug out of the result's JSON-ish text; whole text if not. */
private fun answeredSummary(result: String): String {
    // Results look like {"questions":[…"answer":"Blue"…]} — the answers are the point.
    val answers = Regex("\"answer\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        .findAll(result).map { it.groupValues[1] }.toList()
    return if (answers.isNotEmpty()) answers.joinToString("  ·  ") else result.take(120)
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

/**
 * A slash command and its output. Shown as a compact centred note because it is
 * something that happened to the session, not something anyone said — which is
 * exactly how the raw `<command-name>` records misread before.
 */
@Composable
private fun CommandNote(text: String, isResult: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isResult) 0.35f else 0.6f),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = if (isResult) FontFamily.Default else FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/**
 * Whether an event is the wall a session ran into rather than something it said.
 *
 * `apiError` is the daemon lifting `isApiErrorMessage` off the raw record; 429 is
 * the only status that means "out of usage" rather than "something broke", and
 * the distinction matters because the two want opposite things from the reader:
 * a 500 is worth retrying now, a 429 is worth waiting for.
 *
 * Absent on a daemon older than 3.0.0, which is the compat answer this file
 * needs: the event falls through to an ordinary assistant bubble showing the
 * error text, exactly as it does today.
 */
fun isLimitNotice(ev: TranscriptEvent): Boolean = ev.apiError == LIMIT_STATUS

/** The one API status that means headroom rather than failure. */
const val LIMIT_STATUS: Int = 429

/**
 * The clock time Claude Code itself printed, lifted out of its own error text.
 *
 * ⚠ NEVER FORMATTED HERE. `:ui` is common code with no timezone database, and the
 * one time a shell turned an instant into a wall clock by hand it printed UTC as
 * if it were local. The only wall clock this surface may show is the one that
 * arrived as text, which is what this reads — and null, meaning "say nothing", is
 * a perfectly good answer.
 *
 * Delegates to [HeadroomRules.resetClockOf], which is the one implementation:
 * the session mark reads the same clock out of the same sentence, and two copies
 * of this parse are two sets of words for one reading.
 */
fun limitResetClock(text: String?): String? = HeadroomRules.resetClockOf(text)

/** The notice's headline. */
fun limitNoticeTitle(ev: TranscriptEvent): String {
    val clock = limitResetClock(ev.text)
    return if (clock == null) "Usage limit hit" else "Usage limit hit · resets $clock"
}

/**
 * A session sitting on a usage limit, drawn as what it is.
 *
 * NOT an assistant bubble. The error text renders as prose — it is prose — and as
 * a bubble it reads as Claude answering a question, which is how a session that
 * stopped hours ago looks like one that is still working. The second line is what
 * the reader actually needs: whether anything is going to pick it back up.
 */
@Composable
private fun LimitNotice(ev: TranscriptEvent) {
    val session = LocalSessionHeadroom.current
    val resume = HeadroomRules.resumeWords(
        stalled = true,
        // Without a session's own headroom (a chat transcript, an older daemon)
        // the global default is the honest guess, and the daemon's default is on.
        autoResume = session?.autoResume ?: true,
        resetClock = limitResetClock(ev.text),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Surface(
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
            contentColor = MaterialTheme.colorScheme.error,
            shape = RoundedCornerShape(10.dp),
        ) {
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    limitNoticeTitle(ev),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (resume != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        resume,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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

/** What a tool's input is written in, for colouring purposes. */
fun langForTool(name: String?): String = when (name) {
    "Bash", "BashOutput" -> "shell"
    "Read", "Glob", "Grep", "Edit", "Write", "NotebookEdit" -> "plain"
    else -> "plain"
}

/** Tool output that looks like a diff gets diff colouring; everything else plain. */
fun resultLang(result: String): String =
    if (result.lineSequence().take(6).any { it.startsWith("+") || it.startsWith("-") || it.startsWith("@@") })
        "diff" else "plain"
