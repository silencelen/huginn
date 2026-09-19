package com.silencelen.huginn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import com.silencelen.huginn.data.ArchivedSession
import com.silencelen.huginn.data.ProjectRow
import com.silencelen.huginn.data.Session
import com.silencelen.huginn.ui.theme.verbInk

/**
 * The live tmux sessions. Each row leads with what the session is actually doing
 * (Claude Code's own generated title, plus the last couple of meaningful pane
 * lines) rather than only its tmux name, because "which of my four sessions is
 * this" is the question the list exists to answer.
 */
@Composable
fun SessionsScreen(
    sessions: List<Session>,
    selectedName: String? = null,
    /**
     * The list is one COLUMN of a two-pane layout rather than the whole screen.
     *
     * ⚠ AN EXTENDED FAB OVER A 292dp COLUMN IS NOT A CORNER, IT IS A BILLBOARD.
     * Held sideways, "＋ New session" floated 200dp wide in the middle of a
     * 2520px screen, over the list it belongs to and over a row of it. The verb
     * still belongs to this pane — it cannot move to the screen's corner, which
     * is the conversation's — so it shrinks to the plain 56dp button instead.
     */
    twoPane: Boolean = false,
    onOpen: (String) -> Unit,
    onCreate: (String) -> Unit,
    onKill: (String) -> Unit,
    onSoftEnd: (String) -> Unit = {},
    onRename: (String, String) -> Unit,
    /** Sessions ended on purpose, kept with the way back. Empty on a daemon
     *  without the feature — see [archiveAvailable]. */
    archives: List<ArchivedSession> = emptyList(),
    /**
     * Whether this daemon HAS archive. Null until the probe answers; false hides
     * the section AND the row action, because a control whose only outcome is a
     * 404 is worse than no control.
     */
    archiveAvailable: Boolean? = null,
    onArchive: (String) -> Unit = {},
    onRevive: (ArchivedSession) -> Unit = {},
    onCopyResume: (ArchivedSession) -> Unit = {},
    onDeleteArchive: (ArchivedSession) -> Unit = {},
    /**
     * READ an archived conversation, without reviving it. Null hides the verb —
     * the shape every optional row action here already takes.
     */
    onViewArchive: ((ArchivedSession) -> Unit)? = null,
    /**
     * The sessions, grouped by the project that owns them.
     *
     * EMPTY means no grouping at all — either the daemon has no projects route or
     * nothing is grouped yet — and the list then draws exactly as it always has.
     * Non-empty, it REPLACES the flat list: the groups already contain every
     * session, with the unaffiliated ones last, so rendering both would draw each
     * session twice.
     */
    groups: List<SessionGroup> = emptyList(),
    onOpenProject: (ProjectRow) -> Unit = {},
    /** The way to the whole tree. Null hides it — see projectEntries. */
    onOpenProjects: (() -> Unit)? = null,
) {
    var showNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var confirmKill by remember { mutableStateOf<String?>(null) }
    var confirmSoftEnd by remember { mutableStateOf<String?>(null) }
    var confirmArchive by remember { mutableStateOf<String?>(null) }
    var confirmDeleteArchive by remember { mutableStateOf<ArchivedSession?>(null) }
    // Collapsed by default and remembered only for as long as the screen is:
    // the archive is a footnote to this list, and a section that came back open
    // would push the live sessions off a phone screen every time.
    var archivesOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var renameTo by remember { mutableStateOf("") }
    val nowMs = remember(archives) { System.currentTimeMillis() }

    Box(Modifier.fillMaxSize()) {
        if (sessions.isEmpty()) {
            // ⚠ THE ARCHIVE STILL SHOWS HERE. A host whose sessions have all been
            // archived has an empty session list and is not an empty host, and
            // "No sessions" with no way to reach what was put away is the one
            // screen this feature could make worse.
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                EmptyState("No sessions", "Create one and it opens Claude Code on the host, same as cc.")
                if (archiveAvailable == true) {
                    ArchivedSessionsSection(
                        rows = archives,
                        nowMs = nowMs,
                        expanded = archivesOpen,
                        onToggle = { archivesOpen = !archivesOpen },
                        onRevive = onRevive,
                        onCopyResume = onCopyResume,
                        onDelete = { confirmDeleteArchive = it },
                        onOpenLive = onOpen,
                        onView = onViewArchive,
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = LIST_FAB_CLEARANCE),
            ) {
                // ONE row renderer, used flat or under a heading. Two copies of
                // it is how a session row grows an action in one arrangement and
                // not the other.
                val row: @Composable (Session) -> Unit = { s ->
                    SessionRow(
                        s,
                        selected = s.name == selectedName,
                        onOpen = { onOpen(s.name) },
                        onKill = { confirmKill = s.name },
                        onSoftEnd = { confirmSoftEnd = s.name },
                        onArchive = if (archiveAvailable == true) ({ confirmArchive = s.name }) else null,
                        onRename = { renaming = s.name; renameTo = s.name },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (groups.isEmpty()) {
                    items(sessions, key = { it.name }) { s -> row(s) }
                } else {
                    for (g in groups) {
                        val p = g.project
                        if (p != null) {
                            item(key = "project:${p.id}") {
                                ProjectHeader(p, onOpen = { onOpenProject(p) }, onSeeAll = onOpenProjects)
                            }
                        } else if (groups.size > 1) {
                            // Only when there is something above it: a lone
                            // heading over every session on the host would be
                            // naming a category nothing is outside of.
                            item(key = "ungrouped") { SectionLabel(SESSIONS_UNGROUPED) }
                        }
                        items(g.sessions, key = { it.name }) { s -> row(s) }
                    }
                }
                // At the BOTTOM of the live list, collapsed, rather than a fifth
                // bottom tab. An archive is a footnote to the sessions list —
                // somewhere you look once a week — and the tab bar already holds
                // four destinations.
                if (archiveAvailable == true) {
                    item {
                        ArchivedSessionsSection(
                            rows = archives,
                            nowMs = nowMs,
                            expanded = archivesOpen,
                            onToggle = { archivesOpen = !archivesOpen },
                            onRevive = onRevive,
                            onCopyResume = onCopyResume,
                            onDelete = { confirmDeleteArchive = it },
                            onOpenLive = onOpen,
                            onView = onViewArchive,
                        )
                    }
                }
            }
        }

        if (twoPane) {
            FloatingActionButton(
                onClick = { newName = ""; showNew = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(FAB_INSET),
            ) { Icon(Icons.Filled.Add, contentDescription = "New session") }
        } else {
            ExtendedFloatingActionButton(
                onClick = { newName = ""; showNew = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(FAB_INSET),
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New session") },
            )
        }
    }

    if (showNew) {
        AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text("New session") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        label = { Text("Name") },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Letters, digits and underscore. Opens Claude Code on the host.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showNew = false; onCreate(newName) }, enabled = newName.isNotBlank()) {
                    Text("Create")
                }
            },
            dismissButton = { TextButton(onClick = { showNew = false }) { Text("Cancel") } },
        )
    }

    renaming?.let { from ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename $from") },
            text = {
                OutlinedTextField(
                    value = renameTo,
                    onValueChange = { renameTo = it },
                    singleLine = true,
                    label = { Text("New name") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { renaming = null; onRename(from, renameTo) },
                    enabled = renameTo.isNotBlank() && renameTo != from,
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    confirmKill?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmKill = null },
            title = { Text("Kill $name?") },
            text = { Text("The session and anything running inside it are terminated. Unsaved work in that session is lost.") },
            confirmButton = {
                TextButton(onClick = { confirmKill = null; onKill(name) }) { Text(EndVerbs.HARD) }
            },
            dismissButton = { TextButton(onClick = { confirmKill = null }) { Text("Cancel") } },
        )
    }

    confirmArchive?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmArchive = null },
            title = { Text("Archive $name?") },
            text = {
                Text(
                    "Claude is asked to wrap up, and the session is ended once it settles. " +
                        "It moves to Archived with the directory it ran in, a copy of the " +
                        "conversation and the exact resume command, so you can bring it back.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmArchive = null; onArchive(name) }) { Text("Archive") }
            },
            dismissButton = { TextButton(onClick = { confirmArchive = null }) { Text("Cancel") } },
        )
    }

    confirmDeleteArchive?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmDeleteArchive = null },
            title = { Text("Forget ${ArchiveRules.label(row)}?") },
            // Named for what is actually lost. "Delete" against a row that looks
            // like a list entry reads as tidying; the copy of the conversation
            // going with it is the part worth a sentence.
            text = { Text("The row and the kept copy of its conversation are removed. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { val r = row; confirmDeleteArchive = null; onDeleteArchive(r) }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteArchive = null }) { Text("Cancel") } },
        )
    }

    confirmSoftEnd?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmSoftEnd = null },
            title = { Text("${EndVerbs.SOFT} $name?") },
            text = {
                Text(
                    "Sends Claude the wrap-up instruction (finish, commit, prepare to end). " +
                        "If auto-end is on for the host, the session ends on its own once it settles.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmSoftEnd = null; onSoftEnd(name) }) { Text("Send wrap-up") }
            },
            dismissButton = { TextButton(onClick = { confirmSoftEnd = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SessionRow(
    s: Session,
    selected: Boolean,
    onOpen: () -> Unit,
    onKill: () -> Unit,
    onSoftEnd: () -> Unit = {},
    /** Null on a daemon without the archive feature, which is what hides the item. */
    onArchive: (() -> Unit)? = null,
    onRename: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                else Modifier
            )
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.state == "running" || s.bgShells > 0 || s.bgAgents > 0) {
                    PulsingDot(
                        if (s.state == "attention") MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                } else {
                    StateDot(s.state)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    s.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    // "waiting" over a session running four background shells was
                    // a lie of omission — the list is where "looks stalled" started.
                    if (s.state != "running" && (s.bgShells > 0 || s.bgAgents > 0)) "background work"
                    else stateLabel(s.state),
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        s.state == "attention" -> MaterialTheme.colorScheme.error
                        s.state == "running" || s.bgShells > 0 || s.bgAgents > 0 ->
                            MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (s.compacting) {
                    Spacer(Modifier.width(8.dp))
                    CompactingChip()
                }
                Spacer(Modifier.weight(1f))
                // "ctx N%" next to the time; renders nothing when the host didn't report.
                ContextBadge(s.contextPercent, Modifier.padding(end = 8.dp))
                Text(
                    relTime(s.activityAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Bound to a local first: `s.title` is a public property of another
            // module (:core) now, so the compiler will not smart-cast it inside
            // the null check — the value could in principle change between the
            // test and the read. A local is a snapshot and needs no assertion.
            val title = s.title
            if (!title.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (s.bgShells > 0 || s.bgAgents > 0) {
                Spacer(Modifier.height(3.dp))
                Text(
                    buildList {
                        s.bgTask?.let { add("⚙ $it") }
                        if (s.bgShells > 1) add("+${s.bgShells - 1} more")
                        if (s.bgAgents > 0) add("${s.bgAgents} agent${if (s.bgAgents == 1) "" else "s"}")
                    }.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (s.preview.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                s.preview.takeLast(2).forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            val meta = buildList {
                // FIRST: a message this person typed is still waiting to reach the
                // pane, and every other fact on this line is about the terminal.
                SendQueue.rowMark(s.pendingSends)?.let { add(it) }
                if (s.cols > 0) add("${s.cols}x${s.rows}")
                if (s.attachedClients > 0) add("${s.attachedClients} attached")
                if (s.sizeLeased) add("fitted to phone")
            }
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    meta.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Session actions")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null) },
                    onClick = { menu = false; onRename() },
                )
                // THE TWO ENDING VERBS, AND THEY READ AS A PAIR. Both are drawn
                // in the destructive palette — the kill in the full `error` red,
                // the wrap-up in the lighter one — from `verbInk`, the single
                // token in `:ui`'s theme that the desktop's right-click menu also
                // draws from. Two shells picking their own lighter red is the
                // drift that makes a phone held up beside the laptop look like a
                // different product.
                DropdownMenuItem(
                    text = { Text(EndVerbs.soft(1), color = verbInk(VerbTone.SOFT, MaterialTheme.colorScheme)) },
                    // ⚠ AN ICON, BECAUSE THE OTHER THREE HAVE ONE. Without it the
                    // label started in the icon COLUMN (x=754) while Rename,
                    // Archive and Kill started at x=849, and a menu item that
                    // hangs off the left of the others reads as a different class
                    // of thing than it is. Tinted like its label, the way the kill
                    // below is.
                    leadingIcon = {
                        Icon(
                            Icons.Filled.TaskAlt,
                            contentDescription = null,
                            tint = verbInk(VerbTone.SOFT, MaterialTheme.colorScheme),
                        )
                    },
                    onClick = { menu = false; onSoftEnd() },
                )
                // Between the wrap-up and the kill, where it belongs: it is a
                // wrap-up that leaves something behind. Neither red — ending a
                // session you can bring back is the least destructive of the
                // three.
                if (onArchive != null) {
                    DropdownMenuItem(
                        text = { Text("Archive…") },
                        leadingIcon = { Icon(Icons.Filled.Inventory2, contentDescription = null) },
                        onClick = { menu = false; onArchive() },
                    )
                }
                DropdownMenuItem(
                    text = { Text(EndVerbs.hard(1), color = verbInk(VerbTone.DESTRUCTIVE, MaterialTheme.colorScheme)) },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = verbInk(VerbTone.DESTRUCTIVE, MaterialTheme.colorScheme),
                        )
                    },
                    onClick = { menu = false; onKill() },
                )
            }
        }
    }
}


/**
 * The heading over one project's sessions.
 *
 * ⚠ THE COUNTS ARE THE DAEMON'S, not a tally of the rows underneath. The rollup
 * was summed across three registries this client cannot read, and a heading that
 * counted the sessions it happened to be drawing would disagree with the Projects
 * tree about the same cluster — which is the one thing a heading must never do.
 * A member whose tmux session is gone is in the count and not in the list, and
 * that difference is exactly what the reader needs to see.
 */
@Composable
private fun ProjectHeader(project: ProjectRow, onOpen: () -> Unit, onSeeAll: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                ProjectRules.label(project).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                groupWords(project),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The whole tree, when the shell has somewhere to put it. On the heading
        // rather than beside every project, because "all of them" is one place.
        onSeeAll?.let {
            TextButton(onClick = it, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                Text("All", style = MaterialTheme.typography.labelMedium)
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
