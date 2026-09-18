package com.silencelen.huginn.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Plan
import com.silencelen.huginn.data.ProjectDashboard
import com.silencelen.huginn.data.ProjectDashboardMember

/**
 * A cluster, seen whole: what it is doing, who is stuck, and the proposal waiting
 * for an answer.
 *
 * Built from the same numbers a single session's overview is built from —
 * [StatsHeader] and [ProjectionsCard] are reused rather than re-drawn, which is
 * why they stopped being `private` in `SessionOverviewView.kt`. A cluster's
 * header that looked different from a session's header would be two vocabularies
 * for one set of facts.
 *
 * ⚠ NEEDS-YOU ROWS COME FIRST, and that is the screen's reason to exist. Twelve
 * rows in role order put the one session sitting on a permission dialog wherever
 * the alphabet leaves it; [ProjectRules.ordered] puts it at the top.
 *
 * ⚠⚠ THE PER-MEMBER DISCLOSURE ANIMATES HEIGHT ONLY — same trap, same gate, as
 * [ProjectsListView]. In the desktop's detail pane a width-changing expansion
 * re-measures the splitter.
 */
@Composable
fun ProjectDashboardView(
    dashboard: ProjectDashboard?,
    nowMs: Long,
    onOpenMember: (ProjectDashboardMember) -> Unit,
    modifier: Modifier = Modifier,
    /** Drawn under the header when the lead has proposed a cluster. */
    manifest: (@Composable () -> Unit)? = null,
    /** For the pace card, exactly as a session overview uses it. Null hides it. */
    plan: Plan? = null,
) {
    if (dashboard == null) {
        Text(
            "Nothing to show yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(14.dp),
        )
        return
    }
    val members = remember(dashboard.members) {
        ProjectRules.ordered(dashboard.members.map { it.asMember() })
            .mapNotNull { row -> dashboard.members.firstOrNull { it.name == row.name && it.role == row.role } }
    }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(
            dashboardCaption(dashboard, nowMs),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp),
        )
        dashboard.project?.cwd?.trim()?.takeIf { it.isNotEmpty() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp),
            )
        }
        // Only when the daemon actually walked the transcripts. A header of zeroes
        // is worse than no header: it reads as a cluster that has done nothing.
        dashboard.totals?.let {
            StatsHeader(it, dashboard.rate, nowMs)
            ProjectionsCard(dashboard.rate, plan, nowMs)
            Spacer(Modifier.height(10.dp))
        }
        manifest?.let {
            it()
            Spacer(Modifier.height(10.dp))
        }
        Text(
            "MEMBERS",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp),
        )
        if (members.isEmpty()) {
            Text(
                "No members yet — the lead is still sizing this one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            )
            return@Column
        }
        members.forEach { m ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DashboardMemberRow(m, nowMs, onOpen = { onOpenMember(m) })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DashboardMemberRow(
    member: ProjectDashboardMember,
    nowMs: Long,
    onOpen: () -> Unit,
) {
    var open by remember(member.name) { mutableStateOf(false) }
    val row = remember(member) { member.asMember() }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen)
                .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MemberDot(ProjectRules.stateWord(row))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    member.role.ifBlank { member.name },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    memberSubtitle(member, nowMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (open) "▾" else "▸",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { open = !open }.padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        // ⚠⚠ HEIGHT ONLY — see the file KDoc and DisclosureHeightOnlyTest.
        Column(Modifier.fillMaxWidth().animateContentSize()) {
            if (!open) return@Column
            Column(
                Modifier.fillMaxWidth().padding(start = 31.dp, end = 14.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                memberDetailLines(member).forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The dashboard's one-line heading: the project's name and the rollup.
 *
 * The rollup is the same sentence the list row carries, deliberately: a person
 * arriving here from that row should recognise the line they tapped.
 */
fun dashboardCaption(dashboard: ProjectDashboard, nowMs: Long): String {
    val name = dashboard.project?.let { ProjectRules.label(it) }?.takeIf { it.isNotEmpty() }
    val rollup = ProjectRules.rollupWords(dashboard.members.map { it.asMember() })
    val bits = mutableListOf<String>()
    if (name != null) bits += name
    bits += rollup
    agoWords(dashboard.updatedAt, nowMs).takeIf { it.isNotBlank() }?.let { bits += "as of $it" }
    return bits.joinToString(" · ")
}

/** The line under a member's role: what it is doing and when it last did anything. */
fun memberSubtitle(member: ProjectDashboardMember, nowMs: Long): String {
    val row = member.asMember()
    val bits = mutableListOf(ProjectRules.memberWords(row))
    member.pendingSends?.takeIf { it > 0 }?.let {
        bits += if (it == 1) "1 send waiting" else "$it sends waiting"
    }
    agoWords(member.lastActivityTs, nowMs).takeIf { it.isNotBlank() }?.let { bits += it }
    return bits.joinToString(" · ")
}

/**
 * What the disclosure reveals: the model cell, the agents, the peer name.
 *
 * ⚠ THE AGENT COUNT, NOT THE AGENT IDS. An agent id is scoped to the session that
 * spawned it, so a project-wide list of them would be a list of handles that
 * address nothing from here — the delta calls this out explicitly. The count is
 * a fact; a link would be a broken promise.
 */
fun memberDetailLines(member: ProjectDashboardMember): List<String> {
    val lines = mutableListOf<String>()
    member.name.takeIf { it.isNotBlank() }?.let { lines += "peer name  $it" }
    member.headroom?.let { h ->
        val cell = mutableListOf<String>()
        h.family?.let { cell += it }
        h.ladder?.let { cell += "moved to $it" }
        if (h.stalled) cell += "stopped at the usage limit"
        if (!h.autoResume) cell += "auto-resume off"
        if (cell.isNotEmpty()) lines += "model  " + cell.joinToString(" · ")
    }
    val live = member.streams.count { it.active }
    if (member.streams.isNotEmpty()) {
        lines += "agents  " + if (live > 0) "${member.streams.size} · $live live" else "${member.streams.size}"
    }
    member.totals?.let { t ->
        lines += "work  ${t.turns} turns · ${t.toolCalls} tools" +
            if (t.errors > 0) " · ${t.errors} tool errors" else ""
    }
    return lines
}
