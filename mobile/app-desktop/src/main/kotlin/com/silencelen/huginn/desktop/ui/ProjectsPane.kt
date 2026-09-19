package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.ManifestSession
import com.silencelen.huginn.data.ProjectLive
import com.silencelen.huginn.data.ProjectManifest
import com.silencelen.huginn.data.ProjectMemberState
import com.silencelen.huginn.data.ProjectRow
import com.silencelen.huginn.data.SpawnOutcome
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.desktop.ui.common.Frame
import com.silencelen.huginn.desktop.ui.common.NothingOpen
import com.silencelen.huginn.desktop.ui.common.ProjectVerbs
import com.silencelen.huginn.desktop.ui.common.RowMenu
import com.silencelen.huginn.desktop.ui.common.projectMenu
import com.silencelen.huginn.ui.CreateProjectSheet
import com.silencelen.huginn.ui.ManifestCard
import com.silencelen.huginn.ui.PROJECTS_BLURB
import com.silencelen.huginn.ui.ProjectDashboardView
import com.silencelen.huginn.ui.ProjectMemberActions
import com.silencelen.huginn.ui.ProjectRules
import com.silencelen.huginn.ui.ProjectsListView
import kotlinx.coroutines.launch

/**
 * The desktop's home for a cluster of sessions.
 *
 * TWO PANES, and which half you are in decides what the window is for. The LIST
 * is the tree — every project, folded open to its members — and it is the
 * navigation; the DETAIL is either the project's dashboard (with the lead's
 * proposal on top of it when there is one) or one member's ordinary session,
 * drawn by the same [SessionView] the Sessions tab uses.
 *
 * ⚠ A MEMBER OPENS *INSIDE* PROJECTS, not by walking to the Sessions tab. The
 * cluster is the context — which role this is, who else is running, what the lead
 * asked for — and throwing it away to read one session's transcript is how a
 * twelve-session project becomes twelve unrelated windows. The crumb at the top
 * of the detail pane is what keeps the way back, and it is also where both
 * right-click menus live.
 *
 * ⚠⚠ THE VERB MENUS ARE ON THE CRUMB, NOT ON THE TREE ROWS, and that is a
 * deliberate difference from Chats and Sessions. Those two lists are drawn by
 * THIS module ([Lists.kt]), so a row can be wrapped in a [RowMenu]; the tree is
 * `ProjectsListView` in `:ui`, shared with the phone, which has no menu slot and
 * should not grow one for a pointer the phone does not have — the same reason the
 * pages list has no row menu here either. The crumb names exactly the thing whose
 * verbs it offers, which is the property a menu actually needs.
 */

/**
 * What the pane says after a Spawn, in the daemon's own words.
 *
 * ⚠⚠ A 200 IS NOT A VERDICT AND `ok=false` IS THE NORMAL CASE. Spawning is a loop
 * over tmux: the second of three roles failing does not un-spawn the first, so
 * the daemon carries on, makes the rest, and answers with both lists. Collapsed
 * to "spawn failed" the owner loses which role to retry and is told a working
 * cluster is broken; collapsed to "spawned" they are not told at all.
 *
 * ⚠ AND THE REFUSAL IS VERBATIM. The two that matter are the headroom arbiter's
 * STOP sentinel and a manifest that moved under the card, and both are states of
 * the house with the fix inside the sentence. A summary of our own turns "there
 * is no room on this account right now" into "could not spawn", which is a dead
 * end.
 *
 * @return null when everything asked for came up — success says nothing, because
 *   the members appearing in the tree IS the message.
 */
fun spawnOutcomeNote(outcome: SpawnOutcome?): String? {
    if (outcome == null) return null
    outcome.refusal?.let { return it }
    val result = outcome.result ?: return null
    if (result.failed.isEmpty()) return null
    // ProjectRules.spawnFailures is the shared sentence-per-role, so the phone
    // and this window report a partial spawn in the same words.
    return (listOf(ProjectRules.spawnWords(result)) + ProjectRules.spawnFailures(result))
        .joinToString("\n")
}

// ------------------------------------------------------------------ the list

/** The tree: every project, folded open to its live members. */
@Composable
fun ProjectsList(store: AppStore) {
    val projects by store.projects.collectAsState()
    val expanded by store.projectsExpanded.collectAsState()
    val members by store.projectMembers.collectAsState()
    val refusal by store.projectRefusal.collectAsState()
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ProjectsListView(
            projects = projects,
            // Sampled per composition rather than ticked: the list re-polls every
            // five seconds while it is open, and every time word on it is measured
            // in minutes. The same reasoning as the Devices pane.
            nowMs = System.currentTimeMillis(),
            expanded = expanded,
            members = members,
            onToggle = { id ->
                store.toggleProject(id)
                // Fetched on the fold rather than waiting out the poll: the
                // disclosure is the request, and an empty cluster that fills five
                // seconds later reads as a project with no members.
                scope.launch { store.refreshProjectMembers() }
            },
            onOpenProject = { store.openProject(it.id) },
            onOpenMember = { project, member ->
                store.openProject(project.id)
                store.openProjectMember(member.name)
            },
            onCreate = { creating = true },
        )
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false; store.clearProjectRefusal() },
            title = {},
            text = {
                CreateProjectSheet(
                    taken = projects.map { it.name },
                    takenSlugs = projects.map { it.slug },
                    onDismiss = { creating = false; store.clearProjectRefusal() },
                    onCreate = { name, kind, brief, cwd ->
                        scope.launch {
                            // The sheet stays OPEN on a refusal, with everything
                            // typed still in it — the commonest one is a working
                            // directory Claude Code has not been trusted in, and
                            // the daemon's sentence about it is the entire fix.
                            if (store.createProject(name, kind, brief, cwd)) creating = false
                        }
                    },
                    refusal = refusal,
                )
            },
            confirmButton = {},
        )
    }
}

// ---------------------------------------------------------------- the detail

/** The dashboard, the proposal, or one member's session — plus the crumb above them. */
@Composable
fun ProjectsDetail(store: AppStore) {
    val projects by store.projects.collectAsState()
    val projectId by store.projectId.collectAsState()
    val memberName by store.projectMember.collectAsState()
    val allMembers by store.projectMembers.collectAsState()
    val dashboard by store.projectDashboard.collectAsState()
    val record by store.project.collectAsState()
    val refusal by store.projectRefusal.collectAsState()
    // What "Add member" can offer: sessions this client can see running. A
    // session belonging to ANOTHER project looks free from here — the daemon
    // holds that join and answers 409 naming it, which is a better answer than a
    // row quietly missing from the picker.
    val sessions by store.sessions.collectAsState()
    val scope = rememberCoroutineScope()

    var renaming by remember { mutableStateOf<ProjectRow?>(null) }
    var deleting by remember { mutableStateOf<ProjectRow?>(null) }
    var messaging by remember { mutableStateOf<Pair<ProjectRow, ProjectMemberState>?>(null) }
    var editing by remember { mutableStateOf<ProjectManifest?>(null) }
    var spawnNote by remember(projectId) { mutableStateOf<String?>(null) }

    val row = projects.firstOrNull { it.id == projectId }
    if (row == null) {
        // ⚠ SAID ONCE, AND SAID BY THE PANE WITH THE ROOM. With no projects both
        // panes are on screen together, and each used to explain what a project
        // is — two different wordings of the same three facts, side by side. The
        // list keeps the short fact; the explanation is here. And "pick one on
        // the left" is only true when there IS one on the left.
        NothingOpen(
            if (projects.isEmpty()) "No projects yet" else "No project open",
            if (projects.isEmpty()) {
                "$PROJECTS_BLURB Make one with + New."
            } else {
                "Pick one on the left to see its dashboard, or make another with + New."
            },
            emptyList(),
        )
        return
    }
    val live = allMembers[row.id].orEmpty()
    val member = memberName?.let { name -> live.firstOrNull { it.name == name } }

    val verbs = ProjectVerbs(
        open = { store.openProject(it.id) },
        rename = { renaming = it },
        setStatus = { p, status -> scope.launch { store.saveProject(p.id, p.rev, status = status) } },
        delete = { deleting = it },
        openMember = { p, m -> store.openProject(p.id); store.openProjectMember(m.name) },
        message = { p, m -> messaging = p to m },
        // ⚠ THE TMUX SESSION, NOT THE MEMBERSHIP ROW. Ending a member stops the
        // `claude` in that pane; the project keeps the record, which is what makes
        // "it died and I can see that it died" possible at all.
        endMember = { _, m ->
            scope.launch {
                store.client.killSession(m.name)
                store.openProjectMember(null)
                store.refreshProjectMembers()
                store.refreshSessions()
            }
        },
    )

    Column(Modifier.fillMaxSize()) {
        ProjectCrumb(row, member, verbs, onBackToProject = { store.openProjectMember(null) })
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (member != null) {
            // The ordinary session detail, reused whole. A member is a session;
            // drawing a second, thinner version of one here would be a transcript
            // that behaves differently depending on how you arrived at it.
            SessionView(store, member.name)
            return@Column
        }
        ProjectDashboardView(
            dashboard = dashboard,
            nowMs = System.currentTimeMillis(),
            onOpenMember = { store.openProjectMember(it.name) },
            modifier = Modifier.fillMaxSize(),
            manifest = record?.manifest?.takeIf { ProjectRules.hasProposal(row) }?.let { m ->
                {
                    ManifestCard(
                        manifest = m,
                        onSpawn = {
                            scope.launch {
                                // THE REV, NOT THE PLAN: a card left open while
                                // the lead revised its proposal cannot approve the
                                // revision. The daemon answers 409 and the refusal
                                // lands under the card, verbatim.
                                spawnNote = spawnOutcomeNote(store.spawnProject(row.id, m.rev))
                            }
                        },
                        onEdit = { editing = m },
                        onDiscard = { scope.launch { store.discardProposal(row.id) } },
                        refusal = refusal ?: spawnNote,
                    )
                }
            },
            // Adopt and drop. Both edit the RECORD only: nothing is launched,
            // nothing is ended — see ProjectMemberActions.
            membership = ProjectMemberActions(
                liveSessions = sessions.map { it.name },
                onAdopt = { role, name -> scope.launch { store.adoptMember(row.id, role, name) } },
                onDrop = { m -> scope.launch { store.dropMember(row.id, m.role) } },
                refusal = refusal,
                clearRefusal = { store.clearProjectRefusal() },
            ),
        )
    }

    renaming?.let { target ->
        ProjectRenameDialog(
            target = target,
            taken = projects.filter { it.id != target.id }.map { it.name },
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                scope.launch { store.saveProject(target.id, target.rev, name = name) }
            },
        )
    }

    deleting?.let { target ->
        DeleteProjectDialog(
            project = target,
            onDismiss = { deleting = null },
            onConfirm = { end ->
                deleting = null
                scope.launch { store.deleteProject(target.id, end) }
            },
        )
    }

    messaging?.let { (project, target) ->
        MessageMemberDialog(
            member = target,
            onDismiss = { messaging = null },
            onSend = { text ->
                messaging = null
                scope.launch {
                    // FROM THE LEAD, which is the only sender a person choosing
                    // this from a menu could mean: the owner is speaking as the
                    // session that runs this cluster, not as another member.
                    store.messageProject(project.id, from = ProjectRules.LEAD_ROLE, to = target.role, text = text)
                }
            },
        )
    }

    editing?.let { m ->
        ManifestEditorDialog(
            manifest = m,
            onDismiss = { editing = null },
            onSave = { edited ->
                editing = null
                scope.launch { store.saveProject(row.id, row.rev, manifest = edited) }
            },
        )
    }
}

/**
 * Where you are, and every verb about it.
 *
 * ⚠ THE PROJECT HALF IS A LINK WHEN A MEMBER IS OPEN. It is the only way back to
 * the dashboard that does not go through the tree, and on a narrow window the
 * tree is folded away — so without it Escape would be the sole route out of a
 * member, which is a keyboard-only door on a pointer-shaped screen.
 */
@Composable
private fun ProjectCrumb(
    project: ProjectRow,
    member: ProjectLive?,
    verbs: ProjectVerbs,
    onBackToProject: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RowMenu({ projectMenu(project, verbs) }) {
                Text(
                    ProjectRules.label(project),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (member == null) Modifier else Modifier.clickable(onClick = onBackToProject),
                )
            }
            if (member != null) {
                Text(
                    "/",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RowMenu({ projectMenu(project, member, verbs) }) {
                    Text(
                        member.role.ifBlank { member.name },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                listOfNotNull(
                    if (member != null) ProjectRules.memberWords(member) else ProjectRules.statusWords(project.status),
                    if (member == null) ProjectRules.rollupWords(project) else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ------------------------------------------------------------------ dialogs

@Composable
private fun ProjectRenameDialog(
    target: ProjectRow,
    taken: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(target.id) { mutableStateOf(target.name) }
    val problem = ProjectRules.nameProblem(text, taken)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename project", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column {
                DialogField(
                    value = text,
                    ok = problem == null,
                    label = "Project name",
                    placeholder = "What this cluster is for",
                ) { text = it }
                Text(
                    // ⚠ SAID OUT LOUD, because it is the one thing a rename here
                    // does NOT do. The slug is the tmux and peer namespace every
                    // member is already named in; it was fixed when the project
                    // was made and it never moves.
                    problem ?: "The sessions keep their names: ${target.slug}-<role>.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = problem == null, onClick = { onConfirm(text.trim()) }) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Delete, and the question that makes it safe.
 *
 * ⚠⚠ THREE BUTTONS BECAUSE THERE ARE THREE OUTCOMES, and the daemon's default is
 * the gentlest of them: the record goes and the sessions are left alone unless
 * ending them was asked for. A single "Delete" that silently killed twelve live
 * sessions is not a delete anybody meant — and one that silently left twelve
 * orphaned sessions behind is not one either, unless it was said.
 */
@Composable
private fun DeleteProjectDialog(
    project: ProjectRow,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${ProjectRules.label(project)}?", style = MaterialTheme.typography.titleSmall) },
        text = {
            Text(
                "huginn forgets the project: its members, the brief and the lead's proposal. " +
                    "What happens to the ${project.memberCount + 1} sessions is up to you — " +
                    "leaving them running keeps every transcript exactly where it is, in the " +
                    "Sessions list.",
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onConfirm(null) }) { Text("Leave them running") }
                TextButton(onClick = { onConfirm("graceful") }) { Text("Wind them down") }
                TextButton(onClick = { onConfirm("now") }) { Text("End them now") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A line typed into one member.
 *
 * ⚠ THIS IS THE DAEMON TYPING, not the sessions talking to each other. A peer
 * `SendMessage` travels their own socket and starts a turn with no keypress;
 * this rides the send queue and waits on the same gates an ordinary send does,
 * which is why it can land in a composer rather than interrupt a turn.
 */
@Composable
private fun MessageMemberDialog(
    member: ProjectMemberState,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    var text by remember(member.name) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message ${member.claudeName.ifBlank { member.name }}", style = MaterialTheme.typography.titleSmall) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Message", style = MaterialTheme.typography.labelSmall) },
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onSend(text.trim()) }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The proposal, edited before it is approved.
 *
 * ⚠⚠ THE STRUCTURE IS THE DAEMON'S AND THIS EDITS ITS FIELDS, never a fence. The
 * lead writes a tagged fenced block; `lib/projects.js` parses it, refuses
 * anything that decides what gets created, and emits the sessions. A client that
 * let the owner retype the block would be handing the parser a second author.
 *
 * ⚠ THE SAVE IS REV-GUARDED and the rev is the PROJECT's, not the manifest's: a
 * save made against a proposal the lead has since revised comes back as a 409
 * carrying the current project, and the card redraws around the plan that is
 * actually on offer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManifestEditorDialog(
    manifest: ProjectManifest,
    onDismiss: () -> Unit,
    onSave: (ProjectManifest) -> Unit,
) {
    var sessions by remember(manifest.rev) { mutableStateOf(manifest.sessions) }
    fun edit(index: Int, change: (ManifestSession) -> ManifestSession) {
        sessions = sessions.mapIndexed { i, s -> if (i == index) change(s) else s }
    }
    val problems = sessions.mapIndexed { i, s ->
        ProjectRules.roleProblem(s.role, sessions.filterIndexed { j, _ -> j != i }.map { it.role })
            ?: "the first message cannot be empty".takeIf { s.firstPrompt.isBlank() }
    }
    val ok = sessions.isNotEmpty() && problems.all { it == null }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit the proposal", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(Modifier.widthIn(max = Frame.reading).verticalScroll(rememberScrollState())) {
                Text(
                    manifest.scope.ifBlank { "No scope was written." },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                sessions.forEachIndexed { i, s ->
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(8.dp))
                    DialogField(
                        value = s.role,
                        ok = problems[i] == null,
                        label = "Role",
                        placeholder = "docs",
                    ) { v -> edit(i) { it.copy(role = v) } }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = s.firstPrompt,
                        onValueChange = { v -> edit(i) { m -> m.copy(firstPrompt = v) } },
                        label = { Text("First message", style = MaterialTheme.typography.labelSmall) },
                        textStyle = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp),
                    )
                    problems[i]?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    // ⚠ UNKNOWN STAYS UNKNOWN. The three vocabularies are closed
                    // and the daemon already answers null for a word it does not
                    // know; the chips offer what is known and a second press
                    // clears back to "whatever the host defaults to", which is a
                    // real answer rather than a missing one.
                    ChipRow("Model", MODELS, s.model) { v -> edit(i) { it.copy(model = v) } }
                    ChipRow("Effort", EFFORTS, s.effort) { v -> edit(i) { it.copy(effort = v) } }
                    ChipRow("Mode", MODES, s.mode) { v -> edit(i) { it.copy(mode = v) } }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = { onSave(manifest.copy(sessions = sessions)) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The closed vocabularies, as the daemon writes them. */
private val MODELS = listOf("fable", "opus", "sonnet", "haiku")
private val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")
private val MODES = listOf("ask", "act", "auto", "plan")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(label: String, options: List<String>, selected: String?, onPick: (String?) -> Unit) {
    Spacer(Modifier.height(6.dp))
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onPick(if (selected == option) null else option) },
                label = { Text(option, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center) },
            )
        }
    }
}
