package com.silencelen.huginn.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.ManifestSession
import com.silencelen.huginn.data.ProjectDashboard
import com.silencelen.huginn.data.ProjectDashboardMember
import com.silencelen.huginn.data.ProjectDetail
import com.silencelen.huginn.data.ProjectManifest

/**
 * One project: what the cluster has done, and the proposal waiting on the owner.
 *
 * The dashboard and the manifest card are both `:ui`; what is here is the phone's
 * arrangement of them and the one thing `:ui` does not ship — an editor for a
 * proposal, which on this shell is a dialog rather than a screen because editing
 * a plan is a detour from approving it, not a destination.
 */
@Composable
fun ProjectDashboardScreen(
    detail: ProjectDetail?,
    dashboard: ProjectDashboard?,
    nowMs: Long,
    onOpenMember: (ProjectDashboardMember) -> Unit,
    onSpawn: (Int) -> Unit,
    onDiscard: () -> Unit,
    onSaveManifest: (ProjectManifest) -> Unit,
    busy: Boolean = false,
    /** The daemon's refusal, shown on the card in its own words. */
    refusal: String? = null,
    /** Adopt and drop. Null draws no membership controls — see [ProjectMemberActions]. */
    membership: ProjectMemberActions? = null,
) {
    var editing by remember { mutableStateOf<ProjectManifest?>(null) }
    val project = detail?.project
    val manifest = project?.manifest

    ProjectDashboardView(
        dashboard = dashboard,
        nowMs = nowMs,
        onOpenMember = onOpenMember,
        modifier = Modifier.fillMaxSize(),
        manifest = if (manifest != null && ProjectRules.hasProposal(project)) ({
            ManifestCard(
                manifest = manifest,
                busy = busy,
                refusal = refusal,
                onSpawn = { onSpawn(manifest.rev) },
                onEdit = { editing = manifest },
                onDiscard = onDiscard,
            )
        }) else null,
        membership = membership,
    )

    editing?.let { m ->
        ManifestEditDialog(
            manifest = m,
            onDismiss = { editing = null },
            onSave = { edited -> editing = null; onSaveManifest(edited) },
        )
    }
}

/**
 * The proposal, editable.
 *
 * Bounded on purpose: a role and its first prompt, and the power to drop a
 * session. Nothing here ADDS one — the lead sized the work and the owner is
 * approving or trimming it, and a client that invented a fourteenth session would
 * be proposing rather than approving. The daemon re-validates every field with
 * the same parser the lead's own block goes through, so this is a convenience in
 * front of the rules rather than a second set of them.
 *
 * ⚠ THE REV IS CARRIED, NOT RESET. Saving a manifest is a PATCH guarded by the
 * project's own rev; the manifest rev the daemon bumps is its business. An editor
 * that stamped a rev here would be deciding that a plan it had not re-read was
 * the current one.
 */
@Composable
private fun ManifestEditDialog(
    manifest: ProjectManifest,
    onDismiss: () -> Unit,
    onSave: (ProjectManifest) -> Unit,
) {
    var rows by remember(manifest) { mutableStateOf(manifest.sessions) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit the proposal") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "The roles and the first message each session gets. Huginn checks these on the " +
                        "host with the same rules the lead wrote them under.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                rows.forEachIndexed { i, s ->
                    val others = rows.filterIndexed { j, _ -> j != i }.map { it.role }
                    val problem = ProjectRules.roleProblem(s.role, others)
                    OutlinedTextField(
                        value = s.role,
                        onValueChange = { v -> rows = rows.replaceAt(i) { it.copy(role = v) } },
                        singleLine = true,
                        isError = problem != null,
                        label = { Text("Role") },
                        supportingText = problem?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = s.firstPrompt,
                        onValueChange = { v -> rows = rows.replaceAt(i) { it.copy(firstPrompt = v) } },
                        label = { Text("First message") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Never below one: a proposal with no sessions is a discard
                    // wearing an editor's clothes, and Discard already exists.
                    if (rows.size > 1) {
                        TextButton(onClick = { rows = rows.filterIndexed { j, _ -> j != i } }) {
                            Text("Remove ${s.role.ifBlank { "this session" }}")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(manifest.copy(sessions = rows)) },
                enabled = manifestEditable(rows),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * What ending a project may do to the sessions it owns, in THE DAEMON'S OWN
 * SPELLINGS.
 *
 * ⚠⚠ A WRONG SPELLING HERE FAILS SILENTLY AND DANGEROUSLY. The handler reads
 * `end` and accepts exactly `"now"` and `"graceful"`; **anything else ends
 * nothing and deletes the record anyway** (`const end = raw === 'now' || raw ===
 * 'graceful' ? raw : ''`). So "End them now" sent as "hard" or "kill" would look
 * like it worked, forget the project, and leave twelve sessions running with
 * nothing pointing at them — which is the exact stranding P-15 is about, arrived
 * at from the other direction. Typed and tested for that reason.
 */
enum class ProjectEnd(val wire: String?) {
    /** Only the record goes. The daemon's default, and the safe one. */
    KEEP(null),

    /** Each member gets the wrap-up phrase through the send queue and auto-ends on settle. */
    GRACEFUL("graceful"),

    /** Hard end, every member. */
    NOW("now"),
}

/**
 * Winding a cluster down from the phone.
 *
 * ⚠⚠ THE PHONE HAD NO WAY TO DO THIS AT ALL. The project page offered "Add
 * member" and nothing else — no overflow menu, no row menu on the list, no
 * per-member action — so a project started on the phone STRANDED its sessions:
 * they had to be hunted down one at a time in the Sessions list and killed
 * there. The desktop keeps this on a row context menu; the phone's equivalent
 * slot is the top bar's action menu, which is where it is now raised from.
 *
 * ⚠ THREE OUTCOMES, NAMED, AND THE SAFE ONE IS NOT THE ONE IN THE CORNER. The
 * daemon's `DELETE /v1/projects/:id?end=…` defaults to ending NOTHING and only
 * forgetting the record; `graceful` sends each member the wrap-up phrase through
 * the ordinary send queue and arms the auto-end on the settle (a member sitting
 * on a dialog is REFUSED and named, not typed at); `now` is a hard end. A
 * delete that silently killed twelve live sessions is not a delete anybody
 * meant, so the wording says what happens to the sessions before it says what
 * happens to the project.
 *
 * A column of choices rather than the desktop's row of three: three verbs beside
 * a Cancel do not fit a phone dialog's button row, and these are the kind of
 * choice a person should read down rather than scan across.
 */
@Composable
fun EndProjectDialog(
    label: String,
    /** The lead plus its members — what "them" means in the choices below. */
    sessions: Int,
    onDismiss: () -> Unit,
    onEnd: (ProjectEnd) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("End $label?") },
        text = {
            Column {
                Text(
                    "huginn forgets the project: its members, the brief and the lead's proposal. " +
                        "What happens to the $sessions ${if (sessions == 1) "session" else "sessions"} " +
                        "is up to you.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                EndChoice(
                    "Leave them running",
                    "Only the project record goes. Every transcript stays exactly where it is, " +
                        "in the Sessions list.",
                ) { onEnd(ProjectEnd.KEEP) }
                EndChoice(
                    "Wind them down",
                    "Each session is asked to finish and commit, and ends on its own once it " +
                        "settles. One sitting on a question is left alone and named.",
                ) { onEnd(ProjectEnd.GRACEFUL) }
                EndChoice(
                    "End them now",
                    "Every session and whatever is running inside it is terminated. Unsaved work " +
                        "in them is lost.",
                    destructive = true,
                ) { onEnd(ProjectEnd.NOW) }
            }
        },
        // No confirm button: the three above ARE the confirmations, and a fourth
        // one would have to mean one of them by default.
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EndChoice(
    label: String,
    summary: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Text(
            summary,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun List<ManifestSession>.replaceAt(i: Int, f: (ManifestSession) -> ManifestSession) =
    mapIndexed { j, s -> if (j == i) f(s) else s }

/**
 * Whether an edited proposal may be saved: every role legal and distinct, every
 * first prompt non-empty.
 *
 * Mirrors the daemon rather than replacing it — `ProjectRules` IS the mirror, and
 * this is only the place that asks it about a whole list at once.
 */
fun manifestEditable(rows: List<ManifestSession>): Boolean {
    if (rows.isEmpty() || rows.size > ProjectRules.MAX_MEMBERS) return false
    return rows.withIndex().all { (i, s) ->
        val others = rows.filterIndexed { j, _ -> j != i }.map { it.role }
        ProjectRules.roleProblem(s.role, others) == null && s.firstPrompt.isNotBlank()
    }
}

// ------------------------------------------------------------- the poll gate

/**
 * What a dashboard poll is allowed to be a no-op.
 *
 * ⚠⚠ A DASHBOARD TICK IS NOT FREE ON THE HOST, AND IT IS NOT FREE HERE EITHER.
 * The daemon sums up to twelve session overviews to answer this, and the client
 * then redraws a table of twelve rows of numbers. `generatedAt` is the daemon's
 * own stamp for "this is when I answered", so an answer carrying the stamp we
 * already hold is the same answer — publishing it recomposes the whole screen to
 * draw identical figures, five seconds apart, for as long as the screen is open.
 *
 * ⚠ ZERO IS NOT A STAMP. A daemon that did not fill the field leaves 0, and
 * treating 0 as "unchanged" would freeze the screen on its first answer forever.
 * So 0 always passes.
 */
class DashboardCursor {
    private var seen: Long = -1L

    /** True when this answer is new work. False means: keep what is on screen. */
    fun accept(next: ProjectDashboard?): Boolean {
        if (next == null) return false
        val at = next.generatedAt
        if (at > 0 && at == seen) return false
        seen = at
        return true
    }

    /** Forgets the stamp, so the next answer always lands. Used when the project changes. */
    fun reset() { seen = -1L }
}
