package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Console
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.desktop.ui.common.Frame
import com.silencelen.huginn.desktop.ui.common.DeskType
import com.silencelen.huginn.desktop.ui.common.ReadingPane
import com.silencelen.huginn.desktop.ui.common.openInBrowser
import com.silencelen.huginn.ui.ConsoleRules
import com.silencelen.huginn.ui.ConsolesView
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.launch

/**
 * The internal pages this host serves — armap, the jtyper trainer, the board
 * view, the BTC sim — with a name, a note and a liveness probe.
 *
 * SPANS BOTH COLUMNS, like Devices: a row already carries everything there is to
 * say about a URL, so there is no detail half to split off. That is why
 * `Splitter.showsList` deliberately leaves [com.silencelen.huginn.desktop.View.CONSOLES]
 * out while it includes Projects.
 *
 * ⚠ A CONSOLE IS NOT A DEVICE. A device is another machine that enrols, asks for
 * work and builds its own argv under a scope lattice; a console is an address on
 * this host with a name on it. No shared key, no shared lifecycle, no shared
 * security story — so no shared registry, and the resemblance stops at the shape
 * of the row.
 *
 * ⚠⚠ NOTHING HERE APPLIES THE REBIND. The approval card lists a systemd change on
 * the huginn host and four firewall lines on heimdall; both are owner-run, in a
 * netplan session, and the only control the card has is Copy (decision 47). The
 * clipboard is this window's entire contribution to that job.
 */
@Composable
fun ConsolesPane(store: AppStore) {
    val consoles by store.consoles.collectAsState()
    val approval by store.consoleApproval.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Console?>(null) }
    var adding by remember { mutableStateOf(false) }

    fun copy(text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    if (consoles.isEmpty() && approval == null) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No consoles yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "A console is an internal page this host serves — a dashboard, a tool, a lab. " +
                    "Huginn lists them, says whether each one answered when it was last probed " +
                    "from the host, and opens them in your browser.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = Frame.prose).padding(top = 8.dp),
            )
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { adding = true }) { Text("Add one") }
        }
    } else {
        // ⚠ THE HEADER SPANS THE PANE; THE ROWS KEEP THE READING CAP. Consoles
        // began flush at the window edge with its first row — no title, no count,
        // no way to add one — while Sessions, Chats, Rounds, Pages and Projects
        // all open with the same header, and its ONLY "Add a console" sat under
        // the rebind approval card, off-screen at first paint. The pane spans both
        // columns (see `WindowLayout`), so the header takes that width the way
        // every other pane header does, and the rows stay inside `ReadingPane`'s
        // cap because a 1590px-wide URL row is not more readable than an 800px one.
        Column(Modifier.fillMaxSize()) {
            ListHeader("Consoles", consoles.size, selected = 0) {
                TextButton(onClick = { adding = true }) { Text("+ New", style = DeskType.rail) }
            }
            ReadingPane(padding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
            ConsolesView(
                consoles = consoles,
                nowMs = System.currentTimeMillis(),
                // ⚠ THE HTTP GUARD IS THE ROW'S, NOT OURS TO SKIP. `openInBrowser`
                // hands the address to the desktop's own handler, and a console
                // whose URL the rules refuse never gets there — the row draws its
                // problem instead. A URL that cannot be opened is COPIED, because
                // a link the reader can still paste beats a click that silently
                // did nothing.
                onOpen = { c ->
                    if (ConsoleRules.openable(c) && !openInBrowser(c.url)) copy(c.url)
                },
                onProbe = { c -> scope.launch { store.probeConsole(c.id) } },
                onEdit = { editing = it },
                onCopyApproval = { copy(it) },
                approval = approval,
                header = null,
            )
            }
        }
    }

    editing?.let { target ->
        ConsoleEditorDialog(
            console = target,
            onDismiss = { editing = null },
            onDelete = {
                editing = null
                scope.launch { store.deleteConsole(target.id) }
            },
            onSave = { name, url, kind, notes ->
                editing = null
                scope.launch { store.saveConsole(target.id, target.version, name, url, kind, notes) }
            },
        )
    }

    if (adding) {
        ConsoleEditorDialog(
            console = null,
            onDismiss = { adding = false },
            onDelete = null,
            onSave = { name, url, kind, notes ->
                adding = false
                scope.launch { store.createConsole(name, url, kind, notes) }
            },
        )
    }
}

/**
 * Add or edit one console.
 *
 * ⚠ THE ADDRESS IS ANSWERED IN THE FIELD, not after the round trip.
 * [ConsoleRules.urlProblem] mirrors the daemon's own rule — http(s) only, no
 * credentials in the authority — so a refusal the host would give arrives while
 * the cursor is still in the box. The daemon re-checks it regardless; this is the
 * courtesy, not the gate.
 */
@Composable
private fun ConsoleEditorDialog(
    console: Console?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (name: String, url: String, kind: String?, notes: String?) -> Unit,
) {
    var name by remember(console?.id) { mutableStateOf(console?.name.orEmpty()) }
    var url by remember(console?.id) { mutableStateOf(console?.url.orEmpty()) }
    var kind by remember(console?.id) { mutableStateOf(console?.kind ?: ConsoleRules.OTHER_KIND) }
    var notes by remember(console?.id) { mutableStateOf(console?.notes.orEmpty()) }
    val urlProblem = ConsoleRules.urlProblem(url)
    val ok = name.isNotBlank() && urlProblem == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (console == null) "Add a console" else "Edit ${console.name}",
                style = MaterialTheme.typography.titleSmall,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                DialogField(value = name, ok = name.isNotBlank(), label = "Name", placeholder = "Architecture map") {
                    name = it
                }
                Spacer(Modifier.height(6.dp))
                DialogField(value = url, ok = urlProblem == null, label = "Address", placeholder = "http://huginn:8088/") {
                    url = it
                }
                urlProblem?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                DialogField(
                    value = kind,
                    ok = true,
                    label = "Kind",
                    placeholder = ConsoleRules.KINDS.joinToString(" · "),
                ) { kind = it }
                Spacer(Modifier.height(6.dp))
                DialogField(value = notes, ok = true, label = "Note", placeholder = "What it is for") { notes = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ok,
                onClick = { onSave(name.trim(), url.trim(), kind.trim().ifBlank { null }, notes.trim().ifBlank { null }) },
            ) { Text(if (console == null) "Add" else "Save") }
        },
        dismissButton = {
            if (onDelete != null) {
                TextButton(onClick = onDelete) { Text("Remove") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
