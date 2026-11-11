package com.silencelen.huginn.ui

import com.silencelen.huginn.ui.system.HouseMenuItem
import com.silencelen.huginn.ui.system.HouseMenu
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
import com.silencelen.huginn.ui.theme.LocalHuginnColors
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.ArchivedSession
import com.silencelen.huginn.data.Session
import com.silencelen.huginn.ui.theme.StateKind
import com.silencelen.huginn.ui.theme.stateColor
import com.silencelen.huginn.ui.theme.verbInk
import com.silencelen.huginn.ui.system.HouseDialog
import com.silencelen.huginn.ui.system.ListRow
import com.silencelen.huginn.ui.system.Scale

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
    // How many project sessions this list does NOT draw — see
    // ProjectRules.splitByProject; they are on the Projects page and nowhere else.
    inProjects: Int = 0,
    // How many projects they are in: the number the line says (owner, 2026-10-17).
    projectCount: Int = 0,
    /** The way to the whole tree. Null hides it — see projectEntries. */
    onOpenProjects: (() -> Unit)? = null,
    /** The recents for the "Starting directory" dropdown (appd 3.17); null draws the field alone. */
    recentDirs: com.silencelen.huginn.data.RecentDirs? = null,
    /** Asked when the New session dialog opens, so the dropdown is fresh. */
    onLoadRecentDirs: () -> Unit = {},
    /**
     * Create with a directory. [onCreate] is the older two-argument shape and is
     * kept for callers that have no directory to pass; when this is set it wins.
     * The dialog stays up until `onDone(null)`; a daemon refusal (the directory
     * is not on the server) arrives as `onDone(reason)` and is shown in place.
     */
    onCreateIn: ((name: String, cwd: String?, onDone: (String?) -> Unit) -> Unit)? = null,
) {
    var showNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    // A TextFieldValue, not a String: a path picked from Recent has to land with
    // the cursor at its END, so the field shows the tail (StartDirField).
    var newDirField by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    val newDir = newDirField.text
    var newDirPicking by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
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

    // ⚠⚠ P-02. WHICH ROW'S ⋮ IS OPEN, HOISTED. It used to be `var menu by
    // remember` inside SessionRow, which meant nothing outside the row could
    // know a gesture was in progress — and the list re-sorts by `activityAt`
    // every poll, so the walker's tap on row 2's "Kill session" raised
    // "Wrap up rv-desktop-1?". Now the open row is state up here, and it is one
    // of the things that freezes the order.
    var openMenu by remember { mutableStateOf<String?>(null) }
    val frozen = openMenu != null || confirmKill != null || confirmSoftEnd != null ||
        confirmArchive != null || confirmDeleteArchive != null || renaming != null
    // What the reader is currently looking at. Recorded on every unfrozen render
    // and held to while anything is open — see `OrderLock`, which is where the
    // rule lives and where it is asserted.
    var shownOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    val ordered = OrderLock.order(shownOrder, sessions, frozen) { it.name }
    if (!frozen) {
        val drawn = OrderLock.keysOf(ordered) { it.name }
        if (drawn != shownOrder) shownOrder = drawn
    }

    Box(Modifier.fillMaxSize()) {
        if (sessions.isEmpty()) {
            // ⚠ THE ARCHIVE STILL SHOWS HERE. A host whose sessions have all been
            // archived has an empty session list and is not an empty host, and
            // "No sessions" with no way to reach what was put away is the one
            // screen this feature could make worse.
            // ⚠ THE EMPTY BRANCH CLEARS THE FAB TOO (P-37). The live list has
            // carried [LIST_FAB_CLEARANCE] since the FAB was written down; this
            // column did not, so on a host whose sessions have ALL been archived
            // the archive rows — the only thing on the screen — sat under "New
            // session".
            Column(
                Modifier.fillMaxSize().padding(bottom = LIST_FAB_CLEARANCE),
                verticalArrangement = Arrangement.Center,
            ) {
                EmptyState("No sessions", "Create one and it opens Claude Code on the host, same as cc.")
                if (inProjects > 0) InProjectsLine(inProjects, projectCount, onOpenProjects)
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
                val row: @Composable (Session, Modifier) -> Unit = { s, mod ->
                    SessionRow(
                        s,
                        selected = s.name == selectedName,
                        menuOpen = openMenu == s.name,
                        onMenu = { open -> openMenu = if (open) s.name else null },
                        onOpen = { onOpen(s.name) },
                        onKill = { confirmKill = s.name },
                        onSoftEnd = { confirmSoftEnd = s.name },
                        onArchive = if (archiveAvailable == true) ({ confirmArchive = s.name }) else null,
                        onRename = { renaming = s.name; renameTo = s.name },
                        // The 292dp column has no room for a sentence on the meta line.
                        compact = twoPane,
                        modifier = mod,
                    )
                    HorizontalDivider(color = LocalHuginnColors.current.outlineHairline)
                }
                // ⚠ `animateItem` IS THE OTHER HALF OF P-02. When the list
                // DOES re-sort — which it must, the moment nothing is open —
                // a row that teleports is a row nobody can follow. Moving it
                // is what lets a reader see that the thing they were aiming
                // at went somewhere, instead of discovering it by pressing.
                items(ordered, key = { it.name }) { s -> row(s, Modifier.animateItem()) }
                // The project sessions are not in this list, and this is the one
                // line that says so — see InProjectsLine.
                if (inProjects > 0) item(key = "in-projects") { InProjectsLine(inProjects, projectCount, onOpenProjects) }
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
                onClick = { newName = ""; newDirField = StartDirField.picked(""); createError = null; onLoadRecentDirs(); showNew = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(FAB_INSET),
            ) { Icon(Icons.Filled.Add, contentDescription = "New session") }
        } else {
            ExtendedFloatingActionButton(
                onClick = { newName = ""; newDirField = StartDirField.picked(""); createError = null; onLoadRecentDirs(); showNew = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(FAB_INSET),
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New session") },
            )
        }
    }

    if (showNew) {
        // ⚠ THE NAME FIELD TAKES FOCUS (P-25). The dialog opened with no
        // keyboard and nothing selected: one tap, every time, on a dialog whose
        // only content is one field. A `FocusRequester` inside a dialog has to
        // wait for the window to exist, which is what the LaunchedEffect is for.
        val nameFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { nameFocus.requestFocus() } }
        // ⚠ THE RULE IS THE DAEMON'S, NOT A GUESS (P-24). "Letters, digits and
        // underscore" forbade the DASH the host has always allowed — which is
        // what every session in this fleet is named with. Asked of the field as
        // it is typed, so a refusal arrives here rather than as a 400 after the
        // dialog has closed.
        val problem = SessionNameRules.nameProblem(newName).takeIf { newName.isNotBlank() }
        // The directory rule is `:core`'s too (StartDirRules): blank is the
        // server default, anything else must be absolute; whether it EXISTS is
        // the daemon's 400, shown here while the dialog stays up.
        val dirProblem = StartDirRules.cwdProblem(newDir)
        val dirChoices = StartDirRules.choices(recentDirs, newDir)
        // Each Recent row cut in the MIDDLE to what the menu row holds (the
        // desktop's DirLabel, measured, not counted), so two long paths that
        // differ at the end still read differently.
        val dirMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
        val dirStyle = MaterialTheme.typography.bodyMedium
        val dirLabelPx = with(androidx.compose.ui.platform.LocalDensity.current) { StartDirField.RECENT_LABEL_WIDTH.roundToPx() }
        val dirLabels = remember(dirChoices, dirStyle, dirLabelPx, dirMeasurer) {
            dirChoices.map { d ->
                StartDirRules.DirLabel.fit(d) { s ->
                    dirMeasurer.measure(s, dirStyle, softWrap = false, maxLines = 1).size.width <= dirLabelPx
                }
            }
        }
        HouseDialog(
            title = "New session",
            onDismiss = { showNew = false },
            confirmLabel = "Create",
            onConfirm = {
                val createIn = onCreateIn
                if (createIn == null) {
                    showNew = false; onCreate(newName)
                } else {
                    creating = true; createError = null
                    createIn(newName, StartDirRules.cwdOrNull(newDir)) { err ->
                        creating = false
                        if (err == null) showNew = false else createError = err
                    }
                }
            },
            confirmEnabled = newName.isNotBlank() && problem == null && dirProblem == null && !creating,
            text = {
                Column {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        label = { Text("Name") },
                        isError = problem != null,
                        modifier = Modifier.focusRequester(nameFocus),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        problem ?: "${SessionNameRules.HINT} Opens Claude Code on the host.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (problem != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Box {
                        OutlinedTextField(
                            value = newDirField,
                            onValueChange = { newDirField = StartDirField.oneLine(it); createError = null },
                            // ⚠ TWO LINES, so a long path shows WHOLE: one line put
                            // `/root/projects/huginn-e2e/al` on screen and hid the
                            // tail that names the directory. Still one line of TEXT
                            // (oneLine), and Done rather than a return key.
                            singleLine = false,
                            maxLines = 2,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                            ),
                            label = { Text(StartDirRules.LABEL) },
                            placeholder = { Text(recentDirs?.default ?: "/path/on/the/server") },
                            isError = dirProblem != null || createError != null,
                            trailingIcon = if (dirChoices.isEmpty()) null else ({
                                TextButton(onClick = { newDirPicking = true }) { Text("Recent") }
                            }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        HouseMenu(
                            expanded = newDirPicking,
                            onDismiss = { newDirPicking = false },
                            items = dirChoices.mapIndexed { i, d ->
                                HouseMenuItem(dirLabels.getOrElse(i) { d }) { newDirField = StartDirField.picked(d); createError = null }
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        createError ?: dirProblem ?: StartDirRules.hint(recentDirs),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (createError != null || dirProblem != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }

    renaming?.let { from ->
        HouseDialog(
            title = "Rename $from",
            onDismiss = { renaming = null },
            confirmLabel = "Rename",
            onConfirm = { renaming = null; onRename(from, renameTo) },
            confirmEnabled = renameTo.isNotBlank() && renameTo != from,
            text = {
                OutlinedTextField(
                    value = renameTo,
                    onValueChange = { renameTo = it },
                    singleLine = true,
                    label = { Text("New name") },
                )
            },
        )
    }

    confirmKill?.let { name ->
        HouseDialog(
            title = null,
            titleContent = { ConfirmTitle(EndVerbs.HARD, name) },
            onDismiss = { confirmKill = null },
            confirmLabel = EndVerbs.HARD,
            onConfirm = { confirmKill = null; onKill(name) },
            destructive = true,
            text = { Text("The session and anything running inside it are terminated. Unsaved work in that session is lost.") },
        )
    }

    confirmArchive?.let { name ->
        HouseDialog(
            title = null,
            titleContent = { ConfirmTitle("Archive", name) },
            onDismiss = { confirmArchive = null },
            confirmLabel = "Archive",
            onConfirm = { confirmArchive = null; onArchive(name) },
            destructive = true,
            text = {
                Text(
                    "Claude is asked to wrap up, and the session is ended once it settles. " +
                        "It moves to Archived with the directory it ran in, a copy of the " +
                        "conversation and the exact resume command, so you can bring it back.",
                )
            },
        )
    }

    confirmDeleteArchive?.let { row ->
        HouseDialog(
            title = null,
            titleContent = { ConfirmTitle("Forget", ArchiveRules.label(row)) },
            onDismiss = { confirmDeleteArchive = null },
            confirmLabel = "Forget",
            onConfirm = { val r = row; confirmDeleteArchive = null; onDeleteArchive(r) },
            destructive = true,
            // Named for what is actually lost. "Delete" against a row that looks
            // like a list entry reads as tidying; the copy of the conversation
            // going with it is the part worth a sentence.
            text = { Text("The row and the kept copy of its conversation are removed. This cannot be undone.") },
        )
    }

    confirmSoftEnd?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmSoftEnd = null },
            title = { ConfirmTitle(EndVerbs.SOFT, name) },
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
    /**
     * ⚠ HOISTED (P-02). This was `var menu by remember` in here, so the screen
     * could not tell that a gesture was in progress and the list went on
     * re-sorting underneath the open menu. The owner of the state is now the
     * screen, which is also what freezes the order.
     */
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onKill: () -> Unit,
    onSoftEnd: () -> Unit = {},
    /** Null on a daemon without the archive feature, which is what hides the item. */
    onArchive: (() -> Unit)? = null,
    onRename: () -> Unit,
    /** The row is one column of a two-pane layout: the runtime mark takes its short form. */
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val c = LocalHuginnColors.current
    val backgroundWork = s.bgShells > 0 || s.bgAgents > 0
    // The daemon is cycling this session's Claude Code (appd 3.19): drawn as a
    // session that is starting up — the busy word and the breathing dot — and
    // the stale mark below stays off, because the restart IS its answer.
    val restarting = SessionRestart.restarting(s.runtime)
    // THE HOUSE ROW (UI-AUDIT section 3): dot · mono name · one state line ·
    // ctx, age and the menu trailing. The detail lines below ride ListRow's
    // `detail` slot so the frame, the inset and the selection are the house ones.
    //
    // ⚠ THE NAME GIVES WAY, NOT THE AGE. 2026-10-02 on a foldable at font scale
    // 2.0: "now" vanished from one card and was 8 px wide and 252 px tall on
    // another, and "ctx 48%" took three lines. ListRow measures the trailing
    // group first and gives the title column (weighted) what is left; the name
    // ellipsises on one line there, and the trailing ctx and age never wrap.
    ListRow(
        title = s.name,
        titleMono = true,
        // The state word sits beside the name on the title line (owner, 2026-10-13),
        // not on a line of its own. "waiting" over a session running four background
        // shells was a lie of omission — the list is where "looks stalled" started.
        titleSuffix = {
            Text(
                SessionRestart.titleWord(s.state, backgroundWork, s.runtime),
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    restarting -> MaterialTheme.colorScheme.primary
                    s.state == "attention" -> c.stateAttention
                    s.state == "running" || backgroundWork -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
        },
        selected = selected,
        onClick = onOpen,
        modifier = modifier,
        leading = {
            // One 8 dp dot column, so the quiet 6 dp waiting dot does not shift the name.
            Box(Modifier.size(Scale.dot), contentAlignment = Alignment.Center) {
                if (restarting || s.state == "running" || backgroundWork) {
                    PulsingDot(
                        if (s.state == "attention") c.stateAttention
                        else MaterialTheme.colorScheme.primary
                    )
                } else {
                    // The shared state dot: 8 dp live, the quiet 6 dp for waiting.
                    com.silencelen.huginn.ui.theme.StateDot(s.state)
                }
            }
        },
        trailing = {
            // "ctx N%" next to the time; renders nothing when the host didn't report.
            ContextBadge(s.contextPercent, Modifier.padding(end = Scale.s8))
            Text(
                relTime(s.activityAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
            Box {
                IconButton(onClick = { onMenu(true) }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Session actions")
                }
                // The house menu (pr/03-row-system.md). Every verb carries a
                // leading icon and its tone; HouseMenu inks label and icon alike
                // from `verbInk`.
                HouseMenu(
                    expanded = menuOpen,
                    onDismiss = { onMenu(false) },
                    items = buildList {
                        add(HouseMenuItem("Rename", icon = Icons.Filled.DriveFileRenameOutline, onClick = onRename))
                        // THE TWO ENDING VERBS, AND THEY READ AS A PAIR. The kill in
                        // the full `error` red, the wrap-up in the soft tone — from
                        // `verbInk`, the single token in `:ui`'s theme that the
                        // desktop's right-click menu also draws from. Two shells
                        // picking their own lighter red is the drift that makes a
                        // phone held up beside the laptop look like a different
                        // product.
                        //
                        // ⚠ AN ICON ON EVERY VERB (P-22). Without one the label
                        // started in the icon COLUMN (x=754) while Rename, Archive
                        // and Kill started at x=849, and a menu item that hangs off
                        // the left of the others reads as a different class of
                        // thing than it is.
                        add(HouseMenuItem(EndVerbs.soft(1), icon = Icons.Filled.TaskAlt, tone = VerbTone.SOFT, onClick = onSoftEnd))
                        // Between the wrap-up and the kill, where it belongs: it is a
                        // wrap-up that leaves something behind. Neither red — ending a
                        // session you can bring back is the least destructive of the
                        // three.
                        if (onArchive != null) {
                            add(HouseMenuItem("Archive…", icon = Icons.Filled.Inventory2, onClick = onArchive))
                        }
                        add(HouseMenuItem(EndVerbs.hard(1), icon = Icons.Filled.Delete, tone = VerbTone.DESTRUCTIVE, onClick = onKill))
                    },
                )
            }
        },
        detail = {
            if (s.compacting) {
                CompactingChip(Modifier.padding(top = Scale.s4))
            }

            // Bound to a local first: `s.title` is a public property of another
            // module (:core) now, so the compiler will not smart-cast it inside
            // the null check — the value could in principle change between the
            // test and the read. A local is a snapshot and needs no assertion.
            val title = s.title
            if (!title.isNullOrBlank()) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Scale.s4),
                )
            }

            if (backgroundWork) {
                Text(
                    buildList {
                        s.bgTask?.let { add("◦ $it") }
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
                // ⚠ P-14. `❯ run sleep 10 in the background then say doneB` on a
                // row nobody had typed into: Claude Code's dim inline SUGGESTION,
                // drawn here in the same weight as output and reading as a
                // pending prompt. The dimness is gone by the time a preview line
                // exists (the list's capture has no `-e`), so `PanePreview` uses
                // the same structural tell `pane.js` does. Kept, and drawn as the
                // composer rather than as something the session did.
                PanePreview.rows(s.preview.takeLast(2)).forEach { row ->
                    Text(
                        row.text,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (row.hint) MaterialTheme.colorScheme.outline
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = if (row.hint) FontStyle.Italic else null,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // The runtime mark (appd 3.19): this session's Claude Code is behind
            // what a launch would load now. On the meta line, in the state
            // language's info colour, so the row keeps its shape and the mark
            // still reads as a state rather than as one more terminal fact.
            val runtimeMark = SessionRestart.rowMark(s.runtime, short = compact)
            val meta = buildList {
                // FIRST: a message this person typed is still waiting to reach the
                // pane, and every other fact on this line is about the terminal.
                SendQueue.rowMark(s.pendingSends)?.let { add(it) }
                // A background agent waiting on an answer: quiet, never yellow —
                // its request has no dialog on this session's pane.
                AgentRequestLabels.rowMark(s.agentRequests)?.let { add(it) }
                runtimeMark?.let { add(it) }
                if (s.cols > 0) add("${s.cols}x${s.rows}")
                if (s.attachedClients > 0) add("${s.attachedClients} attached")
                if (s.sizeLeased) add("fitted to phone")
            }
            if (meta.isNotEmpty()) {
                val markInk = stateColor(StateKind.INFO)
                Text(
                    buildAnnotatedString {
                        meta.forEachIndexed { i, part ->
                            if (i > 0) append("  ·  ")
                            if (part === runtimeMark) withStyle(SpanStyle(color = markInk)) { append(part) }
                            else append(part)
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    )
}


/**
 * The one line under the list that says where the project sessions are.
 *
 * ⚠ A PROJECT SESSION IS NEVER ON THIS PAGE (owner rule, 2026-09-19): it is on
 * its project's dashboard and nowhere else, because a copy of it here read as
 * a second, unrelated session — the dashboard-app lead sat between two hand-made
 * ones and looked like a session that had failed to do something. This line
 * is the list's only admission that they exist: a count, and the way there,
 * so a reader who watched a cluster spawn is not left looking for rows that
 * will never appear.
 */
@Composable
private fun InProjectsLine(count: Int, projects: Int, onOpenProjects: (() -> Unit)?) {
    val words = ProjectRules.hiddenWords(count, projects) ?: return
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            words,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        onOpenProjects?.let {
            TextButton(onClick = it, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                Text("Projects", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
