package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Starting a project: a name, and where it runs.
 *
 * Two fields, because two is what the daemon needs to launch a lead session; the
 * brief is the FIRST MESSAGE that session gets, and a first message belongs in a
 * composer rather than in a creation form.
 *
 * ⚠⚠ THE REFUSAL IS SHOWN VERBATIM, AND THE SHEET STAYS OPEN. The commonest
 * refusal is a working directory Claude Code has not been trusted in, and the
 * daemon's sentence about it IS the fix — "open that directory in Claude Code
 * once, then create the project". Replaced with a summary of our own it becomes
 * "could not create project", which is a dead end. Thrown as an error it takes
 * the typed name with it. So the 409 comes back as an answer (see
 * `HuginnClient.createProject`) and lands here, under the field, with everything
 * the person typed still in place.
 */
@Composable
fun CreateProjectSheet(
    /** Names already in use, so a collision is answered in the field. */
    taken: List<String>,
    onDismiss: () -> Unit,
    onCreate: (name: String, cwd: String?) -> Unit,
    /** Pre-filled directory: the shell's own default (the daemon's WORKDIR). */
    defaultCwd: String? = null,
    /** In flight — the button is held so a double tap cannot make two projects. */
    busy: Boolean = false,
    /** The daemon's last refusal, drawn UNDER the fields, exactly as it was written. */
    refusal: String? = null,
) {
    var name by remember { mutableStateOf("") }
    var cwd by remember(defaultCwd) { mutableStateOf(defaultCwd.orEmpty()) }
    val nameProblem = ProjectRules.nameProblem(name, taken).takeIf { name.isNotBlank() }
    val cwdProblem = ProjectRules.cwdProblem(cwd)
    val ready = !busy && name.isNotBlank() && nameProblem == null && cwdProblem == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") },
                    isError = nameProblem != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                nameProblem?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = cwd,
                    onValueChange = { cwd = it },
                    singleLine = true,
                    label = { Text("Directory") },
                    placeholder = { Text("the host's own working directory") },
                    isError = cwdProblem != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                cwdProblem?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
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
                onClick = { onCreate(ProjectRules.cleanName(name), cwd.trim().takeIf { it.isNotEmpty() }) },
            ) { Text(if (busy) "Starting…" else "Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** What creating a project actually does, said before it is done. */
const val CREATE_PROJECT_BLURB: String =
    "This starts one session — the lead. It sizes the work and proposes the members; " +
        "nothing else is started until you approve them."
