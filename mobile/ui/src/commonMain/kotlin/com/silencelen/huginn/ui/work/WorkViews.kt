package com.silencelen.huginn.ui.work

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.AgentRun
import com.silencelen.huginn.data.AgentsInfo
import com.silencelen.huginn.data.BgTask
import com.silencelen.huginn.ui.WorkSummary

/**
 * "What is this session doing right now", for both clients.
 *
 * The transcript stays silent until whole blocks complete, so without this a
 * conversation looks dead exactly when the most is happening — right after a
 * message is sent, and right through a twenty-minute build whose only evidence is
 * on the tmux screen. Every decision about WHAT to say lives in
 * [WorkSummary]; this is only how it looks.
 *
 * House rules: no left accent bars, a breathing dot rather than a badge, and one
 * strip that is also the control that opens the detail — same verb, one element.
 */

/**
 * A slow breathing dot. A spinner reads as "the app is busy"; this reads as "the
 * thing over there is busy", which is the truth.
 */
@Composable
fun PulseDot(color: Color, modifier: Modifier = Modifier, size: Int = 8) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val a by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(modifier.size(size.dp).clip(CircleShape).background(color.copy(alpha = a)))
}

/** A settled counterpart to [PulseDot]: present, not animated, not shouting. */
@Composable
fun SettledDot(modifier: Modifier = Modifier, size: Int = 8) {
    Box(modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outline))
}

/**
 * The strip itself: one headline that changes in place, and up to a few detail
 * rows under it.
 *
 * @param live whether to breathe. A lingering strip — kept a few minutes after
 *   the work ended so an agent's conclusion is still reachable — must not keep
 *   pulsing at something that has stopped.
 * @param trailing the affordance at the end, left to the caller because a phone
 *   opens a sheet upward and a desktop expands in place.
 */
@Composable
fun WorkStrip(
    strip: WorkSummary.Strip,
    live: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (live) PulseDot(MaterialTheme.colorScheme.primary) else SettledDot()
                Spacer(Modifier.width(9.dp))
                Text(
                    strip.headline,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                trailing()
            }
            strip.details.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 17.dp, top = 2.dp),
                )
            }
        }
    }
}

/**
 * The strip, opened: everything it compressed, plus the reason it exists — the
 * individual agents behind "0/4 agents done", each with its task and either what
 * it is doing right now or what it concluded.
 *
 * Emits plain rows into whatever container the caller provides, so a phone can put
 * it in a bottom sheet and a desktop in an expanding panel without either owning
 * the other's scrolling.
 */
@Composable
fun WorkDetail(
    title: String,
    statusLines: List<String>,
    tasks: List<BgTask>,
    agents: AgentsInfo?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

        statusLines.forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (tasks.isNotEmpty()) {
            SectionHeading("Background shells")
            tasks.forEach { t ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(9.dp))
                    Text(
                        t.command,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        WorkSummary.agoShort(t.forSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SectionHeading(WorkSummary.agentCount(agents, statusLines) ?: "Agents…")
        val list = agents?.agents ?: emptyList()
        // The server's own clock, carried with the agents: these are files on
        // huginn, and a client clock a minute out would report a live agent as
        // stale or a settled one as writing right now.
        val nowSec = agents?.serverTime ?: 0L
        list.forEach { AgentCard(it, nowSec) }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** One agent: its state, its run, its task, and its last word. */
@Composable
fun AgentCard(a: AgentRun, nowSec: Long, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (a.active) 0.65f else 0.3f),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (a.active) PulseDot(MaterialTheme.colorScheme.primary) else SettledDot()
                Spacer(Modifier.width(8.dp))
                Text(
                    if (a.active) "working" else "settled",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (a.active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                a.workflow?.let { wf ->
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
                Text(
                    WorkSummary.sinceShort(a.updatedAt, nowSec),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            WorkSummary.taskLine(a.task)?.let { task ->
                Spacer(Modifier.size(3.dp))
                Text(
                    task,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // A settled agent's own conclusion beats its last tool call as an
            // epitaph; the live line stays for agents still working.
            val summary = a.summary
            val closing = if (!a.active && !summary.isNullOrBlank()) summary else a.lastLine
            closing?.let { last ->
                Spacer(Modifier.size(3.dp))
                Text(
                    last,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = if (last == summary) FontFamily.Default else FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
