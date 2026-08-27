package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.silencelen.huginn.desktop.device.LockProbe
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Account
import com.silencelen.huginn.data.AppdRoutes
import com.silencelen.huginn.data.Autoswitch
import com.silencelen.huginn.data.SavedAccount
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.desktop.DesktopSettings
import com.silencelen.huginn.desktop.CliSync
import com.silencelen.huginn.desktop.LocalServe
import com.silencelen.huginn.desktop.diag.AppLog
import com.silencelen.huginn.desktop.update.UpdateState
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI
import kotlin.math.roundToInt

/**
 * Connection, accounts, notifications, updates, diagnostics, and where the file
 * lives.
 *
 * The address field REFUSES anything off the allowlist rather than saving it and
 * failing later: the bearer token follows the base URL on every request, so an
 * arbitrary address is a one-field path to handing a root-equivalent daemon token
 * to a stranger. The refusal is shown, not swallowed — a setting that silently
 * does not take is worse than one that says no.
 *
 * Takes the whole [AppStore] rather than five parameters: accounts need the
 * client, diagnostics need every flow the store owns, and threading each one
 * through the shell would make adding a fact to the report a two-file change.
 */
@Composable
fun SettingsView(store: AppStore) {
    val settings = store.settings
    val scope = rememberCoroutineScope()
    val route by store.route.collectAsState()
    val present by store.presence.present.collectAsState()
    val notifyEnabled by settings.notifyEnabled.collectAsState(initial = true)

    var url by remember(route) { mutableStateOf(route) }
    var token by remember { mutableStateOf(settings.tokenNow()) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleMedium)

        SectionHeader("Server")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier.widthIn(min = 320.dp, max = 460.dp),
            )
            Button(onClick = {
                scope.launch {
                    message = runCatching { settings.selectRoute(url, pinned = true) }
                        .fold({ "saved — route pinned" }, { it.message })
                }
            }) { Text("Save") }
        }
        Muted(
            "known routes: " + AppdRoutes.ALL.joinToString("  ") { "${it.label} ${it.url}" },
            Modifier.padding(top = 4.dp),
        )

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.padding(top = 12.dp).widthIn(min = 320.dp, max = 460.dp),
        )
        Button(
            onClick = { scope.launch { settings.setToken(token); message = "token saved" } },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("Save token") }

        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        AccountsSection(store)

        SectionHeader("Notifications")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = notifyEnabled,
                onCheckedChange = { scope.launch { settings.setNotifyEnabled(it) } },
            )
            Text("Claim the notification route", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
        }
        // The reader has to be able to see WHY the claim is off, because "off"
        // is also what a bug looks like.
        Muted(
            if (!notifyEnabled) "off — huginn falls back to Telegram"
            else if (present) "claiming: this window has been attended recently"
            else "not claiming: window hidden or unattended, so Telegram stays live",
            Modifier.padding(top = 6.dp, start = 4.dp),
            maxLines = 2,
        )

        DeviceSection(store)
        LocalServeSection(store)

        UpdateSection(store)
        DiagnosticsSection(store)

        SectionHeader("This install")
        Muted(settings.path)
        Muted("client id ${settings.clientIdNow()}", Modifier.padding(top = 2.dp))
        Muted(
            if (DesktopSettings.isPackaged()) "packaged build" else "running from source",
            Modifier.padding(top = 2.dp),
        )
        // What the launch-time CLI sync did, when it did anything: the CLI on
        // this machine rides along with the app instead of aging in place.
        CliSync.summary.collectAsState().value?.let {
            Muted(it, Modifier.padding(top = 2.dp))
        }
    }
}

// ------------------------------------------------------------------ accounts

/**
 * Saved Claude logins on the host, and the three-step flow that adds one.
 *
 * The sign-in cannot happen in this process: the daemon runs `claude` on huginn,
 * the browser step is Anthropic's, and the code comes back through the daemon's
 * login session. So all this client does is start it, open the URL, carry the
 * pasted code back — and REPORT THE OUTCOME HONESTLY. Duplicate and mismatch are
 * the two answers a hopeful UI hides, and both matter: a duplicate means the
 * switch you are about to make changes nothing, and a mismatch means the token
 * now saved belongs to somebody other than the account you were adding.
 */
@Composable
private fun AccountsSection(store: AppStore) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf<Account?>(null) }
    var saved by remember { mutableStateOf<List<SavedAccount>>(emptyList()) }
    var autoswitch by remember { mutableStateOf<Autoswitch?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    var loginEmail by remember { mutableStateOf("") }
    var loginUrl by remember { mutableStateOf<String?>(null) }
    var loginCode by remember { mutableStateOf("") }
    var loginNote by remember { mutableStateOf<String?>(null) }
    var forgetting by remember { mutableStateOf<SavedAccount?>(null) }

    suspend fun reload() {
        runCatching { store.client.account() }.onSuccess { current = it }
        // plan=1: the weekly headroom per saved login is the only number that makes
        // the list worth reading — it is what says which one to switch to.
        runCatching { store.client.savedAccounts(withPlan = true) }.onSuccess { saved = it }
        runCatching { store.client.autoswitch() }.onSuccess { autoswitch = it }
        loaded = true
    }

    LaunchedEffect(Unit) { reload() }

    SectionHeader("Accounts")
    Muted("Saved Claude logins on the host. The active one serves every chat and session.", maxLines = 2)

    val who = current
    Text(
        when {
            !loaded -> "Loading…"
            who == null || !who.loggedIn -> "Signed in: nobody"
            else -> "Signed in: ${who.email ?: "unknown"}" + (who.subscriptionType?.let { " · $it" } ?: "")
        },
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
    Muted(autoswitchLine(autoswitch), Modifier.padding(top = 2.dp), maxLines = 2)

    saved.forEach { a ->
        Row(
            Modifier.fillMaxWidth().widthIn(max = 760.dp).padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A dot, not a row tint or an accent bar: "active" is one bit and it
            // reads at a glance in the same vernacular as the nav rail's liveness.
            StateDot(
                if (a.isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant
            )
            Column(Modifier.weight(1f)) {
                Text(
                    buildString {
                        append(a.email ?: a.slug)
                        if (!a.verified) append(" (unconfirmed)")
                        if (a.duplicateOf) append(" (duplicate)")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (a.isActive) FontWeight.SemiBold else FontWeight.Normal,
                )
                val bits = listOfNotNull(
                    a.weeklyPercent?.let { "${it.roundToInt()}% of week" },
                    a.subscriptionType,
                    if (a.isActive) "active" else null,
                )
                if (bits.isNotEmpty()) Muted(bits.joinToString(" · "))
            }
            if (!a.isActive) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            runCatching { store.client.activateAccount(a.slug) }
                                .onFailure { loginNote = it.message ?: "could not switch" }
                            reload()
                            busy = false
                        }
                    },
                ) { Text("Use") }
            }
            TextButton(enabled = !busy, onClick = { forgetting = a }) {
                Text("Forget", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (loaded && saved.isEmpty()) Muted("No saved logins on the host yet.", Modifier.padding(top = 8.dp))

    // The three steps, stated. A sign-in that leaves the app for a browser and
    // comes back through a paste is not self-evident, and the step marker is the
    // difference between "nothing happened" and "it is waiting for you".
    val step = if (loginUrl == null) 1 else 3
    Muted(
        "1 · Start sign-in    2 · Approve in the browser    3 · Paste the code" +
            "        (now: step $step)",
        Modifier.padding(top = 14.dp),
    )

    val pendingUrl = loginUrl
    if (pendingUrl == null) {
        Row(
            Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = loginEmail,
                onValueChange = { loginEmail = it },
                label = { Text("email to add (optional)") },
                singleLine = true,
                modifier = Modifier.widthIn(min = 280.dp, max = 400.dp),
            )
            Button(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        loginNote = "Starting sign-in on the host…"
                        runCatching { store.client.startLogin(loginEmail.trim().ifBlank { null }) }
                            .onSuccess { s ->
                                val link = s.url
                                if (link.isNullOrBlank()) {
                                    loginNote = "The host did not produce a sign-in URL — check the login tmux session."
                                } else {
                                    loginUrl = link
                                    loginNote = if (openInBrowser(link)) {
                                        "Approve the sign-in in the browser, then paste the code here."
                                    } else {
                                        "No browser could be opened here — copy the link, approve it, then paste the code."
                                    }
                                }
                            }
                            .onFailure { loginNote = it.message ?: "could not start sign-in" }
                        busy = false
                    }
                },
            ) { Text("Add login") }
        }
        Muted(
            "Naming the account aims the authorize page at it; leave it blank to use whatever session the browser carries.",
            Modifier.padding(top = 4.dp),
            maxLines = 2,
        )
    } else {
        Row(
            Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = loginCode,
                onValueChange = { loginCode = it },
                label = { Text("paste the code from the browser") },
                singleLine = true,
                modifier = Modifier.widthIn(min = 280.dp, max = 400.dp),
            )
            Button(
                enabled = loginCode.isNotBlank() && !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        loginNote = "Checking…"
                        runCatching { store.client.submitLoginCode(loginCode.trim()) }
                            .onSuccess { s ->
                                loginNote = when {
                                    s.duplicate ->
                                        "Already saved: ${s.email ?: "that account"} — the same login twice, so switching to it changes nothing."
                                    s.mismatch ->
                                        "Signed in as ${s.email ?: "someone else"}, not ${s.intendedEmail ?: "the intended account"}."
                                    s.done -> "Added ${s.email ?: "account"}."
                                    else -> s.message ?: "Still waiting on the host."
                                }
                                if (s.done) {
                                    loginUrl = null
                                    loginCode = ""
                                    loginEmail = ""
                                    reload()
                                }
                            }
                            .onFailure { loginNote = it.message ?: "could not submit the code" }
                        busy = false
                    }
                },
            ) { Text("Submit code") }
            TextButton(onClick = { loginUrl = null; loginCode = ""; loginNote = null }) { Text("Cancel") }
        }
        Row(
            Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Muted(pendingUrl, Modifier.weight(1f))
            val clipboard = LocalClipboardManager.current
            TextButton(onClick = { clipboard.setText(AnnotatedString(pendingUrl)) }) { Text("Copy link") }
            TextButton(onClick = { openInBrowser(pendingUrl) }) { Text("Open again") }
        }
    }

    loginNote?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp).widthIn(max = 760.dp),
        )
    }

    val victim = forgetting
    if (victim != null) {
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget saved login") },
            text = {
                Text(
                    "Remove ${victim.email ?: victim.slug} from the host's saved logins? " +
                        "Signing in again re-adds it."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    forgetting = null
                    scope.launch {
                        runCatching { store.client.forgetAccount(victim.slug) }
                            .onFailure { loginNote = it.message ?: "could not forget that login" }
                        reload()
                    }
                }) { Text("Forget", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
        )
    }
}

private fun autoswitchLine(a: Autoswitch?): String {
    if (a == null) return "autoswitch: unknown"
    if (!a.enabled) return "autoswitch off — a login that runs out stays the active one"
    val last = a.last ?: return "autoswitch on · ${a.accounts} accounts · nothing switched yet"
    return "autoswitch on · ${a.accounts} accounts · last: ${last.fromEmail ?: "?"} (${last.fromPercent}%) → " +
        "${last.toEmail ?: "?"} (${last.toPercent}%)"
}

/**
 * Opens [url] in the user's browser. False when this JVM has no desktop
 * integration — headless, a bare WM, or a sandbox — which is not an error so much
 * as a reason to show the link instead of pretending it opened.
 */
private fun openInBrowser(url: String): Boolean = runCatching {
    if (!Desktop.isDesktopSupported()) return false
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.BROWSE)) return false
    desktop.browse(URI(url))
    true
}.getOrDefault(false)

// -------------------------------------------------------------------- update

/**
 * What the self-updater knows. It downloads and VERIFIES on its own; installing is
 * a button, never a background decision, because these builds are unsigned and an
 * update that runs itself is an update nobody chose.
 */
@Composable
private fun UpdateSection(store: AppStore) {
    val scope = rememberCoroutineScope()
    val state by store.updater.state.collectAsState()

    SectionHeader("Update")
    Text(
        when (val s = state) {
            UpdateState.Idle -> "installed ${store.updater.installedVersion} · not checked yet"
            UpdateState.Checking -> "checking…"
            is UpdateState.UpToDate -> "up to date (${s.version})"
            is UpdateState.Downloading -> "downloading ${s.version}…"
            is UpdateState.Ready -> "${s.version} downloaded and verified"
            is UpdateState.Error -> "update check failed: ${s.message}"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (state is UpdateState.Error) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurface,
    )
    (state as? UpdateState.Downloading)?.fraction?.let { f ->
        LinearProgressIndicator(progress = { f }, modifier = Modifier.padding(top = 6.dp).width(280.dp))
    }
    (state as? UpdateState.Ready)?.let { ready ->
        if (ready.notes.isNotBlank()) Muted(ready.notes, Modifier.padding(top = 4.dp), maxLines = 4)
    }
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
            onClick = { scope.launch { store.updater.check() } },
        ) { Text("Check now") }
        (state as? UpdateState.Ready)?.let { ready ->
            Button(enabled = ready.installable, onClick = { store.updater.install() }) { Text("Install and restart") }
        }
    }
    (state as? UpdateState.Ready)?.takeIf { !it.installable }?.let {
        Muted("downloaded to ${it.file.absolutePath} — install it by hand on this platform", Modifier.padding(top = 4.dp), maxLines = 2)
    }
}

// --------------------------------------------------------------- diagnostics

/**
 * The paste-a-blob button.
 *
 * This exists so the owner can answer "why did it do that" himself. Every field
 * question about the Electron client — did the updater run, why did the stream
 * drop, is it claiming notifications — previously took an SSH session into a
 * laptop nobody can reach. The report carries the app version, the connection, the
 * watch stream, the claim state, the update state, the platform and the recent
 * log; it carries NO token, and that is a property of [com.silencelen.huginn.desktop.diag.Diagnostics.Input]
 * having no field for one rather than of anybody remembering.
 */
@Composable
private fun DiagnosticsSection(store: AppStore) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }

    SectionHeader("Diagnostics")
    Button(onClick = {
        scope.launch {
            // Refreshed FIRST: the status snapshot is only fetched while the
            // Status view is open, so a report copied from here otherwise said
            // "appd version unknown" — which is the one line that says whether
            // the client and the daemon are even the same generation.
            runCatching { store.refreshStatus() }
            val text = AppLog.diagnostics(store)
            // Compose's clipboard, not AWT's. The Electron release that denied every
            // permission also denied clipboard writes and broke every copy in the app
            // silently for a whole release; the carry-over list names it.
            clipboard.setText(AnnotatedString(text))
            note = "copied ${text.lineSequence().count()} lines — paste it into a chat"
        }
    }) { Text("Copy diagnostics") }
    note?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    Muted("log ${AppLog.path ?: "(memory only — the log file could not be opened)"}", Modifier.padding(top = 6.dp))
}

// ------------------------------------------------------------------ plumbing

@Composable
private fun DeviceSection(store: AppStore) {
    val settings = store.settings
    val enabled by settings.deviceEnabled.collectAsState()
    val scopeWire by settings.deviceScope.collectAsState()
    val root by settings.deviceRoot.collectAsState()
    val claudePath by settings.deviceClaudePath.collectAsState()
    val status by store.deviceRunner.status.collectAsState()

    SectionHeader("Give Huginn access to this PC")

    Muted(
        "Lets huginn run work here, in this machine's own context. Nothing listens " +
            "on a port: this app asks huginn for work and posts the results back, so " +
            "it works the same on a laptop away from home.",
        maxLines = 4,
    )

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
        Switch(
            checked = enabled,
            onCheckedChange = { settings.setDeviceEnabled(it); store.syncDeviceRunner() },
        )
        Text(
            if (enabled) "Available to huginn" else "Off",
            Modifier.padding(start = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    // The status is the honest bit: "enrolled, waiting for work" and "claude was
    // not found here" are the two things the owner will actually need to see, and
    // neither is guessable from the toggle.
    Muted(status.note, Modifier.padding(top = 6.dp, start = 4.dp), maxLines = 3)

    if (enabled) {
        SectionHeader("What it may do")
        for ((wire, label, blurb) in SCOPE_CHOICES) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                RadioButton(
                    selected = scopeWire == wire,
                    onClick = { settings.setDeviceScope(wire) },
                )
                Column(Modifier.padding(start = 6.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Muted(blurb, maxLines = 2)
                }
            }
        }

        // Said plainly rather than implied by the word "work": overstating a fence
        // is worse than not having one.
        Muted(
            "Work starts in the folder below. That is where a run begins, not a " +
                "sandbox — a command that is allowed to run can leave any folder.",
            Modifier.padding(top = 8.dp, start = 4.dp),
            maxLines = 3,
        )

        OutlinedTextField(
            value = root,
            onValueChange = { settings.setDeviceRoot(it) },
            label = { Text("Folder for Work runs") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        OutlinedTextField(
            value = claudePath,
            onValueChange = { settings.setDeviceClaudePath(it) },
            label = { Text("Path to claude (leave blank to use PATH)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        Muted(
            if (LockProbe.supported()) {
                if (status.locked) {
                    "This machine reads as locked, so it is read-only until someone unlocks it."
                } else {
                    "While the screen is locked, this machine drops to Look and refuses Act."
                }
            } else {
                "Lock detection is not available on this platform, so this machine " +
                    "reports itself as locked and will only ever Look."
            },
            Modifier.padding(top = 10.dp, start = 4.dp),
            maxLines = 3,
        )
    }
}

private val SCOPE_CHOICES = listOf(
    Triple("look", "Look", "Read files and search. No commands, no changes."),
    Triple("work", "Work", "Read, change and run commands, starting in the folder below."),
    Triple("own", "Own", "The whole machine."),
)

/**
 * The local-AI tier on THIS machine — a door to the same fetched manager the
 * `huginn local` verb drives (one implementation, two doors). The app holds no
 * serving state of its own: the services belong to systemd/WinSW, this section
 * only asks and relays. Serving is never remotely flippable — this section
 * exists only on the machine itself, which is the whole doctrine.
 *
 * Setting up happens HERE too, with the same consent the terminal takes: the
 * read-only `plan` (class, models, disk gate) is shown as a card, and only a
 * human's click on the sized button runs `on --yes`. Never a bare switch —
 * the button says what it will download before it downloads it.
 */
/**
 * The enable/stop flow's state, held OUTSIDE the composition on purpose: an
 * install in flight must neither cancel nor vanish when the person clicks away
 * from Settings. The audit caught exactly that — state in remember{} and work
 * in the section's own scope meant leaving mid-download abandoned the log and
 * CANCELLED the reader while the elevated child kept running unwatched. One
 * flow at a time is all the manager allows anyway.
 */
private object LocalServeFlow {
    val busy = kotlinx.coroutines.flow.MutableStateFlow(false)
    val log = kotlinx.coroutines.flow.MutableStateFlow(listOf<String>())
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    fun line(l: String) { log.value = (log.value + l).takeLast(8) }

    fun run(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        log.value = emptyList()
        scope.launch { try { block() } finally { busy.value = false } }
    }
}

@Composable
private fun LocalServeSection(store: AppStore) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<LocalServe.Status?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy by LocalServeFlow.busy.collectAsState()
    val log by LocalServeFlow.log.collectAsState()
    var plan by remember { mutableStateOf<LocalServe.Plan?>(null) }

    fun refresh() {
        scope.launch {
            if (!LocalServe.managerFile().isFile) {
                // A MACHINE-wide install (ProgramData on Windows) can exist
                // while this user's fetched manager does not — the door must
                // not claim "not set up" over a box that is serving. Fetch
                // quietly and ask properly; a fetch failure falls through to
                // the honest set-up state.
                if (java.io.File(LocalServe.localDataDir(), "local.json").isFile) {
                    LocalServe.fetchManager { }
                } else { status = null; error = null; return@launch }
            }
            LocalServe.status()
                .onSuccess { status = it; error = null }
                .onFailure { status = null; error = it.message }
        }
    }
    LaunchedEffect(Unit) { refresh() }
    // The flow finishing — begun from ANY visit to this screen — is the moment
    // the truth changed; re-ask the manager rather than trusting the last log line.
    LaunchedEffect(busy) { if (!busy) refresh() }

    SectionHeader("Serve local AI from this PC")
    Muted(
        "Runs small AI models here and offers them in huginn's chat model menus. " +
            "Everything serves on this machine only (127.0.0.1), key-gated, and can " +
            "only be set up or stopped from this machine — never remotely.",
        maxLines = 4,
    )

    val s = status
    val engineUp = s?.setup == true && s.engine.reachable && s.engine.models.isNotEmpty()
    if (!engineUp) {
        if (error != null) Muted("The manager did not answer: $error", Modifier.padding(top = 8.dp, start = 4.dp), maxLines = 2)
        // Installed-but-dark gets its diagnosis AND the door back on — the
        // audit caught Stop dead-ending this section with nothing but Refresh.
        // An alive endpoint serving an EMPTY list is its own named state, not
        // "not answering".
        if (s?.setup == true) {
            Muted(
                if (s.engine.reachable) {
                    "Set up as \"${s.deviceName ?: "?"}\" and the engine answers — but it serves NO models, " +
                        "which is not healthy. Turning serving back on reinstalls the pinned models."
                } else {
                    "Set up as \"${s.deviceName ?: "?"}\" but the engine is NOT answering — services: " +
                        "llm ${s.services.llm ?: "?"}, runner ${s.services.runner ?: "?"}."
                },
                Modifier.padding(top = 8.dp, start = 4.dp),
                maxLines = 4,
            )
        }
        val p = plan
        when {
            p == null -> {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    TextButton(
                        onClick = {
                            LocalServeFlow.run {
                                LocalServe.plan { LocalServeFlow.line(it) }
                                    .onSuccess { plan = it; LocalServeFlow.log.value = emptyList() }
                                    .onFailure { LocalServeFlow.line(it.message ?: "could not read this machine") }
                            }
                        },
                        enabled = !busy,
                    ) {
                        Text(
                            when {
                                busy -> "Working…"
                                s?.setup == true -> "Turn serving back on…"
                                else -> "Set up local AI…"
                            },
                        )
                    }
                    if (s?.setup == true) {
                        TextButton(
                            onClick = { LocalServeFlow.run { LocalServe.disable { LocalServeFlow.line(it) } } },
                            enabled = !busy,
                        ) { Text("Stop serving") }
                    }
                }
                Muted(
                    "Checks what this machine can serve and shows the exact plan before anything downloads. " +
                        "Also available from a terminal:  huginn local on",
                    Modifier.padding(start = 4.dp),
                    maxLines = 3,
                )
            }
            p.refuse != null -> {
                Muted("This machine can't serve: ${p.refuse}", Modifier.padding(top = 8.dp, start = 4.dp), maxLines = 4)
                TextButton(onClick = { plan = null }, enabled = !busy) { Text("Back") }
            }
            else -> {
                // The consent card — the same lines the terminal flow prints.
                Muted("class ${p.cls ?: "?"}${p.note?.let { " — $it" } ?: ""} → device \"${p.deviceName}\"", Modifier.padding(top = 8.dp, start = 4.dp), maxLines = 3)
                p.downloads.forEach { d ->
                    Muted("${d.bytes / (1024 * 1024)} MB  ${d.name}", Modifier.padding(start = 12.dp), maxLines = 1)
                }
                p.gate?.let { Muted(it.line, Modifier.padding(start = 4.dp, top = 2.dp), maxLines = 2) }
                Muted(
                    "Installs two always-on services and offers this machine's models to huginn." +
                        if (p.platform == "win32") " Windows will show one administrator (UAC) prompt." else "",
                    Modifier.padding(start = 4.dp, top = 2.dp),
                    maxLines = 3,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    val url = store.settings.baseUrlNow()
                    val token = store.settings.tokenNow()
                    TextButton(
                        onClick = {
                            plan = null
                            LocalServeFlow.run { LocalServe.enable(url, token) { LocalServeFlow.line(it) } }
                        },
                        enabled = !busy && p.gate?.ok == true,
                    ) { Text(if (busy) "Setting up…" else "Download and turn on (${p.needBytes / (1024 * 1024)} MB)") }
                    TextButton(onClick = { plan = null }, enabled = !busy) { Text("Cancel") }
                }
            }
        }
    } else {
        // Non-null by engineUp's definition; bound once so it reads plainly.
        val sv = checkNotNull(s)
        val runnerState = sv.services.runner ?: "?"
        val runnerUp = runnerState == "active" ||
            runnerState.contains("Started", ignoreCase = true) ||
            runnerState.contains("running", ignoreCase = true)
        Muted(
            "Serving ${sv.engine.models.joinToString(", ")} (class ${sv.cls ?: "?"}) as \"${sv.deviceName ?: "?"}\"." +
                // The engine answering is HALF the path: without the runner,
                // huginn queues work to a machine that never asks for it.
                if (!runnerUp) " But the runner service is $runnerState — chats will queue until it runs." else "",
            Modifier.padding(top = 8.dp, start = 4.dp),
            maxLines = 3,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            TextButton(onClick = { refresh() }, enabled = !busy) { Text("Refresh") }
            TextButton(
                onClick = {
                    // Elevated on Windows: stopping a LocalSystem service is
                    // as privileged as installing one.
                    LocalServeFlow.run { LocalServe.disable { LocalServeFlow.line(it) } }
                },
                enabled = !busy,
            ) { Text("Stop serving") }
        }
    }
    // The manager's own words, raw: if it ever lies, the lie is inspectable.
    log.forEach { Muted(it, Modifier.padding(start = 8.dp), maxLines = 1) }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 6.dp),
    )
}
