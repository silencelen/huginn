package com.silencelen.huginn.desktop.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.desktop.LocalServe
import com.silencelen.huginn.desktop.device.LockProbe
import com.silencelen.huginn.desktop.ui.Muted
import kotlinx.coroutines.launch

/**
 * THIS COMPUTER, as huginn sees it: whether it takes work, what it may do while
 * it does, where a run starts, and whether it serves local models.
 *
 * MOVED HERE FROM `ui/SettingsView.kt`, BODIES UNCHANGED. Nothing about either
 * flow is different — the same toggle, the same three scopes, the same consent
 * card in front of the same `plan`/`enable`/`disable` calls, and the same
 * out-of-composition [LocalServeFlow]. What changed is where they are drawn: the
 * *Devices* drawer, beside a row into the fleet, so the desktop's old split
 * (this machine buried in Settings, everything else at `View.DEVICES`) reads as
 * one place with two halves rather than as two unrelated screens.
 *
 * ⚠ THE SECTION HEADERS STAY. These two are the app's only door to enrolling a
 * machine and to setting local serving up, and each one is a small form with its
 * own three or four controls — not a settings ROW. Rewriting them as rows is a
 * different change with a different risk, and the redesign explicitly re-hosts
 * them rather than reworking them.
 */

@Composable
internal fun DeviceSection(store: AppStore) {
    val settings = store.settings
    val enabled by settings.deviceEnabled.collectAsState()
    val scopeWire by settings.deviceScope.collectAsState()
    val root by settings.deviceRoot.collectAsState()
    val claudePath by settings.deviceClaudePath.collectAsState()
    val status by store.deviceRunner.status.collectAsState()

    FormHeader("Give Huginn access to this PC")

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
        FormHeader("What it may do")
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

/** The shell's own platform, asked once — the manager reports its own. */
private fun isWindowsHost() = System.getProperty("os.name")?.startsWith("Windows") == true

@Composable
internal fun LocalServeSection(store: AppStore) {
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

    FormHeader("Serve local AI from this PC")
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
                // ⚠ TRUE ON THE OS READING IT. This said "two always-on
                // services" on every platform, and on Linux it was false: the
                // manager wrote a systemd USER unit, which stops at logout.
                // Persistence is the thing being consented to here.
                Muted(
                    LocalServe.consentServicesCopy(p.platform, p.elevation),
                    Modifier.padding(start = 4.dp, top = 2.dp),
                    maxLines = 4,
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
        // The answer to "can other huginn clients still use this when I am not
        // here", said on the machine that decides it. Nothing above this line
        // could ever have told you: a user unit reports `active` right up
        // until the session it belongs to ends.
        val persistence = LocalServe.persistenceCopy(sv, isWindowsHost())
        if (persistence.line.isNotBlank()) {
            Muted(persistence.line, Modifier.padding(top = 4.dp, start = 4.dp), maxLines = 3)
        }
        if (sv.adopted) {
            Muted(
                "The model server itself was adopted, not installed here — stopping serving gives it " +
                    "back untouched and only removes the runner.",
                Modifier.padding(start = 4.dp),
                maxLines = 3,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            TextButton(onClick = { refresh() }, enabled = !busy) { Text("Refresh") }
            persistence.action?.let { label ->
                TextButton(
                    onClick = { LocalServeFlow.run { LocalServe.persist { LocalServeFlow.line(it) } } },
                    enabled = !busy,
                ) { Text(label) }
            }
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

/**
 * The heading inside one of these two forms.
 *
 * NOT the deleted `SectionHeader` under another name. That one was the ELEVEN
 * top-level bands a flat Settings screen was divided into, and the categories
 * replaced it — a page has a title now, supplied by the catalog. This is a
 * heading INSIDE a single form, of which there are three in this file, and a
 * form whose halves run together is the other way to make a screen unreadable.
 */
@Composable
private fun FormHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 6.dp),
    )
}
