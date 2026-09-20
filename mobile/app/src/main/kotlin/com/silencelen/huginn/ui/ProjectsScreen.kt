package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.silencelen.huginn.data.ProjectLive
import com.silencelen.huginn.data.ProjectRow
import com.silencelen.huginn.ui.theme.verbInk

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
    projectsDir: String? = null,
    /**
     * ⚠ D-8, THE PHONE'S HALF. Winding a cluster down lived ONLY behind the
     * dashboard's top-bar menu, which is two taps away and inside the project —
     * and the desktop's equivalent was a right-click on a title with no
     * affordance at all. The row gets the same door both lists now have. Null
     * hides the control, which is what a shell with nowhere to put the dialog
     * gets.
     */
    onEndProject: ((ProjectRow) -> Unit)? = null,
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
            // The additive slot `ProjectsListView` describes: the ITEMS are this
            // shell's, because a phone menu is a `DropdownMenu` and the desktop's
            // is a `ContextMenuItem` list.
            rowTrailing = onEndProject?.let { end ->
                { project -> ProjectRowMenu(onOpen = { onOpenProject(project) }, onEnd = { end(project) }) }
            },
        )

        ExtendedFloatingActionButton(
            onClick = { sheet = true },
            // The token, not a copy of the number the clearance is derived
            // from — see [LIST_FAB_CLEARANCE].
            modifier = Modifier.align(Alignment.BottomEnd).padding(FAB_INSET),
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("New project") },
        )
    }

    if (sheet) {
        CreateProjectSheet(
            taken = projects.map { it.name },
            takenSlugs = projects.map { it.slug },
            defaultCwd = defaultCwd,
            projectsDir = projectsDir,
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
    /**
     * Project sessions kept OFF the sessions list — the tree poll that decides
     * which, and the one line under the list that says how many. A project
     * session lives in Projects and nowhere else (owner rule, 2026-09-19).
     */
    val hiding: Boolean,
) {
    val any: Boolean get() = sessionsIcon || settingsRow || hiding
}

fun projectEntries(available: Boolean?): ProjectEntries {
    val on = available == true
    return ProjectEntries(sessionsIcon = on, settingsRow = on, hiding = on)
}

/**
 * The project row's own menu (D-8).
 *
 * Two items, and they are the two verbs this client actually has: opening the
 * cluster, and winding it down. Rename / Pause / Archive are the desktop's,
 * because the phone has no view-model path to `PATCH /v1/projects/:id` yet —
 * an item that can only fail teaches people the whole menu is decoration.
 */
@Composable
private fun ProjectRowMenu(onOpen: () -> Unit, onEnd: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Project actions")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Open") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                onClick = { open = false; onOpen() },
            )
            DropdownMenuItem(
                text = { Text("End\u2026", color = verbInk(VerbTone.DESTRUCTIVE, MaterialTheme.colorScheme)) },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = verbInk(VerbTone.DESTRUCTIVE, MaterialTheme.colorScheme),
                    )
                },
                onClick = { open = false; onEnd() },
            )
        }
    }
}
