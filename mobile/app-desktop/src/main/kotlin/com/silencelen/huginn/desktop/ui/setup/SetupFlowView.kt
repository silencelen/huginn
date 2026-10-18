package com.silencelen.huginn.desktop.ui.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.HuginnSettings
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.desktop.LocalServe
import com.silencelen.huginn.desktop.notify.NavTarget
import com.silencelen.huginn.desktop.notify.Notifier
import com.silencelen.huginn.desktop.notify.NotifyRequest
import com.silencelen.huginn.desktop.notify.Notifiers
import com.silencelen.huginn.desktop.setup.Autostart
import com.silencelen.huginn.desktop.setup.ClaudePath
import com.silencelen.huginn.desktop.setup.LocalAiOutcome
import com.silencelen.huginn.desktop.setup.SetupController
import com.silencelen.huginn.desktop.setup.SetupHost
import com.silencelen.huginn.settings.StepStatus
import com.silencelen.huginn.desktop.setup.SetupDrafts
import com.silencelen.huginn.desktop.setup.SetupProbes
import com.silencelen.huginn.desktop.ui.settings.ClaudePathField
import com.silencelen.huginn.desktop.ui.settings.DeviceSection
import com.silencelen.huginn.desktop.ui.settings.LocalServeFlow
import com.silencelen.huginn.desktop.ui.settings.LocalServeSection
import com.silencelen.huginn.desktop.ui.settings.StartupRow
import com.silencelen.huginn.settings.SetupStep
import com.silencelen.huginn.ui.settings.RouteListActions
import com.silencelen.huginn.ui.settings.SettingsFieldRow
import com.silencelen.huginn.ui.settings.SettingsRouteListRow
import com.silencelen.huginn.ui.settings.SettingsToggleRow
import com.silencelen.huginn.ui.settings.SetupScaffold
import com.silencelen.huginn.ui.settings.SetupScaffoldRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The desktop's half of the first-run flow: the probes, and the frame the shared
 * [SetupScaffold] is hung in.
 *
 * ⚠ THE STEP BODIES ARE THE SETTINGS CONTROLS, NOT COPIES OF THEM. The route
 * list, the token field, the claude-path field, the device form, the local-AI
 * consent card and the notification claim below are the SAME composables the
 * matching Settings rows draw, wired to the same state and the same callbacks.
 * That is `docs/dev/kotlin-clients.md`'s rule, and here it stops a specific class
 * of drift: a wizard with its own route field would be a second home for
 * `RouteGuard`'s refusal, and a wizard with its own consent card would be a
 * second place to get a download size wrong — both on a screen people see once,
 * where nobody would notice for months.
 */
@Composable
fun SetupOverlay(store: AppStore) {
    val controller = SetupHost.controller ?: return
    val visible by controller.visible.collectAsState()
    if (!visible) return

    val state by controller.state.collectAsState()
    val busy by controller.busy.collectAsState()
    val note by controller.note.collectAsState()
    val awaiting by controller.awaitingAnswer.collectAsState()

    // OPAQUE, full-window. Not a dialog: this is the whole screen on a fresh
    // install, and a flow floating over an empty Sessions list would invite
    // somebody to dismiss the only thing that can make that list non-empty.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        SetupScaffold(
            state = state,
            onPrimary = { controller.primary() },
            onSkip = { controller.skip() },
            onMoveOn = { controller.moveOn() },
            onBack = { controller.back() },
            onClose = { controller.close() },
            onOpenStep = { controller.goTo(it) },
            busy = busy,
            note = note,
        ) { step ->
            StepBody(store, step)
            if (step == SetupStep.NOTIFY && awaiting) {
                // The only honest test of a notification is a person saying they
                // saw one. Two bounded choices, which is also the house rule for
                // anything a notification itself may ask.
                Row(Modifier.padding(top = 10.dp)) {
                    Button(onClick = { controller.answerNotify(true) }) { Text("Yes, I saw it") }
                    TextButton(
                        onClick = { controller.answerNotify(false) },
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text("No, nothing appeared") }
                }
            }
        }
    }
}

/** One step's control. Every branch here is a row that exists in Settings. */
@Composable
private fun ColumnScope.StepBody(store: AppStore, step: SetupStep) {
    val settings = store.settings
    val scope = rememberCoroutineScope()
    val viaCore = store.viaCore
    if (viaCore != null && (step == SetupStep.ROUTE || step == SetupStep.TOKEN)) {
        // Through huginn core, the address and the token are the core's
        // (docs/design/desktop-via-core.md): there is nothing here to type. The address
        // is read from the core NOW, not the one this run started with: a core
        // paired again since showed one address here and another beside it.
        var url by remember(viaCore) { mutableStateOf(viaCore.url) }
        LaunchedEffect(viaCore) { DesktopSetupProbes.coreUrl(viaCore)?.let { url = it } }
        Text(
            if (step == SetupStep.ROUTE) "huginn core reaches huginn at $url, and keeps its other addresses (Settings > Host)."
            else "huginn core holds the token; this app keeps none of its own.",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    when (step) {
        SetupStep.ROUTE -> {
            // ONE field (owner, 2026-10-16), and the step's own button checks it:
            // a second "Check" beside the field did the same thing (run 3). More
            // addresses - a mesh or LAN one - go in Settings > Host, into huginn
            // core's book. The draft is published for the probe, as the token's is.
            val book by store.routeBook.collectAsState()
            var address by remember { mutableStateOf(book.activeUrl) }
            SetupDrafts.address = address
            SettingsFieldRow(
                id = "host.address",
                title = "Address",
                value = address,
                onValueChange = { address = it; SetupDrafts.address = it },
                summary = "An IP address or a name; https:// and the port are added for you.",
            )
        }

        SetupStep.TOKEN -> {
            var token by remember { mutableStateOf(settings.tokenNow()) }
            // ⚠ THE DRAFT IS PUBLISHED, because the probe runs outside this
            // composition and cannot see a `remember`. Without it "Try the token"
            // read the SAVED token and answered "no token saved yet" at a field
            // the reader had just pasted into — the step's button contradicting
            // the step's own field. `DesktopSetupProbes.token` commits this
            // before it tries anything.
            SetupDrafts.token = token
            SettingsFieldRow(
                id = "host.token",
                title = "Token",
                value = token,
                onValueChange = { token = it; SetupDrafts.token = it },
                secret = true,
                // The step's own text says where it comes from and who keeps it.
            )
        }

        SetupStep.CLAUDE -> ClaudePathField(store)

        // ⚠ D-13. `intro = false` — THE SCAFFOLD ABOVE ALREADY SAID IT. Both of
        // these sections carry their own heading and blurb in Settings, where
        // there is no step title over them; hosted here they printed the step's
        // own subtitle back with four words changed, twice in a row (steps 4 and
        // 5). Exactly the same call the autostart row gets below, for exactly the
        // same reason. `SetupIntroTest` greps for these two arguments.
        SetupStep.DEVICE -> {
            DeviceSection(store, intro = false)
            // The step follows its switch (owner rule: what is shown is re-read).
            // Run 5 turned "Available to huginn" off and the list still read
            // "Waiting for work", counted as set up. Turning it off after it
            // passed is the person's answer; an enrolment that lands passes it.
            val on by settings.deviceEnabled.collectAsState()
            val device by store.coreDevice.status.collectAsState()
            LaunchedEffect(on, device.enrolled, device.note) {
                SetupHost.ifReady { c ->
                    val cur = c.state.value.statusOf(SetupStep.DEVICE)
                    when {
                        on && device.enrolled ->
                            c.settledByCard(SetupStep.DEVICE, StepStatus.Passed(device.note.ifBlank { "enrolled, waiting for work" }))
                        !on && cur is StepStatus.Passed -> c.settledByCard(SetupStep.DEVICE, StepStatus.Skipped())
                    }
                }
            }
        }

        SetupStep.LOCAL_AI -> {
            // Owner, 2026-10-17: the download can take a long while, so the step
            // says plainly that setup can go on without it, or wait here.
            val flow = SetupHost.controller?.state?.collectAsState()?.value
            if (flow?.statusOf(SetupStep.LOCAL_AI) is StepStatus.Working) {
                Text(
                    SetupScaffoldRules.WORKING_CHOICE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
            }
            LocalServeSection(store, intro = false, onServing = { detail ->
                SetupHost.ifReady { it.settledByCard(SetupStep.LOCAL_AI, StepStatus.Passed(detail)) }
            })
        }

        SetupStep.NOTIFY -> {
            val notifyEnabled by settings.notifyEnabled.collectAsState(initial = true)
            SettingsToggleRow(
                id = "notify.claim-route",
                title = "Claim the notification route",
                checked = notifyEnabled,
                onCheckedChange = { scope.launch { settings.setNotifyEnabled(it) } },
                summary = if (notifyEnabled) {
                    "Attention comes here while this window is attended, instead of to your other devices."
                } else {
                    "off — huginn alerts your other devices instead"
                },
            )
        }

        // The row already carries the per-OS sentence as its own summary — where
        // the entry goes and where to turn it off without coming back here — so
        // nothing is added around it. Repeating it under the control was the
        // first thing a walk through the real flow showed.
        SetupStep.AUTOSTART -> StartupRow(store, onChanged = { on ->
            SetupHost.ifReady {
                it.settledByCard(SetupStep.AUTOSTART, if (on) StepStatus.Passed("the window opens at sign-in") else StepStatus.Skipped())
            }
        })
    }
}

/**
 * The probes, as this shell can actually make them.
 *
 * Each one returns the PROOF or the world's own sentence — a version string, an
 * enrolment note, a daemon's refusal. Nothing here paraphrases a far end: the
 * only thing this flow has over the four unvalidated fields it replaces is that
 * it repeats what the far end said.
 */
class DesktopSetupProbes(
    private val store: AppStore,
    private val notifier: () -> Notifier,
) : SetupProbes {

    override suspend fun live(step: SetupStep): StepStatus? = when (step) {
        SetupStep.ROUTE -> store.viaCore?.let { StepStatus.Passed("huginn core reaches huginn at ${coreUrl(it) ?: it.url}") }
        SetupStep.TOKEN -> store.viaCore?.let { StepStatus.Passed("huginn core holds the token") }
        SetupStep.DEVICE -> store.coreDevice.status.value.takeIf { it.enrolled }
            ?.let { StepStatus.Passed(it.note.ifBlank { "enrolled, waiting for work" }) }
        SetupStep.LOCAL_AI -> LocalServe.status().getOrNull()
            ?.takeIf { it.setup == true && it.engine.reachable && it.engine.models.isNotEmpty() }
            ?.let { StepStatus.Passed("serving ${it.engine.models.joinToString(", ")} as \"${it.deviceName ?: "?"}\"") }
        SetupStep.AUTOSTART -> withContext(Dispatchers.IO) {
            if (com.silencelen.huginn.desktop.setup.Autostart.isEnabled()) StepStatus.Passed("the window opens at sign-in") else null
        }
        else -> null
    }

    override suspend fun route(): Result<String> = runCatching {
        store.viaCore?.let { return@runCatching "through huginn core ${it.coreVersion}, at ${coreUrl(it) ?: it.url}" }
        // The field first, as the token step does: what the reader typed is
        // what gets checked, without a second button to commit it.
        val typed = SetupDrafts.address.trim()
        if (typed.isNotEmpty()) store.useAddress(typed)?.let { error(it) }
        val url = store.settings.baseUrlNow()
        check(url.isNotBlank()) { "type huginn's address first" }
        // ⚠⚠ THE PROBE, NOT `ping()`. This step exists to separate "nothing
        // answers at that address" from "something answers and does not like your
        // bearer" — and it used to ask a TOKEN-GATED route, so on every fresh
        // install a correct address failed with the word "unauthorized" and sent
        // the reader after a token the flow had not offered yet. It asks
        // unauthenticated — a bearer is exactly what must not be sent to an
        // address that has not been proven to be huginn yet.
        //
        // ⚠ AND IT REPORTS THREE OUTCOMES, NOT TWO (decision 58). Proved is the
        // ordinary answer from a 3.6+ daemon. `answered` without a proof is a
        // daemon too old for the challenge — a real one, and also exactly what an
        // impostor looks like — so the step PASSES with that said out loud rather
        // than failing a correct address on an older host. The person typed this
        // address themselves, which is the explicit choice; what `proven` gates
        // is the path where NOBODY chose, and that is `RouteResolver`'s
        // auto-switch, which refuses it (see `AppdRoutes.resolve`).
        val probe = store.client.probeDaemon(url)
        check(probe.proven || probe.answered) { "nothing at that address answered as huginn" }
        HuginnClient.probeWords(probe)
    }

    override suspend fun token(): Result<String> = runCatching {
        store.viaCore?.let {
            // Proved the same way as below - an authenticated call - only the
            // core's token is the one it carries.
            val status = store.client.status()
            return@runCatching "huginn core's token, accepted by " +
                (status.appdVersion?.takeIf { v -> v.isNotBlank() }?.let { v -> "appd $v" } ?: "huginn")
        }
        // ⚠ THE FIELD, COMMITTED FIRST. This read `tokenNow()` alone, which is
        // only written by the separate "Save token" button — so on a fresh
        // install, with the field visibly full, the one control the step offers
        // answered "no token saved yet". Committing before the probe also means
        // a token that turns out to work is the one the very next request
        // carries, rather than one the reader has to go back and save again.
        val wanted = SetupDrafts.tokenToUse(SetupDrafts.token, store.settings.tokenNow())
        check(wanted.isNotBlank()) {
            "no token yet — paste the one from the huginn host into the box above"
        }
        // ⚠ INTO THE CORE, NOT THIS APP (owner rule 2026-10-16): the token pairs
        // huginn core, which proves it against the address above and keeps it;
        // this app then goes through the core and holds no token of its own.
        // THE CHECK NOTHING DID used to be here as a direct /v1/status; the
        // core's pairing proves the token, and the status below proves the link.
        store.connectThroughCore(wanted).getOrThrow()
        val status = store.client.status()
        val version = status.appdVersion?.takeIf { it.isNotBlank() }?.let { "appd $it" } ?: "huginn"
        val host = status.host?.takeIf { it.isNotBlank() }?.let { " on $it" }.orEmpty()
        "huginn core paired: accepted by $version$host"
    }

    override suspend fun claude(): Result<String> = withContext(Dispatchers.IO) {
        ClaudePath.detect(override = store.settings.deviceClaudePathNow()).map { found ->
            // Recorded, so the next launch spawns the binary that was PROVEN
            // rather than whatever PATH happens to say then. Left alone when the
            // person typed it themselves — overwriting their answer with the
            // same answer is noise, and with a different one is a surprise.
            if (store.settings.deviceClaudePathNow().isBlank() && found.path != DEFAULT_CLAUDE) {
                store.coreDevice.setClaudePath(found.path)
            }
            // A found binary is not a signed-in one, and the device step passed on
            // this before: the first job then failed with "check auth" on a machine
            // Settings called ready. Claude keeps everything under ~/.claude, so a
            // profile with no such folder has never run it. Said, not failed — the
            // person typed nothing wrong, and signing in is the next thing to do.
            val home = System.getProperty("user.home")
            val everRan = home != null && java.io.File(home, ".claude").isDirectory
            "${found.path} — ${found.version}" +
                if (everRan) "" else " — never run as this account: open a terminal, run `claude` once and sign in, or the first job will fail"
        }
    }

    override suspend fun device(): Result<String> = runCatching {
        store.syncDeviceRunner()
        store.coreDevice.setEnabled(true)
        // Waits for the DAEMON to say so rather than for the toggle to look on.
        // The toggle has always been instant and meaningless; enrolment is a
        // round trip that can fail on a sleeping host or a rejected token.
        val enrolled = withTimeoutOrNull(ENROL_TIMEOUT_MS) {
            store.coreDevice.status.first { it.enrolled }
        }
        checkNotNull(enrolled) { store.coreDevice.status.value.note.ifBlank { "huginn did not confirm the enrolment" } }
        enrolled.note.ifBlank { "enrolled, waiting for work" }
    }

    override suspend fun localAi(): LocalAiOutcome {
        val status = LocalServe.status().getOrNull()
        if (status?.setup == true && status.engine.reachable && status.engine.models.isNotEmpty()) {
            return LocalAiOutcome.Serving(
                "serving ${status.engine.models.joinToString(", ")} as \"${status.deviceName ?: "?"}\"",
            )
        }
        val plan = LocalServe.plan { }.getOrElse { return LocalAiOutcome.Refused(SetupController.reason(it)) }
        plan.refuse?.let { return LocalAiOutcome.Refused("this machine can't serve: $it") }
        // The plan goes onto the card, with its Download button: one way in.
        LocalServeFlow.plan.value = plan
        val mb = plan.needBytes / (1024 * 1024)
        return LocalAiOutcome.Offered("can serve (class ${plan.cls ?: "?"}): read the plan, then Download and turn on ($mb MB)")
    }

    override suspend fun postTestNotification(): Result<String> = runCatching {
        val n = notifier()
        // ⚠⚠ ASKED ONLY WHEN THERE IS SOMETHING TO SEE. With no tray and no
        // libnotify the backend's `post` is a no-op, and the step went straight on
        // to "did it appear?" with a Yes button — a pass recordable for a route
        // that cannot deliver. A step that cannot be attempted fails with the
        // reason instead, and `SetupController` never arms the question on a
        // failure.
        Notifiers.testRefusal(n)?.let { error(it) }
        // A real one, through the real backend, rather than a claim about what
        // the backend supports. `Notifiers.describe` was until now reported ONLY
        // to a stdout that a packaged Windows launcher does not have.
        n.post(
            NotifyRequest(
                key = TEST_TARGET.key,
                title = "Huginn",
                body = "This is the test notification from setup. You can close it.",
                urgent = false,
                target = TEST_TARGET,
            ),
        )
        Notifiers.describe(n)
    }

    override suspend fun clearTestNotification() {
        runCatching { notifier().withdraw(TEST_TARGET.key) }
    }

    override suspend fun autostart(on: Boolean): Result<String> = withContext(Dispatchers.IO) {
        if (on) Autostart.enable() else Autostart.disable()
    }

    companion object {
        /** The address huginn core is paired with right now, or null when it does not answer. */
        suspend fun coreUrl(via: com.silencelen.huginn.desktop.core.CoreBoot.Outcome.ViaCore): String? =
            runCatching { com.silencelen.huginn.desktop.core.CoreClient(via.socket).status().url }
                .getOrNull()?.takeIf { it.isNotBlank() }

        private const val DEFAULT_CLAUDE = "claude"
        private const val ENROL_TIMEOUT_MS = 25_000L

        /**
         * ⚠ A DEAD TARGET, ON PURPOSE AND WITHDRAWN.
         *
         * [NotifyRequest] requires somewhere to navigate, and a test toast has
         * nowhere honest to go. The chat id is one nothing will ever hold, and
         * the notification is taken down the moment the reader answers — on a
         * backend that cannot withdraw it expires on its own, and the worst
         * outcome of a click is the Chats list.
         */
        private val TEST_TARGET = NavTarget.SETUP_TEST
    }
}
