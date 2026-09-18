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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silencelen.huginn.data.Project
import com.silencelen.huginn.data.ProjectMember

/**
 * The projects, each one folding open to the sessions it is made of.
 *
 * ONE composable for both shells: the desktop puts it in the list pane above the
 * loose sessions, the phone puts it behind the Sessions tab's Projects icon, and
 * a member row opens the ordinary session detail on either. Nothing about a
 * cluster reads differently under a thumb than under a mouse.
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
    projects: List<Project>,
    nowMs: Long,
    /** Ids of the projects currently folded open. Held by the shell, so it survives navigation. */
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    onOpenProject: (Project) -> Unit,
    onOpenMember: (Project, ProjectMember) -> Unit,
    modifier: Modifier = Modifier,
    header: String? = "PROJECTS",
    /** Null hides the control: a shell with nowhere to put a create sheet offers none. */
    onCreate: (() -> Unit)? = null,
) {
    val ordered = remember(projects) { ProjectRules.orderedProjects(projects) }
    Column(modifier.fillMaxWidth()) {
        if (header != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    header,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.2.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (onCreate != null) {
                    TextButton(onClick = onCreate) {
                        Text("New", style = MaterialTheme.typography.labelMedium, maxLines = 1)
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
            ProjectRow(
                project = project,
                nowMs = nowMs,
                expanded = project.id in expanded,
                onToggle = { onToggle(project.id) },
                onOpen = { onOpenProject(project) },
                onOpenMember = { onOpenMember(project, it) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun ProjectRow(
    project: Project,
    nowMs: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onOpenMember: (ProjectMember) -> Unit,
) {
    val members = remember(project.members) { ProjectRules.ordered(project.members) }
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
            if (ProjectRules.rollup(project.members).needsYou > 0) {
                // The one mark on the row, and the house's own vernacular for it:
                // a small dot, no accent rail, no badge count.
                MemberDot("attention")
            }
        }
        // ⚠⚠ HEIGHT ONLY. The column is fillMaxWidth, so the width it is measured
        // at arrived fixed from the pane and animateContentSize can only move the
        // other axis. Never put a width animation on this chain — see the file's
        // KDoc and DisclosureHeightOnlyTest.
        Column(Modifier.fillMaxWidth().animateContentSize()) {
            if (!expanded) return@Column
            if (members.isEmpty()) {
                Text(
                    "No members yet — the lead is still sizing this one.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 34.dp, end = 14.dp, bottom = 10.dp),
                )
                return@Column
            }
            members.forEach { m -> MemberRow(m, onClick = { onOpenMember(m) }) }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun MemberRow(member: ProjectMember, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = 34.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemberDot(ProjectRules.stateWord(member))
        Spacer(Modifier.width(8.dp))
        Text(
            member.role.ifBlank { member.name },
            style = MaterialTheme.typography.bodySmall,
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
const val PROJECTS_EMPTY: String =
    "No projects yet. A project is a cluster of sessions with roles: a lead sizes " +
        "the work, proposes the members, and you approve them before anything is started."

/**
 * The one line under a project's name: who is working, where, and since when.
 *
 * The rollup leads because it is the answer to the only question this row is
 * asked. The directory comes second and only when there is one — an absent fact
 * is absent, not an empty slot between two dots (the device line's rule).
 */
fun projectSubtitle(project: Project, nowMs: Long): String {
    val bits = mutableListOf<String>()
    bits += ProjectRules.rollupWords(project.members)
    project.cwd?.trim()?.takeIf { it.isNotEmpty() }?.let { bits += it }
    if (!ProjectRules.live(project)) {
        agoWords(project.endedAt, nowMs).takeIf { it.isNotBlank() }?.let { bits += "ended $it" }
    }
    return bits.joinToString(" · ")
}
