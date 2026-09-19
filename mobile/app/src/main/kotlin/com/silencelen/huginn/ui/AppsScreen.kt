package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.App
import com.silencelen.huginn.data.AppCreate
import com.silencelen.huginn.data.AppForm

/**
 * The full Apps page: everything huginn hosts itself, whether each one answers,
 * whether THIS phone could reach it, and — on the ones it could not — the exact
 * lines that would fix that.
 *
 * ⚠ THE FIX HAS A COPY CONTROL AND NOTHING ELSE, and that is the whole decision
 * (owner, 47 and 55). The lines rebind a systemd unit on the host and sometimes
 * add firewall lines on a different machine; the daemon has no business running
 * either and this app has less. Nothing on this screen may grow a button that
 * applies them.
 */
@Composable
fun AppsScreen(
    apps: List<App>,
    /** The daemon's own vocabulary for `kind`, for the picker. */
    kinds: List<String>,
    retrofitApplied: Boolean,
    /** The one-line transition note, or null. */
    note: String?,
    nowMs: Long,
    onOpen: (App) -> Unit,
    onProbe: (App) -> Unit,
    onCopyFix: (String) -> Unit,
    /**
     * The daemon's answer to the last add, or null while none is outstanding.
     *
     * ⚠⚠ A 422 IS AN ANSWER AND THE DIALOG STAYS OPEN ON IT (decision 54). The
     * add was refused because the app does not answer where this phone arrives;
     * the person runs the fix lines and presses Add again, and the form still
     * holds what they typed.
     */
    addAnswer: AppCreate?,
    onAdd: (AppForm) -> Unit,
    /** Clears [addAnswer] once this screen has acted on it. */
    onAddSettled: () -> Unit,
    onSave: (id: String, version: Int, form: AppForm) -> Unit,
    onDelete: (App) -> Unit,
) {
    var editing by remember { mutableStateOf<App?>(null) }
    var adding by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf(AppForm()) }

    LaunchedEffect(addAnswer) {
        val answer = addAnswer ?: return@LaunchedEffect
        if (answer.ok) {
            adding = false
            form = AppForm()
        } else {
            // Every typed field survives; the refusal and its fix lines arrive
            // underneath them.
            form = form.refused(answer)
        }
        onAddSettled()
    }

    // ⚠ THE SYSTEM NAV INSET IS THIS SCREEN'S TO PAY. Apps is a pushed
    // destination: there is no `NavigationBar` under it to consume the inset the
    // way there is on Chats, Sessions and Rounds, so without this the "Add app"
    // button is drawn straight over the gesture bar. `ListFabClearanceTest`
    // holds both halves of that rule, because the wrong one is invisible either
    // way. One spelling for it, in Insets.kt.
    Box(Modifier.fillMaxSize().systemNavPadding()) {
        AppsView(
            apps = apps,
            nowMs = nowMs,
            onOpen = onOpen,
            retrofitApplied = retrofitApplied,
            note = note,
            onProbe = onProbe,
            onEdit = { editing = it },
            onCopyFix = onCopyFix,
            // ⚠ THIS DESTINATION IS THE SCROLL. Nothing else on it scrolls — a
            // failing row's fix lines and its only control, Copy fix, were simply
            // off the bottom of the phone.
            scroll = true,
            // The same clearance every other list under a FAB uses, derived from
            // the button rather than written out — see [LIST_FAB_CLEARANCE].
            modifier = Modifier.fillMaxSize().padding(bottom = LIST_FAB_CLEARANCE),
        )
        ExtendedFloatingActionButton(
            onClick = { form = AppForm(); adding = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Add app") },
        )
    }

    if (adding) {
        AppEditDialog(
            title = "Add an app",
            confirm = "Add",
            form = form,
            kinds = kinds,
            onChange = { form = it },
            onCopyFix = onCopyFix,
            onDismiss = { adding = false; form = AppForm() },
            onConfirm = { onAdd(form) },
            onDelete = null,
        )
    }
    editing?.let { a ->
        var draft by remember(a.id) { mutableStateOf(a.asForm()) }
        AppEditDialog(
            title = "Edit ${AppRules.label(a)}",
            confirm = "Save",
            form = draft,
            kinds = kinds,
            onChange = { draft = it },
            onCopyFix = onCopyFix,
            onDismiss = { editing = null },
            onConfirm = { editing = null; onSave(a.id, a.version, draft) },
            onDelete = { editing = null; onDelete(a) },
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
 * The phone's dialog chrome around the shared fields.
 *
 * The fields themselves — including what happens to them when the daemon
 * refuses — are [AppFormFields] in `:ui`, so the desktop cannot drift from this.
 */
@Composable
private fun AppEditDialog(
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
    // ⚠ THE FIX LINES ARE COPYABLE FROM THE CHROME, not only from the bottom of
    // a panel nobody can see. A 422 refusal is unbounded — the daemon decides how
    // many addresses it probed and how many lines the fix is — so on first render
    // the block is cut mid-line at the dialog's bottom edge with the `# on huginn`
    // comment half a row tall and every actual fix line hidden. The panel scrolls
    // and carries its own Copy fix, but a control a person has to discover a
    // scroll to reach is a control that is not there, and these are shell lines
    // they are expected to run on ANOTHER MACHINE. The button chrome is the
    // shell's to decide, so the phone puts it where the dialog's other verbs are.
    //
    // The clipping itself, and the missing scroll affordance, are `AppFormFields`
    // in `:ui` and are left to the shared batch.
    val fixText = AppRules.fixTextOf(form.name, form.unit, form.addresses, form.fix)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            AppFormFields(
                form = form,
                kinds = kinds,
                onChange = onChange,
                onCopyFix = onCopyFix,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = form.sendable) { Text(confirm) }
        },
        dismissButton = {
            Row {
                if (fixText.isNotBlank()) {
                    TextButton(onClick = { onCopyFix(fixText) }) { Text("Copy fix") }
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Remove") }
                } else {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        },
    )
}

// ------------------------------------------------------- what the shell offers

/**
 * Whether the three ways into Apps are drawn, decided once.
 *
 * Same rule and same reason as [ProjectEntries]: a daemon with neither
 * `/v1/apps` nor `/v1/consoles` answers 404 at both, and a card on Status that
 * leads to an error is worse than a Status screen that never mentions apps.
 */
data class AppEntries(
    /** The card on the Status screen (owner decision 48). */
    val statusCard: Boolean,
    /** The full page it opens. */
    val fullPage: Boolean,
    /** The row in Settings, so search can find the page at all. */
    val settingsRow: Boolean,
)

fun appEntries(available: Boolean?): AppEntries {
    val on = available == true
    return AppEntries(statusCard = on, fullPage = on, settingsRow = on)
}
