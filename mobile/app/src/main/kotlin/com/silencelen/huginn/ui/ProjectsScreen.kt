package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.silencelen.huginn.data.ProjectLive
import com.silencelen.huginn.data.ProjectRow
import com.silencelen.huginn.data.Session

/**
 * The phone's Projects list: the shared tree, a create sheet, and the fetches the
 * tree cannot make for itself.
 *
 * ⚠ THE TREE DOES NOT FETCH. `ProjectsListView` takes `members` as a map the
 * SHELL fills, because membership comes from a per-project GET and a composable
 * that issued one per row would put twelve requests on screen at a scroll. So
 * opening a disclosure is a request here — [onExpand] — and a project whose
 * members have not arrived draws "Reading the cluster…" rather than "no members".
 */
@Composable
fun ProjectsScreen(
    projects: List<ProjectRow>,
    members: Map<String, List<ProjectLive>>,
    nowMs: Long,
    onOpenProject: (ProjectRow) -> Unit,
    onOpenMember: (ProjectLive) -> Unit,
    /** Asked to fetch a project's members when its disclosure opens. */
    onExpand: (String) -> Unit,
    onCreate: (name: String, kind: String, brief: String, cwd: String?) -> Unit,
    creating: Boolean = false,
    /** The daemon's last refusal, shown under the sheet's fields, verbatim. */
    refusal: String? = null,
    onDismissSheet: () -> Unit = {},
    defaultCwd: String? = null,
) {
    // Survives a fold, like every other screen state here: unfolding the phone
    // must not collapse a cluster somebody had just opened.
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var sheet by rememberSaveable { mutableStateOf(false) }

    // Pushed destination, no bar beneath it: the system navigation inset is this
    // screen's to pay or "New project" sits on the gesture bar. Same rule as
    // Apps, one spelling in Insets.kt, held by `ListFabClearanceTest`.
    Box(Modifier.fillMaxSize().systemNavPadding()) {
        ProjectsListView(
            projects = projects,
            nowMs = nowMs,
            expanded = expanded,
            members = members,
            onToggle = { id ->
                val open = id in expanded
                expanded = if (open) expanded - id else expanded + id
                // Fetched on OPEN only, and every time: a cluster's membership is
                // the thing that moves, and a map filled once would show a member
                // that ended an hour ago as busy.
                if (!open) onExpand(id)
            },
            onOpenProject = onOpenProject,
            onOpenMember = { _, live -> onOpenMember(live) },
            onCreate = null,
            // The same rule as Apps: this destination owns the scroll, because
            // nothing around the tree provides one.
            scroll = true,
            // …and the same clearance, so the last project is not parked behind
            // "New project" with no scroll position that would move it.
            modifier = Modifier.fillMaxSize().padding(bottom = LIST_FAB_CLEARANCE),
        )

        ExtendedFloatingActionButton(
            onClick = { sheet = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("New project") },
        )
    }

    if (sheet) {
        CreateProjectSheet(
            taken = projects.map { it.name },
            takenSlugs = projects.map { it.slug },
            defaultCwd = defaultCwd,
            busy = creating,
            refusal = refusal,
            onDismiss = { sheet = false; onDismissSheet() },
            onCreate = onCreate,
        )
    }
}

// ------------------------------------------------------- what the shell offers

/**
 * Whether the three ways into Projects are drawn, decided ONCE.
 *
 * ⚠ THREE PLACES, ONE ANSWER. A probe that hid the list but left the Sessions
 * grouping, or left a Settings row whose only outcome is a 404, is worse than no
 * feature at all — and three separate `== true` checks written beside three
 * call sites is exactly how one of them gets left behind. `null` is "the probe
 * has not answered", which shows nothing: a door that may not exist is not a
 * door to offer.
 */
data class ProjectEntries(
    /** The Projects icon in the Sessions top bar. */
    val sessionsIcon: Boolean,
    /** The nav row in Settings → Chats & sessions. */
    val settingsRow: Boolean,
    /** Project headers over the sessions list. */
    val grouping: Boolean,
) {
    val any: Boolean get() = sessionsIcon || settingsRow || grouping
}

fun projectEntries(available: Boolean?): ProjectEntries {
    val on = available == true
    return ProjectEntries(sessionsIcon = on, settingsRow = on, grouping = on)
}

// ----------------------------------------------------------------- grouping

/**
 * One block of the sessions list: a project and the sessions that belong to it,
 * or the ones that belong to nothing.
 *
 * [project] null is the unaffiliated block, and it is always LAST — a session
 * that is nobody's is still a session somebody runs, so it keeps the list it has
 * always had rather than being filed under a heading it does not have.
 */
data class SessionGroup(
    val project: ProjectRow?,
    val sessions: List<Session>,
)

/**
 * The sessions list, grouped by project.
 *
 * ⚠⚠ THE JOIN KEY IS THE TMUX NAME, AND IT COMES OFF `live[]`. A project's
 * membership is `<slug>-<role>`; its PEER name is `<slug>/<role>` and a slash is
 * not a tmux name character, so matching on the peer name matches nothing.
 * Matching on the slug prefix would be worse — a session somebody called
 * `statusflap-notes` by hand would silently join a cluster it is not in.
 *
 * ⚠ AND `live[]` IS PER-PROJECT AND FETCHED LAZILY. A project whose detail has
 * not been read contributes only its LEAD, which the list row already carries —
 * so the tree fills in as the shell fetches rather than flickering between two
 * groupings. A project that claims no session on this host draws no header:
 * a heading over nothing is a heading that reads as a bug.
 */
fun groupSessions(
    projects: List<ProjectRow>,
    members: Map<String, List<ProjectLive>>,
    sessions: List<Session>,
): List<SessionGroup> {
    if (projects.isEmpty()) return listOf(SessionGroup(null, sessions))
    val claimed = HashSet<String>()
    val groups = ArrayList<SessionGroup>()
    for (p in ProjectRules.orderedProjects(projects)) {
        val names = LinkedHashSet<String>()
        // The lead off the row first, so a project the shell has never opened
        // still gathers the one session it is certain to own.
        p.lead?.name?.takeIf { it.isNotBlank() }?.let { names += it }
        members[p.id].orEmpty().forEach { m -> m.name.takeIf { it.isNotBlank() }?.let { names += it } }
        // FIRST CLAIM WINS, in tree order. Two projects cannot legally own one
        // tmux name, but a stale `live[]` held from before a rename can say they
        // do, and a session drawn under two headings is a list that lies twice.
        val mine = sessions.filter { it.name in names && claimed.add(it.name) }
        if (mine.isNotEmpty()) groups += SessionGroup(p, mine)
    }
    val rest = sessions.filterNot { it.name in claimed }
    if (rest.isNotEmpty() || groups.isEmpty()) groups += SessionGroup(null, rest)
    return groups
}

/** The heading over a project's block: what it is, and who is working. */
fun groupWords(project: ProjectRow): String = ProjectRules.rollupWords(project)

/** What the unaffiliated block is called, when there is a project above it. */
const val SESSIONS_UNGROUPED: String = "NOT IN A PROJECT"
