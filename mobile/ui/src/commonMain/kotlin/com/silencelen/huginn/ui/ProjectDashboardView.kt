package com.silencelen.huginn.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.silencelen.huginn.data.ProjectDashboard
import com.silencelen.huginn.data.ProjectDashboardMember

/**
 * A cluster, seen whole: what it is doing, who is stuck, and the proposal waiting
 * for an answer.
 *
 * Built from the same numbers a single session's overview is built from —
 * [StatsHeader] is reused rather than re-drawn, which is why it stopped being
 * `private` in `SessionOverviewView.kt`. A cluster's header that looked different
 * from a session's header would be two vocabularies for one set of facts.
 *
 * ⚠ THE PACE CARD IS NOT HERE, and its absence is a decision. `ProjectionsCard`
 * takes a single session's `GraphRate`; the dashboard's rate is the members'
 * rates ADDED — the same per-minute unit and the same spelling, summed across
 * twelve authors — and projecting one session's burn off that sum would be a
 * number that means nothing.
 * The sum is shown as a rate, in words, and not extrapolated.
 *
 * ⚠ NEEDS-YOU ROWS COME FIRST, and that is the screen's reason to exist. Twelve
 * rows in role order put the one session sitting on a permission dialog wherever
 * the alphabet leaves it; [ProjectRules.ordered] puts it at the top, with the
 * lead directly under it.
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
    /**
     * Adopt and drop, wired. NULL draws no membership controls at all, which is
     * what both shells did before the routes existed and is still the right
     * answer against a daemon that has not got them.
     */
    membership: ProjectMemberActions? = null,
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
    val members = remember(dashboard.members) { ProjectRules.ordered(dashboard.members) }
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
        dashboardPace(dashboard)?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp),
            )
        }
        // Only when the daemon actually walked the transcripts. A header of zeroes
        // is worse than no header: it reads as a cluster that has done nothing.
        dashboard.totals?.let {
            StatsHeader(it, null, nowMs)
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
                PROJECT_NO_MEMBERS,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            )
            return@Column
        }
        members.forEach { m ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DashboardMemberRow(m, nowMs, onOpen = { onOpenMember(m) }, membership = membership)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        membership?.let { AdoptMemberRow(it, members) }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * Adopt and drop, as one value rather than four callbacks in a signature — the
 * [RouteListActions] argument: the vocabulary is what must not be written twice,
 * and it can only live in one file if that file sees the shells' verbs.
 *
 * ⚠ NEITHER VERB TOUCHES A SESSION. Adopt takes a session that is ALREADY
 * RUNNING into the record; drop takes it out and leaves it running. Spawning and
 * ending live elsewhere, on purpose — the daemon's own comment calls a membership
 * verb that quietly did either "the surprise this block exists to avoid".
 */
class ProjectMemberActions(
    /**
     * Live session names this client can see. ⚠ A session belonging to ANOTHER
     * project looks exactly like a free one from here; the daemon holds that join
     * and answers 409 naming the other project, which is a better answer than a
     * row quietly missing from the picker. See [ProjectRules.adoptable].
     */
    val liveSessions: List<String> = emptyList(),
    val onAdopt: (role: String, name: String) -> Unit = { _, _ -> },
    val onDrop: (ProjectDashboardMember) -> Unit = {},
    val busy: Boolean = false,
    /** The daemon's own sentence for a refusal. Shown verbatim, never summarised. */
    val refusal: String? = null,
    val clearRefusal: () -> Unit = {},
)

/**
 * "Add member": pick a session that is already running, give it a role.
 *
 * ⚠ THE FORM REFUSES BEFORE IT CLOSES, the [routeFormRefusal] discipline. The
 * shells' project actions are fire-and-forget, so a refusal that arrived after
 * the form had gone would take the typed role with it and leave a sentence under
 * a list nobody is looking at any more. The role grammar and the cap are read off
 * [ProjectRules] — the same rules the daemon applies, not a second copy.
 */
@Composable
private fun AdoptMemberRow(actions: ProjectMemberActions, members: List<ProjectDashboardMember>) {
    var open by remember { mutableStateOf(false) }
    var role by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }

    val free = remember(actions.liveSessions, members) {
        ProjectRules.adoptable(actions.liveSessions, members)
    }
    val taken = remember(members) { members.map { it.role } }
    val refusal = when {
        !open -> null
        picked.isNullOrBlank() -> PROJECT_ADOPT_NEEDS_SESSION
        else -> ProjectRules.roleProblem(role, taken)
            ?: ProjectRules.capProblem(members.size, 1)
    }

    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    open = !open
                    if (!open) { role = ""; picked = null }
                    actions.clearRefusal()
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) { Text(if (open) "Cancel" else "Add member") }
        }
        if (open) {
            // ⚠ A PICKER, NOT A FREE-TEXT FIELD. The daemon adopts a session BY
            // NAME and 404s on one that is not there; a typed name is a 404
            // waiting to happen, and the list of what is running is right here.
            Box {
                TextButton(
                    onClick = { picking = true },
                    enabled = free.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(picked ?: if (free.isEmpty()) PROJECT_ADOPT_NOTHING_FREE else "Pick a session")
                }
                DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                    free.forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = { picked = name; picking = false },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = role,
                onValueChange = { role = it },
                label = { Text("Role") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        val name = picked ?: return@TextButton
                        actions.onAdopt(role.trim(), name)
                        open = false; role = ""; picked = null
                    },
                    enabled = refusal == null && !actions.busy,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) { Text("Add") }
            }
            (refusal ?: actions.refusal)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        } else {
            // A refusal the DAEMON gave survives the form closing: the commonest
            // one names the other project this session already belongs to, which
            // is the entire fix and is useless if it vanishes with the sheet.
            actions.refusal?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

const val PROJECT_ADOPT_NEEDS_SESSION: String = "Pick the session to bring in."

const val PROJECT_ADOPT_NOTHING_FREE: String = "No other session is running"

/** ⚠ SAYS WHAT IT DOES NOT DO. "Drop" and "end" are one keystroke apart. */
const val PROJECT_DROP_VERB: String = "Drop from project"

const val PROJECT_DROP_NOTE: String = "The session keeps running."

@Composable
private fun DashboardMemberRow(
    member: ProjectDashboardMember,
    nowMs: Long,
    onOpen: () -> Unit,
    membership: ProjectMemberActions? = null,
) {
    var open by remember(member.name) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen)
                .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MemberDot(ProjectRules.stateWord(member))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    memberLabel(member),
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
                // ⚠ INSIDE THE DISCLOSURE, and never on the lead. A destructive-
                // looking verb on a collapsed row is a mis-tap away from a
                // membership edit nobody meant; a reader who opened the row is
                // already reading about that member. The lead cannot be dropped
                // at all — see [ProjectRules.canDrop].
                if (membership != null && ProjectRules.canDrop(member)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { membership.onDrop(member) },
                            enabled = !membership.busy,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(PROJECT_DROP_VERB, color = MaterialTheme.colorScheme.error)
                        }
                        Text(
                            PROJECT_DROP_NOTE,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The dashboard's one-line heading: the project's name and the rollup.
 *
 * The rollup is the same sentence the list row carries, deliberately: a person
 * arriving here from that row should recognise the line they tapped. It is taken
 * off the dashboard's own [ProjectDashboard.project] row, which is the DAEMON'S
 * count — never recomputed from the member list beside it.
 *
 * ⚠ THE CLOCK IS `generatedAt`. It is when this poll was answered, which is the
 * only honest thing to say about a rollup of twelve transcripts.
 */
fun dashboardCaption(dashboard: ProjectDashboard, nowMs: Long): String {
    val row = dashboard.project
    val bits = mutableListOf<String>()
    row?.let { ProjectRules.label(it) }?.takeIf { it.isNotEmpty() }?.let { bits += it }
    bits += if (row != null) ProjectRules.rollupWords(row) else ProjectRules.rollupWords(dashboard.members)
    agoWords(dashboard.generatedAt, nowMs).takeIf { it.isNotBlank() }?.let { bits += "as of $it" }
    return bits.joinToString(" · ")
}

/**
 * The cluster's pace in words, or null when nothing is moving.
 *
 * ⚠ REPORTED, NOT PROJECTED. These are the members' rates added together, so
 * they answer "how fast is this cluster burning right now" and nothing else.
 * Running a single session's projection off them would put a confident
 * time-to-limit on a number that has twelve authors.
 */
fun dashboardPace(dashboard: ProjectDashboard): String? {
    val rate = dashboard.rate ?: return null
    if (rate.tokensPerMin10 <= 0 && rate.tokensPerMin60 <= 0) {
        return if (rate.activeRecently) "active, too little to measure a rate" else null
    }
    // ⚠ THE APP'S NUMBER WORDS, NOT THE RAW LONG (P-33). "41383 tokens/min over
    // 10m · 41383 over 60m" sat two cards away from "561.6k" — one screen, two
    // number systems, and the unformatted one is the harder to read of the two.
    // [OverviewFormat.burnWords] is what the session's own Pace card uses.
    val bits = mutableListOf(
        "${OverviewFormat.burnWords(rate.tokensPerMin10)} over 10m",
        "${OverviewFormat.burnWords(rate.tokensPerMin60)} over 60m",
    )
    if (!rate.activeRecently) bits += "nothing recent"
    return bits.joinToString(" · ")
}

/** The line under a member's role: what it is doing and when it last did anything. */
fun memberSubtitle(member: ProjectDashboardMember, nowMs: Long): String {
    val bits = mutableListOf(ProjectRules.memberWords(member))
    member.pendingSends.takeIf { it > 0 }?.let {
        bits += if (it == 1) "1 send waiting" else "$it sends waiting"
    }
    agoWords(member.lastActivityTs, nowMs).takeIf { it.isNotBlank() }?.let { bits += it }
    return bits.joinToString(" · ")
}

/**
 * What the disclosure reveals: the peer name, the model cell, the agents, the
 * work and what it cost.
 *
 * ⚠ THE AGENT COUNT, NOT THE AGENT IDS — and the daemon does not send ids at
 * all. An agent id is scoped to the session that spawned it, so a project-wide
 * list of them would be a list of handles that address nothing from here. The
 * count is a fact; a link would be a broken promise.
 *
 * ⚠ AND THE PEER NAME IS `claudeName`, NEVER `name`. The tmux name is how the
 * rest of this app addresses the session; `<slug>/<role>` is what a peer's
 * SendMessage takes, and it is the one somebody reading this line would type.
 */
fun memberDetailLines(member: ProjectDashboardMember): List<String> {
    val lines = mutableListOf<String>()
    member.claudeName.takeIf { it.isNotBlank() }?.let { lines += "peer name  $it" }
    member.name.takeIf { it.isNotBlank() && it != member.claudeName }?.let { lines += "tmux  $it" }
    member.headroom?.let { h ->
        val cell = mutableListOf<String>()
        h.family?.let { cell += it }
        h.ladder?.let { cell += "moved to $it" }
        if (h.stalled) cell += "stopped at the usage limit"
        if (!h.autoResume) cell += "auto-resume off"
        if (cell.isNotEmpty()) lines += "model  " + cell.joinToString(" · ")
    }
    if (member.agentCount > 0) {
        lines += "agents  ${member.agentCount}"
    }
    if (member.turns > 0 || member.tokens.input > 0 || member.tokens.output > 0) {
        val work = mutableListOf("${member.turns} turns")
        work += PlanFormat.compactTokens(member.tokens.input + member.tokens.output)
        member.estCostUsd?.let { work += OverviewFormat.usd(it) }
        lines += "work  " + work.joinToString(" · ")
    }
    return lines
}
