package com.silencelen.huginn.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.GraphAgent
import com.silencelen.huginn.data.GraphNode
import com.silencelen.huginn.data.Plan
import com.silencelen.huginn.data.SessionGraph
import com.silencelen.huginn.data.SessionMetaSaver
import com.silencelen.huginn.data.SessionOverview
import com.silencelen.huginn.ui.theme.LocalSyntaxColors
import com.silencelen.huginn.ui.work.PulseDot
import com.silencelen.huginn.ui.work.SettledDot

/**
 * A place to rest during a long run.
 *
 * Four things, in the order somebody arriving actually wants them: what this
 * session has spent, where the pace lands, what they told themselves they were
 * doing, and a map of what it has been doing while they were away.
 *
 * The map is the part worth explaining. It is a spine of BLOCKS — one per batch
 * of work, one per thing the person said, a hairline where the context was
 * compacted — with agent lifelines in a gutter to the right: a line leaves the
 * block that spawned an agent and curves back into the block its result came
 * home to. Which column each line gets is decided in [GraphLayout], in `:core`,
 * where it is tested; nothing here decides anything.
 *
 * House rules observed: no left accent bars anywhere, a breathing dot rather than
 * a badge for live work, and every estimate says out loud that it is one — the
 * pace line hedges in words, and the API-list-rate cost never appears without the
 * caption that says whose money it is not.
 */

enum class OverviewDensity { COMPACT, COMFORTABLE }

/**
 * How a block is tinted. A ROLE rather than a colour: the mapping to the scheme
 * belongs to the renderer, and the decision — which is what a reader is reading —
 * belongs somewhere it can be asserted.
 */
enum class BlockTone {
    /** Something the person said. The one kind that is not the session working. */
    SAID,
    WORK,
    SPOKE,

    /** A break in the conversation. Drawn as a hairline, not as a block. */
    BREAK,
}

/** What a lifeline's colour is SAYING, before it is a colour. */
enum class LaneTone {
    /** Still going. The one that gets the live tint. */
    LIVE,

    /** Came back an error. */
    FAILED,

    /** Ran, but nothing ever recorded how it ended, or where it belongs. */
    LOOSE,

    /** Settled normally: its own hue, so a fan-out reads as one thing. */
    OWN,
}

fun blockTone(kind: String): BlockTone = when (kind) {
    "user" -> BlockTone.SAID
    "response" -> BlockTone.SPOKE
    "compact" -> BlockTone.BREAK
    // Anything a newer daemon invents is still work that happened; drawing it as
    // a plain block beats leaving a hole in the map.
    else -> BlockTone.WORK
}

fun laneTone(status: String): LaneTone = when (status) {
    "running" -> LaneTone.LIVE
    "failed" -> LaneTone.FAILED
    "stalled", "orphan" -> LaneTone.LOOSE
    else -> LaneTone.OWN
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionOverviewView(
    overview: SessionOverview?,
    graph: SessionGraph?,
    plan: Plan?,
    nowMs: Long,
    goals: String,
    notes: String,
    saveState: SessionMetaSaver.State,
    density: OverviewDensity,
    onGoals: (String) -> Unit,
    onNotes: (String) -> Unit,
    onDensity: (OverviewDensity) -> Unit,
    modifier: Modifier = Modifier,
    /** Why there is nothing to show — a plain shell, or a first prompt not yet sent. */
    unavailable: String? = null,
    note: String? = null,
    onDismissNote: () -> Unit = {},
) {
    var open by remember { mutableStateOf<GraphNode?>(null) }
    val totals = graph?.totals ?: overview?.totals
    val rate = graph?.rate ?: overview?.rate
    val layout = remember(graph) { graph?.let { GraphLayout.layout(it) } ?: GraphLayout.Result() }
    val agentsById = remember(graph) { graph?.agents.orEmpty().associateBy { it.id } }

    LazyColumn(modifier.fillMaxSize()) {
        if (unavailable != null) {
            item { Quiet(unavailable, Modifier.padding(horizontal = 14.dp, vertical = 18.dp)) }
        }
        if (totals != null) {
            item { StatsHeader(totals, rate, nowMs) }
            item { ProjectionsCard(rate, plan, nowMs) }
        }
        item {
            NotesCard(goals, notes, saveState, onGoals, onNotes, note, onDismissNote)
        }
        if (layout.rows.isNotEmpty()) {
            item { MapHeader(layout, density, onDensity) }
            items(layout.rows, key = { it.node.id.ifEmpty { "row${it.index}" } }) { row ->
                MapRow(row, layout.laneCount, density, agentsById) { open = row.node }
            }
            if (layout.unplaced.isNotEmpty()) {
                item {
                    Quiet(
                        "${layout.unplaced.size} agent${if (layout.unplaced.size == 1) "" else "s"} could not be " +
                            "placed on the map — their branch point is older than the last compaction. " +
                            "Their tokens are still counted above.",
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }

    open?.let { node ->
        ModalBottomSheet(onDismissRequest = { open = null }) {
            // A block with twenty agents under it is ORDINARY here, and a sheet has
            // no more room than the window it opens in: uncapped and unscrolled,
            // the list simply ran past the bottom edge and the last agents could
            // not be reached at all. The cap is what gives the scroller something
            // to scroll INSIDE — a sheet that is only as tall as it needs to be
            // still is, because this is a maximum rather than a height.
            NodeDetail(
                node = node,
                agents = node.agents.mapNotNull { agentsById[it] },
                modifier = Modifier
                    .heightIn(max = SHEET_MAX)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 28.dp),
            )
        }
    }
}

// ------------------------------------------------------------------- header

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsHeader(
    totals: com.silencelen.huginn.data.GraphTotals,
    rate: com.silencelen.huginn.data.GraphRate?,
    nowMs: Long,
) {
    // Null when nothing in the transcript carried usage — then there is no chip
    // and no caption, rather than a $0.00 nobody could stand behind.
    val cost = OverviewFormat.costStat(totals.estCost, totals.agentEstCostUsd)
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (rate?.activeRecently == true) PulseDot(MaterialTheme.colorScheme.primary) else SettledDot()
            Spacer(Modifier.width(9.dp))
            Text(
                OverviewFormat.durationWords(totals.wallMs),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                PlanFormat.compactTokens(totals.tokens.all),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Stat("turns", totals.turns.toString())
            Stat("you said", totals.userMessages.toString())
            Stat("tools", totals.toolCalls.toString())
            if (totals.filesTouched > 0) Stat("files", totals.filesTouched.toString())
            if (totals.agentCount > 0) {
                Stat(if (totals.agentCount == 1) "agent" else "agents",
                    if (totals.activeAgents > 0) "${totals.agentCount} · ${totals.activeAgents} live"
                    else totals.agentCount.toString())
            }
            if (totals.compactions > 0) {
                Stat("compacted", "${totals.compactions}× · ${PlanFormat.compactTokens(totals.droppedTokens)} dropped")
            }
            if (totals.errors > 0) Stat("tool errors", totals.errors.toString())
            // Last because it is the widest chip by far — the agents' share rides
            // in the value the way "3 · 1 live" does — so it wraps to its own row
            // instead of shunting the short ones into a ragged one.
            cost?.let { Stat("api cost", it.statValue) }
        }
        Spacer(Modifier.height(6.dp))
        // The cache share is stated because it is most of the total and almost
        // none of the work: "620M tokens" with no note that 99% was a re-read of
        // context already paid for is a number that starts arguments.
        Text(
            buildString {
                append(PlanFormat.compactTokens(totals.tokens.written)).append(" written · ")
                append(OverviewFormat.cacheShare(totals.tokens.cacheRead, totals.tokens.all))
                append(" of the total was cache reads")
                if (totals.agentTokens.all > 0) {
                    append(" · ").append(PlanFormat.compactTokens(totals.agentTokens.all)).append(" of it in agents")
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The caption belongs beside the share line and in the same voice: quiet,
        // no icon, no colour. It is the reason the figure is allowed on a screen
        // at all, and a warning tint would make a subscription-covered estimate
        // look like a charge to answer for.
        cost?.let {
            Text(
                it.captionLine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (totals.models.isNotEmpty()) {
            Text(
                (totals.models.map { shortModel(it) } + totals.efforts).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** claude-opus-5 → opus-5. The vendor prefix is the same on every row. */
private fun shortModel(id: String) = id.removePrefix("claude-").removeSuffix("-latest")

@Composable
private fun Stat(label: String, value: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProjectionsCard(
    rate: com.silencelen.huginn.data.GraphRate?,
    plan: Plan?,
    nowMs: Long,
) {
    if (rate == null) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text("Pace", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row {
                Text(
                    "${OverviewFormat.burnWords(rate.tokensPerMin10)} over 10m",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "${OverviewFormat.burnWords(rate.tokensPerMin60)} over 1h",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OverviewFormat.paceLine(rate, plan, nowMs)?.let { line ->
                Spacer(Modifier.height(6.dp))
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // The hedge is a separate line rather than more words in the
                // sentence: a straight line drawn through ten minutes is not a
                // forecast, and the sentence is long enough already.
                Text(
                    "an estimate, from the current rate",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            plan?.limits.orEmpty().filter { it.resetsAt != null }.take(3).forEach { limit ->
                PlanFormat.resetLabel(limit.resetsAt, nowMs)?.let { words ->
                    Text(
                        "${limit.label} — ${limit.percent.toInt()}% used, $words",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------- notes

/** IDLE says nothing: "Saved" over text nobody touched is a claim about work that never happened. */
fun metaSaveWords(state: SessionMetaSaver.State): String = when (state) {
    SessionMetaSaver.State.IDLE -> ""
    SessionMetaSaver.State.PENDING -> "Editing…"
    SessionMetaSaver.State.SAVING -> "Saving…"
    SessionMetaSaver.State.SAVED -> "Saved"
    SessionMetaSaver.State.FAILED -> "Not saved"
}

@Composable
private fun NotesCard(
    goals: String,
    notes: String,
    saveState: SessionMetaSaver.State,
    onGoals: (String) -> Unit,
    onNotes: (String) -> Unit,
    note: String?,
    onDismissNote: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Goals & notes", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                metaSaveWords(saveState),
                style = MaterialTheme.typography.labelSmall,
                color = if (saveState == SessionMetaSaver.State.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = goals,
            onValueChange = onGoals,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("What this run is for") },
            textStyle = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = notes,
            onValueChange = onNotes,
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            placeholder = { Text("Anything worth remembering when you come back") },
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        note?.let {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismissNote) { Text("ok") }
            }
        }
    }
}

// ---------------------------------------------------------------- the map

@Composable
private fun MapHeader(
    layout: GraphLayout.Result,
    density: OverviewDensity,
    onDensity: (OverviewDensity) -> Unit,
) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("The session so far", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            // One control, two states, same verb — the house rule about controls
            // that unify rather than a pair of buttons only one of which does
            // anything at a time.
            TextButton(onClick = {
                onDensity(if (density == OverviewDensity.COMPACT) OverviewDensity.COMFORTABLE else OverviewDensity.COMPACT)
            }) {
                Text(if (density == OverviewDensity.COMPACT) "Roomier" else "Tighter")
            }
        }
    }
}

private val LANE_W = 14.dp
private val INDENT_W = 3.dp

/**
 * The map's own margin from the right edge of the pane.
 *
 * The gutter used to end exactly ON it: the outermost lane sits half a lane width
 * in, so its stroke landed within a pixel of the column edge and a workflow
 * bracket — the widest thing the gutter ever draws — read as part of the window
 * frame rather than as part of the map.
 */
private val MAP_END = 8.dp

/**
 * Between a block's right edge and the lifeline that leaves it.
 *
 * Without it the connector starts flush against the row's token count, and a
 * hairline touching the end of "83.1k" is a strikethrough, not a branch.
 */
private val LANE_GAP = 5.dp

/**
 * How far the connector's control point is pushed off the chord.
 *
 * Small on purpose. This is the whole difference between a branch and a line
 * through the numbers, and a quadratic deviates half its control offset at the
 * midpoint — so the drawn bow is half of this.
 */
private val LANE_BOW = 6.dp

@Composable
private fun MapRow(
    row: GraphLayout.Row,
    laneCount: Int,
    density: OverviewDensity,
    agentsById: Map<String, GraphAgent>,
    onClick: () -> Unit,
) {
    val node = row.node
    val compact = blockTone(node.kind) == BlockTone.BREAK
    val height: Dp = when {
        compact -> 26.dp
        density == OverviewDensity.COMPACT -> 46.dp
        else -> 64.dp
    }
    val scheme = MaterialTheme.colorScheme
    val syntax = LocalSyntaxColors.current
    val hues = remember(syntax) {
        listOf(syntax.function, syntax.string, syntax.keyword, syntax.number, syntax.meta, syntax.comment)
    }
    val gutter = LANE_W * laneCount
    val tone = blockTone(node.kind)
    val fill = when (tone) {
        BlockTone.SAID -> scheme.primary.copy(alpha = 0.16f)
        BlockTone.WORK -> scheme.surfaceVariant
        BlockTone.SPOKE -> scheme.surfaceVariant.copy(alpha = 0.4f)
        BlockTone.BREAK -> Color.Transparent
    }
    val edge = if (node.errors > 0) scheme.error.copy(alpha = 0.55f) else Color.Transparent

    Box(Modifier.fillMaxWidth().height(height).clickable(onClick = onClick)) {
        Canvas(Modifier.fillMaxSize()) {
            val gutterPx = gutter.toPx()
            val endPx = MAP_END.toPx()
            val right = size.width - 14.dp.toPx() - gutterPx - endPx
            val top = 3.dp.toPx()
            val bottom = size.height - 3.dp.toPx()
            val left = 14.dp.toPx()
            val mid = size.height / 2f

            if (compact) {
                // A hairline, not a block. The context break is a fact about the
                // conversation rather than something the session DID.
                drawLine(scheme.outline, Offset(left, mid), Offset(right, mid), strokeWidth = 1f)
            } else {
                val corner = CornerRadius(9.dp.toPx())
                drawRoundRect(
                    color = fill,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    cornerRadius = corner,
                )
                if (edge != Color.Transparent) {
                    drawRoundRect(
                        color = edge,
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                        cornerRadius = corner,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
            }

            for (lane in row.lanes) {
                val agent = agentsById[lane.agentId]
                val laneTone = laneTone(lane.status)
                val color = when (laneTone) {
                    LaneTone.FAILED -> scheme.error
                    LaneTone.LIVE -> scheme.primary
                    LaneTone.LOOSE -> scheme.outline
                    LaneTone.OWN -> hues.getOrElse(lane.hue % hues.size) { scheme.primary }
                }
                val alpha = if (laneTone == LaneTone.LIVE) 0.95f else 0.7f
                val x = size.width - endPx - gutterPx + lane.lane * LANE_W.toPx() +
                    LANE_W.toPx() / 2f + lane.indent * INDENT_W.toPx()
                // The connector starts CLEAR of the block, not on its edge: the
                // row's token count is drawn hard against that edge, so a line
                // beginning there strikes through it.
                drawLane(
                    lane.phase,
                    x,
                    right + LANE_GAP.toPx(),
                    mid,
                    color.copy(alpha = alpha),
                    1.6.dp.toPx(),
                )
            }
        }
        Row(
            // The end inset lands the text INSIDE the block rather than 4dp past
            // its rounded corner, which is where the token count used to sit — and
            // is why the lifeline leaving that block appeared to cross it.
            Modifier.fillMaxSize().padding(start = 24.dp, end = 18.dp + MAP_END + gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    node.label.ifEmpty { node.kind },
                    style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                    fontWeight = if (node.kind == "user") FontWeight.SemiBold else FontWeight.Normal,
                    color = if (compact) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = if (density == OverviewDensity.COMFORTABLE && !compact) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!compact && density == OverviewDensity.COMFORTABLE) {
                    node.detail?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (!compact && node.tokens.written > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    PlanFormat.compactTokens(node.tokens.written).removeSuffix(" tokens"),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One lifeline's contribution to one row.
 *
 * Kept as arithmetic on a [DrawScope] rather than folded into the row so the
 * shape of a branch is one thing in one place: out of the block on a curve, down
 * the gutter straight, back in on the mirror of the same curve.
 */
private fun DrawScope.drawLane(
    phase: GraphLayout.Phase,
    x: Float,
    blockRight: Float,
    mid: Float,
    color: Color,
    width: Float,
) {
    val top = 0f
    val bottom = size.height
    // Which way the lifeline is HEADING, which is what the connector should lean
    // towards: down the gutter after a spawn, up out of it into a merge.
    val bow = LANE_BOW.toPx()
    when (phase) {
        GraphLayout.Phase.PASS -> drawLine(color, Offset(x, top), Offset(x, bottom), strokeWidth = width)
        GraphLayout.Phase.START -> {
            drawPath(curve(blockRight, mid, x, bow), color, style = Stroke(width = width))
            drawLine(color, Offset(x, mid), Offset(x, bottom), strokeWidth = width)
        }
        GraphLayout.Phase.END -> {
            drawLine(color, Offset(x, top), Offset(x, mid), strokeWidth = width)
            drawPath(curve(blockRight, mid, x, -bow), color, style = Stroke(width = width))
        }
        GraphLayout.Phase.POINT -> {
            drawPath(curve(blockRight, mid, x, bow), color, style = Stroke(width = width))
            drawCircle(color, radius = width * 1.6f, center = Offset(x, mid))
        }
    }
}

/**
 * The connector between a block's edge and a lifeline's column.
 *
 * [bow] is the control point's offset OFF THE CHORD, and it is the whole of this
 * function's reason to exist: with the control point on the chord — which is what
 * this shipped with — `quadraticTo` draws a perfectly straight horizontal line,
 * so every branch on the map was a hairline through the row rather than the curve
 * the code above says it is. Signed, because a spawn and a merge lean opposite
 * ways.
 */
private fun curve(fromX: Float, y: Float, toX: Float, bow: Float): Path = Path().apply {
    moveTo(fromX, y)
    quadraticTo((fromX + toX) / 2f, y + bow, toX, y)
}

// ------------------------------------------------------------------ detail

/**
 * How tall the node sheet is allowed to get.
 *
 * A ceiling rather than a height: a block with one tool call still draws a short
 * sheet. It is deliberately shorter than any window this runs in, so the sheet
 * always ENDS somewhere the reader can see — a sheet clipped by the window edge
 * gives no sign that there is more of it.
 */
private val SHEET_MAX = 420.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NodeDetail(node: GraphNode, agents: List<GraphAgent>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            node.label.ifEmpty { node.kind },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        val facts = buildList {
            if (node.durMs > 0) add(OverviewFormat.durationWords(node.durMs))
            if (node.toolCalls > 0) add("${node.toolCalls} tool calls")
            if (node.files > 0) add("${node.files} files")
            if (node.errors > 0) add("${node.errors} failed")
            if (node.tokens.written > 0) add("${PlanFormat.compactTokens(node.tokens.written)} written")
            if (node.tokens.cacheRead > 0) add("${PlanFormat.compactTokens(node.tokens.cacheRead)} cache read")
            node.dropped?.let { add("${PlanFormat.compactTokens(it)} dropped") }
        }
        if (facts.isNotEmpty()) {
            Text(
                facts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (node.kind != "action" && !node.detail.isNullOrBlank() && node.detail != node.label) {
            Text(node.detail!!, style = MaterialTheme.typography.bodySmall)
        }
        if (node.tools.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                node.tools.forEach { Stat(it.name, it.count.toString()) }
            }
        }
        if (agents.isNotEmpty()) {
            Text(
                "${agents.size} agent${if (agents.size == 1) "" else "s"} from here",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            agents.forEach { GraphAgentCard(it) }
        }
    }
}

@Composable
private fun GraphAgentCard(a: GraphAgent) {
    val running = a.status == "running"
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (running) 0.65f else 0.3f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running) PulseDot(MaterialTheme.colorScheme.primary) else SettledDot()
                Spacer(Modifier.width(8.dp))
                Text(
                    statusWords(a.status),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = when (laneTone(a.status)) {
                        LaneTone.FAILED -> MaterialTheme.colorScheme.error
                        LaneTone.LIVE -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                a.agentType?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                a.workflowId?.let { wf ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        // wf_b3db6247-567 → b3db6247: enough to tell runs apart.
                        wf.removePrefix("wf_").substringBefore("-"),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (a.tokens.written > 0) {
                    Text(
                        PlanFormat.compactTokens(a.tokens.written).removeSuffix(" tokens"),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            (a.summary ?: a.description)?.let {
                Spacer(Modifier.height(3.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * `stalled` and `orphan` are not failures and must not read as them: a workflow
 * member whose journal never recorded a result, and an agent whose join did not
 * survive a compaction. But neither is "done", and saying so is the difference
 * between a map and a guess.
 */
fun statusWords(status: String) = when (status) {
    "running" -> "working"
    "failed" -> "failed"
    "stalled" -> "stopped without a result"
    "orphan" -> "ran, unplaced"
    else -> "settled"
}

@Composable
private fun Quiet(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
