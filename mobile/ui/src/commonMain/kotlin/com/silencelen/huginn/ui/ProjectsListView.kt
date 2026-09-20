package com.silencelen.huginn.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silencelen.huginn.data.ProjectLive
import com.silencelen.huginn.data.ProjectMemberState
import com.silencelen.huginn.data.ProjectRow

/**
 * The projects, each one folding open to the sessions it is made of.
 *
 * ONE composable for both shells: the desktop puts it in the list pane above the
 * loose sessions, the phone puts it behind the Sessions tab's Projects icon, and
 * a member row opens the ordinary session detail on either. Nothing about a
 * cluster reads differently under a thumb than under a mouse.
 *
 * ⚠ THE LIST ROUTE CARRIES ROWS, NOT MEMBERS. `GET /v1/projects` answers with
 * summed counts and no membership — the members arrive from `GET
 * /v1/projects/:id`, which is a per-project call the shell makes when a row is
 * folded open. So [members] is supplied rather than read off the row: a project
 * nobody has opened has no entry, and its disclosure says so instead of drawing
 * an empty cluster.
 *
 * ⚠⚠ THE DISCLOSURE ANIMATES HEIGHT ONLY, AND THIS IS THE ONE THING THIS FILE
 * MUST GET RIGHT. Inside the desktop's list pane the rows live in an inner
 * `Box(wrapContentWidth(unbounded = true).requiredWidth(listWidth))`, which means
 * any child that animates its own WIDTH re-measures the pane under the splitter
 * — the pane visibly breathes in and out every time somebody opens a project.
 * `animateContentSize` on a `fillMaxWidth` column can only move the height,
 * because the width arrived fixed. There is a source gate — see
 * `DisclosureHeightOnlyTest` — and it exists because a comment is not a gate.
 */
@Composable
fun ProjectsListView(
    projects: List<ProjectRow>,
    nowMs: Long,
    /** Ids of the projects currently folded open. Held by the shell, so it survives navigation. */
    expanded: Set<String>,
    /**
     * The live members of the projects the shell has fetched, by project id. A
     * missing entry is "not loaded yet", which is not the same as "no members".
     */
    members: Map<String, List<ProjectLive>>,
    onToggle: (String) -> Unit,
    onOpenProject: (ProjectRow) -> Unit,
    onOpenMember: (ProjectRow, ProjectLive) -> Unit,
    modifier: Modifier = Modifier,
    header: String? = "Projects",
    /** Null hides the control: a shell with nowhere to put a create sheet offers none. */
    onCreate: (() -> Unit)? = null,
    /**
     * Whether THIS view owns the scroll. Same rule and same reason as
     * [AppsView]: the phone hosts this column in a `Box(fillMaxSize())` and
     * nothing else there scrolls, so every project past the fold is unreachable;
     * the desktop's pane already scrolls around it and a second one nested inside
     * swallows the gesture instead of throwing.
     */
    scroll: Boolean = false,
    /**
     * ⚠⚠ D-8. THE VERBS WERE UNREACHABLE WITHOUT GUESSING. Open / Rename / Pause
     * / Archive / Delete existed ONLY behind a secondary click on the project
     * TITLE in the detail header: no chevron, no ⋮, no hover mark, and a
     * right-click on the ROW in this list offered nothing at all. The verbs
     * themselves are good — the three-way delete dialog is the best destructive
     * dialog in the product — and they were simply undiscoverable.
     *
     * An ADDITIVE slot rather than a menu built in here: the items are the
     * shell's (the desktop has `ContextMenuItem`s and a `MenuButton`, the phone a
     * `DropdownMenu`), and a shared list has no business knowing either. Null
     * draws nothing, which is exactly what every existing caller gets.
     */
    rowTrailing: (@Composable (ProjectRow) -> Unit)? = null,
) {
    val ordered = remember(projects) { ProjectRules.orderedProjects(projects) }
    val scrollState = rememberScrollState()
    Column(modifier.fillMaxWidth().let { if (scroll) it.verticalScroll(scrollState) else it }) {
        if (header != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // ⚠ THE HOUSE HEADER: Title Case, then the count, then "+ New".
                // Every other list in both clients is drawn that way and this one
                // was uppercase micro-type with a bare "New" — the one list header
                // that looked like a different product.
                Text(
                    header,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (ordered.isNotEmpty()) {
                    Text(
                        "${ordered.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (onCreate != null) {
                    TextButton(onClick = onCreate) {
                        Text("+ New", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
        }
        if (ordered.isEmpty()) {
            // Said, not left blank — the ArchivedSessions rule. An empty list that
            // explains nothing reads as a broken feature rather than an unused one.
            Text(
                PROJECTS_EMPTY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 12.dp),
            )
            return@Column
        }
        ordered.forEach { project ->
            ProjectRowItem(
                project = project,
                nowMs = nowMs,
                expanded = project.id in expanded,
                members = members[project.id],
                onToggle = { onToggle(project.id) },
                onOpen = { onOpenProject(project) },
                onOpenMember = { onOpenMember(project, it) },
                trailing = rowTrailing?.let { slot -> { slot(project) } },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun ProjectRowItem(
    project: ProjectRow,
    nowMs: Long,
    expanded: Boolean,
    members: List<ProjectLive>?,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onOpenMember: (ProjectLive) -> Unit,
    /** The shell's own control at the end of the row — see [ProjectsListView]. */
    trailing: (@Composable () -> Unit)? = null,
) {
    val ordered = remember(members) { ProjectRules.ordered(members.orEmpty()) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen)
                .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The triangle is the disclosure and has its own hit target: tapping
            // the NAME opens the project, which is what a name is for.
            Text(
                if (expanded) "▾" else "▸",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onToggle).padding(horizontal = 4.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    ProjectRules.label(project),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    projectSubtitle(project, nowMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (project.waiting > 0) {
                // The one mark on the row, and the house's own vernacular for it:
                // a small dot, no accent rail, no badge count.
                MemberDot("attention")
            }
            // The way into the verbs, at the end of the row where every other
            // list in this product puts one (D-8).
            trailing?.invoke()
        }
        // ⚠⚠ HEIGHT ONLY. The column is fillMaxWidth, so the width it is measured
        // at arrived fixed from the pane and animateContentSize can only move the
        // other axis. Never put a width animation on this chain — see the file's
        // KDoc and DisclosureHeightOnlyTest.
        Column(Modifier.fillMaxWidth().animateContentSize()) {
            if (!expanded) return@Column
            if (ordered.isEmpty()) {
                Text(
                    if (members == null) PROJECT_MEMBERS_LOADING else PROJECT_NO_MEMBERS,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 34.dp, end = 14.dp, bottom = 10.dp),
                )
                return@Column
            }
            ordered.forEach { m -> MemberRow(m, onClick = { onOpenMember(m) }) }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun MemberRow(member: ProjectLive, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = 34.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemberDot(ProjectRules.stateWord(member))
        Spacer(Modifier.width(8.dp))
        Text(
            memberLabel(member),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (member.lead) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            ProjectRules.memberWords(member),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * The member mark, in the SESSION vocabulary.
 *
 * Takes the word rather than a colour so `:core` never has to name a colour and
 * both shells get the same mapping. A null word draws a hole of the same size —
 * not a grey dot, which would read as "idle" rather than as "nothing known".
 */
@Composable
internal fun MemberDot(word: String?) {
    val colour: Color? = when (word) {
        "attention" -> MaterialTheme.colorScheme.error
        "running" -> MaterialTheme.colorScheme.primary
        "idle" -> MaterialTheme.colorScheme.outline
        else -> null
    }
    if (colour == null) {
        Spacer(Modifier.size(8.dp))
        return
    }
    Surface(color = colour, shape = CircleShape, modifier = Modifier.size(8.dp)) {}
}

/** What a section with nothing in it says, so an empty list is not a broken one. */
/**
 * The LIST pane's empty state. One short sentence.
 *
 * ⚠ THE EXPLANATION LIVES IN THE OTHER PANE. With no projects both panes are on
 * screen at once, and each used to carry its own paragraph about what a project
 * is — two different wordings of the same three facts, side by side. The pane
 * with the room says it (see [PROJECTS_BLURB]); this one is 280dp wide and says
 * the fact.
 */
const val PROJECTS_EMPTY: String = "No projects yet."

/** What a project IS, said once, in the pane wide enough for it. */
const val PROJECTS_BLURB: String =
    "A project is a cluster of sessions with roles: a lead sizes the work, proposes " +
        "the members, and you approve them before anything is started."

/** The disclosure of a project whose membership the shell has not fetched yet. */
const val PROJECT_MEMBERS_LOADING: String = "Reading the cluster…"

/** The disclosure of a project that genuinely has nobody in it yet. */
const val PROJECT_NO_MEMBERS: String = "No members yet — the lead is still sizing this one."

/**
 * What a member row leads with: its role, and the lead said out loud.
 *
 * The role rather than either name, because within one cluster the role IS the
 * identity — `stick-docs` and `stick/docs` are both just "docs" with the
 * project's own name stuck on the front of it.
 */
fun memberLabel(member: ProjectMemberState): String {
    val role = member.role.trim().takeIf { it.isNotEmpty() }
        ?: member.claudeName.substringAfterLast('/').takeIf { it.isNotEmpty() }
        ?: member.name
    return if (member.lead) "$role (lead)" else role
}

/**
 * The one line under a project's name: who is working, where, and what state the
 * cluster is in.
 *
 * The rollup leads because it is the answer to the only question this row is
 * asked. The directory comes second and only when there is one — an absent fact
 * is absent, not an empty slot between two dots (the device line's rule).
 *
 * ⚠ THE COUNTS COME OFF THE ROW, NOT OUT OF A MEMBER LIST. The daemon summed
 * them across three registries this client cannot read, and a client that
 * recomputed them from whatever membership it happened to be holding would
 * disagree with its own tree.
 */
fun projectSubtitle(project: ProjectRow, nowMs: Long): String {
    val bits = mutableListOf<String>()
    bits += ProjectRules.rollupWords(project)
    if (ProjectRules.hasProposal(project)) {
        ProjectRules.manifestSummary(project)?.let { bits += it }
    }
    project.cwd.trim().takeIf { it.isNotEmpty() }?.let { bits += it }
    if (!ProjectRules.live(project)) {
        // The daemon's own sentence about why, when it archived this itself —
        // "the lead session is gone" is the whole story and a client summary of
        // it is not.
        bits += project.endedReason?.trim()?.takeIf { it.isNotEmpty() } ?: "archived"
        agoWords(project.updatedAt, nowMs).takeIf { it.isNotBlank() }?.let { bits += it }
    }
    return bits.joinToString(" · ")
}
