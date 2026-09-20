package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.LaunchedEffect
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
import com.silencelen.huginn.data.App
import com.silencelen.huginn.data.AppForm
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.desktop.ui.common.Frame
import com.silencelen.huginn.desktop.ui.common.DeskType
import com.silencelen.huginn.desktop.ui.common.ReadingPane
import com.silencelen.huginn.desktop.ui.common.openInBrowser
import com.silencelen.huginn.ui.AppFormFields
import com.silencelen.huginn.ui.AppRules
import com.silencelen.huginn.ui.REMOVE_APP_BODY
import com.silencelen.huginn.ui.RemoveConfirmDialog
import com.silencelen.huginn.ui.AppsView
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.launch

/**
 * The things huginn makes and hosts itself — armap, the jtyper trainer, the
 * board view, the BTC sim — with an icon, a note, a liveness probe and a verdict
 * about whether this machine can actually open them.
 *
 * SPANS BOTH COLUMNS, like Devices: a row already carries everything there is to
 * say about a URL, so there is no detail half to split off. That is why
 * `Splitter.showsList` deliberately leaves [com.silencelen.huginn.desktop.View.APPS]
 * out while it includes Projects.
 *
 * ⚠ AN APP IS NOT A DEVICE. A device is another machine that enrols, asks for
 * work and builds its own argv under a scope lattice; an app is an address on
 * this host with a name on it. No shared key, no shared lifecycle, no shared
 * security story — so no shared registry, and the resemblance stops at the shape
 * of the row.
 *
 * ⚠⚠ NOTHING HERE APPLIES THE RETROFIT. A failing row discloses the systemd
 * change on the huginn host and the firewall lines on heimdall; both are
 * owner-run, in a netplan session, and the only control the panel has is Copy
 * (decisions 47 and 55). The clipboard is this window's entire contribution.
 */
@Composable
fun AppsPane(store: AppStore) {
    val list by store.apps.collectAsState()
    val addAnswer by store.appAdd.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<App?>(null) }
    var adding by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf(AppForm()) }

    fun copy(text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    // ⚠⚠ A 422 KEEPS THE DIALOG AND EVERY TYPED FIELD (decision 54). The add was
    // refused because the app does not answer where this machine arrives from;
    // the fix lines arrive under the fields and the person presses Add again.
    LaunchedEffect(addAnswer) {
        val answer = addAnswer ?: return@LaunchedEffect
        if (answer.ok) {
            adding = false
            form = AppForm()
        } else {
            form = form.refused(answer)
        }
        store.clearAppAdd()
    }

    if (list.apps.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No apps yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "An app is something huginn makes and hosts itself — a dashboard, a tool, a lab. " +
                    "huginn lists them, says whether each one answered when it was last probed " +
                    "and whether your devices can reach it, and opens them in your browser.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = Frame.prose).padding(top = 8.dp),
            )
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { form = AppForm(); adding = true }) { Text("Add one") }
        }
    } else {
        // ⚠ THE HEADER SPANS THE PANE; THE ROWS KEEP THE READING CAP. The pane
        // spans both columns (see `WindowLayout`), so the header takes that width
        // the way every other pane header does, and the rows stay inside
        // `ReadingPane`'s cap because a 1590px-wide URL row is not more readable
        // than an 800px one.
        Column(Modifier.fillMaxSize()) {
            ListHeader("Apps", list.apps.size, selected = 0) {
                TextButton(onClick = { form = AppForm(); adding = true }) { Text("+ New", style = DeskType.rail) }
            }
            ReadingPane(padding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
                AppsView(
                    apps = list.apps,
                    nowMs = System.currentTimeMillis(),
                    // ⚠ THE HTTP GUARD IS THE ROW'S, NOT OURS TO SKIP.
                    // `openInBrowser` hands the address to the desktop's own
                    // handler, and an app whose URL the rules refuse never becomes
                    // pressable in the first place. A URL that cannot be opened is
                    // COPIED, because a link the reader can still paste beats a
                    // click that silently did nothing.
                    onOpen = { a ->
                        if (AppRules.openable(a) && !openInBrowser(a.url)) copy(a.url)
                    },
                    retrofitApplied = list.retrofitApplied,
                    note = AppRules.retrofitNote(list),
                    onProbe = { a -> scope.launch { store.probeApp(a.id) } },
                    onEdit = { editing = it },
                    onCopyFix = { copy(it) },
                    header = null,
                )
            }
        }
    }

    editing?.let { target ->
        var draft by remember(target.id) { mutableStateOf(target.asForm()) }
        AppEditorDialog(
            title = "Edit ${AppRules.label(target)}",
            confirm = "Save",
            form = draft,
            kinds = AppRules.kindChoices(list),
            onChange = { draft = it },
            onCopyFix = { copy(it) },
            onDismiss = { editing = null },
            onConfirm = {
                editing = null
                scope.launch { store.saveApp(target.id, target.version, draft) }
            },
            onDelete = {
                editing = null
                scope.launch { store.deleteApp(target.id) }
            },
        )
    }

    if (adding) {
        AppEditorDialog(
            title = "Add an app",
            confirm = "Add",
            form = form,
            kinds = AppRules.kindChoices(list),
            onChange = { form = it },
            onCopyFix = { copy(it) },
            onDismiss = { adding = false; form = AppForm() },
            onConfirm = { scope.launch { store.addApp(form) } },
            onDelete = null,
        )
    }
}

/** The row as the editor holds it. */
private fun App.asForm(): AppForm = AppForm(
    name = name,
    url = url,
    kind = kind,
    notes = notes.orEmpty(),
    unit = unit.orEmpty(),
)

/**
 * The desktop's dialog chrome around the shared fields.
 *
 * ⚠ THE FIELDS THEMSELVES ARE `:ui`'s ([AppFormFields]), including what happens
 * to them when the host refuses. The phone draws the same ones inside its own
 * `AlertDialog`; two copies of the 422 rule is how one of them ends up clearing
 * the form.
 */
@Composable
private fun AppEditorDialog(
    title: String,
    confirm: String,
    form: AppForm,
    kinds: List<String>,
    onChange: (AppForm) -> Unit,
    onCopyFix: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    // ⚠ REMOVE ASKS FIRST (D-18, decision 60). It deleted the row on the click
    // with no dialog and no undo, while Kill and Archive both confirm by name.
    var confirmRemove by remember(form.name) { mutableStateOf(false) }
    val fixText = AppRules.fixTextOf(form.name, form.unit, form.addresses, form.fix)
    if (confirmRemove && onDelete != null) {
        RemoveConfirmDialog(
            verb = "Remove app",
            name = form.name.ifBlank { form.url },
            body = REMOVE_APP_BODY,
            onDismiss = { confirmRemove = false },
            onConfirm = { confirmRemove = false; onDelete() },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleSmall) },
        text = {
            AppFormFields(
                form = form,
                kinds = kinds,
                onChange = onChange,
                onCopyFix = onCopyFix,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = form.sendable, onClick = onConfirm) { Text(confirm) }
        },
        dismissButton = {
            Row {
                // ⚠ THE FIX LINES ARE COPYABLE FROM THE CHROME (P-23/D-19), not
                // only from the bottom of a panel that has to be scrolled to.
                // These are shell lines somebody is expected to run on ANOTHER
                // machine; the panel keeps its own Copy for a reader who is
                // already down there, and this one is for everyone else. Same
                // placement the phone chose, so the two dialogs agree.
                if (fixText.isNotBlank()) {
                    TextButton(onClick = { onCopyFix(fixText) }) { Text("Copy fix") }
                }
                if (onDelete != null) {
                    TextButton(onClick = { confirmRemove = true }) { Text("Remove") }
                } else {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        },
    )
}
