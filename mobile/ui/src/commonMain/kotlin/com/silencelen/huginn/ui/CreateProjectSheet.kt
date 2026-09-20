package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Starting a project: a name, what kind it is, the brief, and where it runs.
 *
 * ⚠⚠ THE BRIEF IS A FIELD HERE BECAUSE THE DAEMON REFUSES A CREATE WITHOUT ONE,
 * and that is not a validation detail. The brief is typed straight into the
 * lead's composer as the whole first message it ever gets — it IS the thing the
 * lead sizes the project from. A create sheet that left it out would launch a
 * session with nothing to do and no obvious way to give it something.
 *
 * ⚠ THE SLUG IS SHOWN, NOT ASKED FOR. It is derived from the name, it is the
 * tmux and peer namespace every member will be named in, and it NEVER moves
 * afterwards — a rename later changes the display name and nothing else. Showing
 * it here is the only moment it can be argued with.
 *
 * ⚠⚠ THE REFUSAL IS SHOWN VERBATIM, AND THE SHEET STAYS OPEN. The commonest
 * refusal is a working directory Claude Code has not been trusted in, and the
 * daemon's sentence about it IS the fix — "open it once with `claude` there and
 * accept the folder-trust question, then create the project". Replaced with a
 * summary of our own it becomes "could not create project", which is a dead end.
 * Thrown as an error it takes the typed brief with it. So the 409 comes back as
 * an answer (see `HuginnClient.createProject`) and lands here, under the fields,
 * with everything the person typed still in place.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateProjectSheet(
    /** Names already in use, so a collision is answered in the field. */
    taken: List<String>,
    onDismiss: () -> Unit,
    onCreate: (name: String, kind: String, brief: String, cwd: String?) -> Unit,
    modifier: Modifier = Modifier,
    /** Slugs already in use. Two different display names can land on one. */
    takenSlugs: List<String> = emptyList(),
    /** Pre-filled directory. Null leaves the field empty, and empty means the
     *  daemon makes the project its own folder — see [projectsDir]. */
    defaultCwd: String? = null,
    /**
     * Where the daemon makes a project's folder when none is named (`GET
     * /v1/projects` → `dir`), so the empty field can say the path it will use.
     * Null on an older daemon, and the field then says only what happens.
     */
    projectsDir: String? = null,
    /** In flight — the button is held so a double tap cannot make two projects. */
    busy: Boolean = false,
    /** The daemon's last refusal, drawn UNDER the fields, exactly as it was written. */
    refusal: String? = null,
) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ProjectRules.KINDS.first()) }
    var brief by remember { mutableStateOf("") }
    var cwd by remember(defaultCwd) { mutableStateOf(defaultCwd.orEmpty()) }

    val slug = ProjectRules.slugFor(name)
    val nameProblem = (
        ProjectRules.nameProblem(name, taken)
            ?: ProjectRules.slugProblem(slug, takenSlugs)
        ).takeIf { name.isNotBlank() }
    val briefProblem = ProjectRules.briefProblem(brief).takeIf { brief.isNotBlank() }
    val cwdProblem = ProjectRules.cwdProblem(cwd)
    val ready = !busy && name.isNotBlank() && brief.isNotBlank() &&
        nameProblem == null && briefProblem == null && cwdProblem == null

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") },
                    // The namespace, shown where it is still negotiable.
                    supportingText = {
                        if (slug.isNotEmpty()) {
                            Text(
                                "sessions will be called $slug/lead, $slug/<role>",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                    isError = nameProblem != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                nameProblem?.let { Problem(it) }
                Spacer(Modifier.height(8.dp))
                // ⚠ A CLOSED VOCABULARY, DRAWN AS ONE. The daemon refuses a create
                // whose kind is not one of these six, so a free-text field here
                // would be a round trip to be told what a chip could have said.
                Text(
                    "Kind",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(Modifier.fillMaxWidth()) {
                    ProjectRules.KINDS.forEach { k ->
                        FilterChip(
                            selected = k == kind,
                            onClick = { kind = k },
                            label = { Text(k, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = brief,
                    onValueChange = { brief = it },
                    label = { Text("Brief") },
                    placeholder = { Text("what this cluster is for, and what the lead should size") },
                    isError = briefProblem != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                )
                briefProblem?.let { Problem(it) }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = cwd,
                    onValueChange = { cwd = it },
                    singleLine = true,
                    label = { Text("Directory") },
                    placeholder = { Text(ProjectRules.newFolderWords(projectsDir, name)) },
                    isError = cwdProblem != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                cwdProblem?.let { Problem(it) }
                Spacer(Modifier.height(6.dp))
                Text(
                    CREATE_PROJECT_BLURB,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // ⚠ THE DAEMON'S OWN WORDS, UNTOUCHED. See the KDoc — four lines
                // rather than two, because the sentence that matters here ends in
                // the instruction and a warning cut before its own consequence is
                // a warning that has not been given.
                refusal?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = {
                    onCreate(
                        ProjectRules.cleanName(name),
                        kind,
                        brief.trim(),
                        cwd.trim().takeIf { it.isNotEmpty() },
                    )
                },
            ) { Text(if (busy) "Starting…" else "Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Problem(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/** What creating a project actually does, said before it is done. */
const val CREATE_PROJECT_BLURB: String =
    "This starts one session — the lead — and types the brief into it. It sizes the work " +
        "and proposes the members; nothing else is started until you approve them."
