package com.silencelen.huginn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.silencelen.huginn.data.ContextBreakdown
import com.silencelen.huginn.data.ContextUsage
import com.silencelen.huginn.data.FastMode
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.Queued
import com.silencelen.huginn.data.SessionContext
import com.silencelen.huginn.ui.system.Scale
import com.silencelen.huginn.ui.theme.LocalHuginnColors
import com.silencelen.huginn.ui.theme.Severity
import com.silencelen.huginn.ui.theme.severityColor
import kotlinx.coroutines.delay

/**
 * The context card: what is in the window, behind the meter.
 *
 * The meter says `47%`. This says what the 47 % is made of — Claude Code's own
 * `/context` table, parsed by the daemon from the transcript (appd 3.14.0,
 * verified on Claude Code 2.1.306) — plus the live reading, the model, the
 * effort and whether fast mode is on. One composable for both apps; the desktop
 * opens it as a popup under the meter and the phone as a bottom sheet
 * ([ContextMeterCard]).
 *
 * The table is as old as the last `/context` the session ran. The card says so
 * ("as of 14m ago") and offers Refresh, which asks the daemon to type `/context`
 * at the next turn boundary — queued, not immediate, and the card says that too
 * rather than spinning.
 */

/** What the shell already has in hand for a session: the row's or the screen's readings. */
data class ContextCardFacts(
    val usage: ContextUsage? = null,
    /** The older daemon's whole-percent reading, the fallback when [usage] is null. */
    val contextPercent: Int? = null,
    val model: String? = null,
    val effort: String? = null,
    val fast: FastMode? = null,
)

/** Above this the total tints to the warning step, like the meter. */
private const val CONTEXT_HIGH = 80

@Composable
fun ContextCard(
    facts: ContextCardFacts,
    breakdown: ContextBreakdown?,
    nowMs: Long,
    refreshNote: String?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val percent = facts.usage?.let { ContextFormat.percentOf(it) } ?: facts.contextPercent
    val high = (percent ?: 0) >= CONTEXT_HIGH || facts.usage?.exceeds200k == true
    Column(modifier.testTag(CONTEXT_CARD_TAG), verticalArrangement = Arrangement.spacedBy(Scale.s8)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Context", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            val total = ContextFormat.totalLine(facts.usage, breakdown)
                ?: facts.contextPercent?.let { "$it% of the window" }
            if (total != null) {
                Text(
                    total,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (high) severityColor(Severity.WARNING) else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.testTag("context.total"),
                )
            }
        }
        // The session's settings as one quiet line: the model, the effort and
        // fast mode are facts about the window, not controls — the header has
        // the controls.
        val settings = listOfNotNull(
            facts.model?.takeIf { it.isNotBlank() },
            facts.effort?.takeIf { it.isNotBlank() }?.let { "effort $it" },
            facts.fast?.let { if (it.on) "fast on" else if (it.available) "fast off" else "fast unavailable" },
        )
        if (settings.isNotEmpty()) {
            Text(
                settings.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (breakdown != null && breakdown.categories.isNotEmpty()) {
            val rows = ContextFormat.rows(breakdown)
            StackedBar(rows)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (row in rows) CategoryRow(row)
            }
        } else {
            Text(
                "No breakdown from this session yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("context.none"),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val asOf = breakdown?.let { ContextFormat.asOf(it.asOf, nowMs) }.orEmpty()
            Text(
                asOf,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh, modifier = Modifier.testTag("context.refresh")) { Text("Refresh") }
        }
        if (refreshNote != null) {
            Text(
                refreshNote,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("context.note"),
            )
        }
    }
}

/**
 * The window as one bar, coloured by KIND rather than by category: what is
 * spent, what is held back for compaction, what is deferred, what is free. Three
 * tones from the existing palette plus the surface — the accent for what is in
 * use, the 3:1 stroke grey for the buffer, the second voice for deferred — and
 * no new colour.
 */
@Composable
private fun StackedBar(rows: List<ContextFormat.Row>) {
    Row(
        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        for (row in rows) {
            val w = row.share.toFloat().coerceIn(0f, 1f)
            if (w <= 0f) continue
            Box(Modifier.weight(w).fillMaxWidth().height(6.dp).background(kindColor(row.category.kind)))
        }
    }
}

@Composable
private fun CategoryRow(row: ContextFormat.Row) {
    Row(Modifier.fillMaxWidth().testTag("context.row.${row.category.id}"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(Scale.dotQuiet).clip(CircleShape).background(kindColor(row.category.kind)))
        Spacer(Modifier.width(Scale.s8))
        Text(
            row.category.name,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(
            ContextFormat.tokens(row.category.tokens),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.width(56.dp),
        )
        Text(
            row.percentWords,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp),
        )
    }
}

/** The kind's tone, from the theme and nowhere else. */
@Composable
fun kindColor(kind: String): Color = when (kind) {
    "free" -> MaterialTheme.colorScheme.surfaceContainerHighest
    "buffer" -> MaterialTheme.colorScheme.outline
    "deferred" -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.primary
}

/** How a shell presents the card: under the meter, or as a sheet from the bottom. */
enum class ContextCardPresentation { POPUP, SHEET }

/**
 * How a card reaches the daemon: the two calls, handed in by the shell so the
 * card never holds a client of its own. The desktop passes `store.client`'s
 * [HuginnClient.sessionContext] and [HuginnClient.refreshSessionContext] for
 * the session; the phone passes its view model's.
 */
class ContextSource(
    val load: suspend () -> SessionContext,
    val refresh: suspend () -> Queued,
)

/**
 * The context meter with its card behind it: the shared wiring both apps use.
 *
 * Draws [ContextMeter] from the live reading (or the older daemon's percent),
 * opens the card on a click, reads the breakdown while the card is open — every
 * few seconds, so a queued refresh lands without being asked for again — and
 * sends Refresh, showing the daemon's answer under the table.
 *
 * Draws nothing when the host reported no figure at all, like the meter did.
 */
@Composable
fun ContextMeterCard(
    name: String,
    source: ContextSource,
    facts: ContextCardFacts,
    nowMs: Long,
    presentation: ContextCardPresentation,
    modifier: Modifier = Modifier,
) {
    val percent = facts.usage?.let { ContextFormat.percentOf(it) } ?: facts.contextPercent ?: return
    var open by remember(name) { mutableStateOf(false) }
    var anchorHeight by remember { mutableStateOf(0) }
    Box(modifier.onSizeChanged { anchorHeight = it.height }) {
        ContextMeter(percent, onClick = { open = true })
        if (open) {
            val content: @Composable () -> Unit = {
                ContextCardBody(name, source, facts, nowMs)
            }
            when (presentation) {
                ContextCardPresentation.POPUP -> ContextCardPopup(anchorHeight, onDismiss = { open = false }, content)
                ContextCardPresentation.SHEET -> ContextCardSheet(onDismiss = { open = false }, content)
            }
        }
    }
}

/**
 * The card, fed: reads the breakdown while shown and carries the Refresh answer.
 *
 * @param pollMs how often the table is re-read while the card is up, so a
 *   queued refresh lands without being asked for again. Zero reads it once:
 *   that is for a test, where an endless `delay` loop under the test clock
 *   never lets the composition go idle (`waitForIdle` spun for 13 hours on it).
 */
@Composable
fun ContextCardBody(
    name: String,
    source: ContextSource,
    facts: ContextCardFacts,
    nowMs: Long,
    pollMs: Long = CARD_POLL_MS,
) {
    var breakdown by remember(name) { mutableStateOf<ContextBreakdown?>(null) }
    var live by remember(name) { mutableStateOf<ContextUsage?>(null) }
    var note by remember(name) { mutableStateOf<String?>(null) }
    var refreshes by remember(name) { mutableStateOf(0) }
    LaunchedEffect(name, refreshes, pollMs) {
        while (true) {
            runCatching { source.load() }.onSuccess { c ->
                breakdown = c.breakdown
                live = c.context
            }
            if (pollMs <= 0L) break
            delay(pollMs)
        }
    }
    ContextCard(
        // The route's own reading first — it is the freshest thing — then what
        // the shell had in hand when it opened the card.
        facts = facts.copy(usage = live ?: facts.usage),
        breakdown = breakdown,
        nowMs = nowMs,
        refreshNote = note,
        onRefresh = { refreshes++ },
    )
    // The refresh itself, once per press: the counter restarts the reader above
    // so the answer is polled for from the moment it was asked.
    LaunchedEffect(name, refreshes) {
        if (refreshes == 0) return@LaunchedEffect
        note = runCatching { source.refresh() }
            .fold({ ContextFormat.refreshNote(it) }, { it.message ?: ContextFormat.refreshNote(false, null) })
    }
}

/** Under the meter, right-aligned, dismissed by a click outside or Escape. */
@Composable
private fun ContextCardPopup(anchorHeightPx: Int, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, anchorHeightPx + POPUP_GAP_PX),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        // ⚠ A POPUP IS A SEPARATE LAYOUT ROOT that inherits the caller's
        // composition locals: a Text here would register with the transcript's
        // selection registrar and the next press-drag throws "layouts are not
        // part of the same hierarchy" (OverlaysDisableSelectionTest). The whole
        // content lambda is one DisableSelection block, as that rule demands.
        DisableSelection {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(Scale.radiusSheet),
                border = BorderStroke(1.dp, LocalHuginnColors.current.outlineHairline),
                modifier = Modifier.widthIn(min = 300.dp, max = 380.dp),
            ) {
                Box(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(Scale.s16)) { content() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextCardSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // Capped and scrolled inside, like the Overview's node sheet: a sheet has
        // no more room than the window it opens in.
        Box(
            Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp).padding(bottom = 28.dp),
        ) { content() }
    }
}

const val CONTEXT_CARD_TAG: String = "context.card"
private const val CARD_POLL_MS: Long = 5_000
private const val POPUP_GAP_PX: Int = 6
