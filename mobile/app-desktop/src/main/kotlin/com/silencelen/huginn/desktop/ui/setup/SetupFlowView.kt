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
import com.silencelen.huginn.desktop.notify.TargetKind
import com.silencelen.huginn.desktop.setup.Autostart
import com.silencelen.huginn.desktop.setup.ClaudePath
import com.silencelen.huginn.desktop.setup.LocalAiOutcome
import com.silencelen.huginn.desktop.setup.SetupController
import com.silencelen.huginn.desktop.setup.SetupDrafts
import com.silencelen.huginn.desktop.setup.SetupHost
import com.silencelen.huginn.desktop.setup.SetupProbes
import com.silencelen.huginn.desktop.ui.settings.ClaudePathField
import com.silencelen.huginn.desktop.ui.settings.DeviceSection
import com.silencelen.huginn.desktop.ui.settings.LocalServeSection
import com.silencelen.huginn.desktop.ui.settings.StartupRow
import com.silencelen.huginn.settings.SetupStep
import com.silencelen.huginn.ui.settings.RouteListActions
import com.silencelen.huginn.ui.settings.SettingsFieldRow
import com.silencelen.huginn.ui.settings.SettingsRouteListRow
import com.silencelen.huginn.ui.settings.SettingsToggleRow
import com.silencelen.huginn.ui.settings.SetupScaffold
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
 * That is `docs/ADDING-A-FEATURE.md`'s rule, and here it stops a specific class
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
    when (step) {
        SetupStep.ROUTE -> {
            val book by store.routeBook.collectAsState()
            val health by store.routeHealth.collectAsState()
            val resolving by store.resolvingRoute.collectAsState()
            val routeNote by store.routeNote.collectAsState()
            SettingsRouteListRow(
                book = book,
                actions = remember(store) {
                    RouteListActions(
                        activate = { store.activateRoute(it) },
                        rename = { id, name -> store.renameRoute(id, name) },
                        setUrl = { id, url -> store.setRouteUrl(id, url) },
                        move = { id, delta -> store.moveRoute(id, delta) },
                        remove = { store.removeRoute(it) },
                        add = { name, url -> store.addRoute(name, url) },
                        setAutoSwitch = { store.setAutoSwitch(it) },
                        findLive = { store.findLiveRoute() },
                        // One Save, one book operation, and a refusal that stays on the form (edge #64/#81).
                        editBoth = { id, name, url -> store.editRoute(id, name, url) },
                        clearNote = { store.clearRouteNote() },
                    )
                },
                health = health,
                nowMs = System.currentTimeMillis(),
                summary = "The addresses that reach huginn, tried in this order.",
                finding = resolving,
                note = routeNote,
                suggestedUrl = HuginnSettings.ROUTE_URL_PLACEHOLDER,
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
                summary = "The bearer this app sends with every request. " +
                    "\"Try the token\" uses what is in this box.",
                trailing = {
                    Button(onClick = { scope.launch { settings.setToken(token.trim()) } }) { Text("Save token") }
                },
            )
        }

        SetupStep.CLAUDE -> ClaudePathField(store)

        SetupStep.DEVICE -> DeviceSection(store)

        SetupStep.LOCAL_AI -> LocalServeSection(store)

        SetupStep.NOTIFY -> {
            val notifyEnabled by settings.notifyEnabled.collectAsState(initial = true)
            SettingsToggleRow(
                id = "notify.claim-route",
                title = "Claim the notification route",
                checked = notifyEnabled,
                onCheckedChange = { scope.launch { settings.setNotifyEnabled(it) } },
                summary = if (notifyEnabled) {
                    "Attention comes here while this window is attended, instead of to Telegram."
                } else {
                    "off — huginn falls back to Telegram"
                },
            )
        }

        // The row already carries the per-OS sentence as its own summary — where
        // the entry goes and where to turn it off without coming back here — so
        // nothing is added around it. Repeating it under the control was the
        // first thing a walk through the real flow showed.
        SetupStep.AUTOSTART -> StartupRow(store)
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

    override suspend fun route(): Result<String> = runCatching {
        val url = store.settings.baseUrlNow()
        check(url.isNotBlank()) { HuginnClient.NO_ROUTE }
        // ⚠⚠ THE PROBE, NOT `ping()`. This step exists to separate "nothing
        // answers at that address" from "something answers and does not like your
        // bearer" — and it used to ask a TOKEN-GATED route, so on every fresh
        // install a correct address failed with the word "unauthorized" and sent
        // the reader after a token the flow had not offered yet. The 401 plus the
        // `X-Huginn-Appd` header IS the proof of a daemon (`provesDaemon`), and
        // the header carries the version, so nothing is lost by asking without a
        // bearer — and a bearer is exactly what must not be sent to an address
        // that has not been proven to be huginn yet.
        val probe = store.client.probeDaemon(url)
        check(probe.proven) { "nothing at that address answered as huginn" }
        HuginnClient.probeWords(probe)
    }

    override suspend fun token(): Result<String> = runCatching {
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
        if (wanted != store.settings.tokenNow()) store.settings.setToken(wanted)
        // THE CHECK NOTHING DID. The token field's only feedback was the words
        // "token saved", printed whether or not the daemon would ever accept it.
        val status = store.client.status()
        val version = status.appdVersion?.takeIf { it.isNotBlank() }?.let { "appd $it" } ?: "huginn"
        val host = status.host?.takeIf { it.isNotBlank() }?.let { " on $it" }.orEmpty()
        "accepted by $version$host"
    }

    override suspend fun claude(): Result<String> = withContext(Dispatchers.IO) {
        ClaudePath.detect(override = store.settings.deviceClaudePathNow()).map { found ->
            // Recorded, so the next launch spawns the binary that was PROVEN
            // rather than whatever PATH happens to say then. Left alone when the
            // person typed it themselves — overwriting their answer with the
            // same answer is noise, and with a different one is a surprise.
            if (store.settings.deviceClaudePathNow().isBlank() && found.path != DEFAULT_CLAUDE) {
                store.settings.setDeviceClaudePath(found.path)
            }
            "${found.path} — ${found.version}"
        }
    }

    override suspend fun device(): Result<String> = runCatching {
        store.settings.setDeviceEnabled(true)
        store.syncDeviceRunner()
        // Waits for the DAEMON to say so rather than for the toggle to look on.
        // The toggle has always been instant and meaningless; enrolment is a
        // round trip that can fail on a sleeping host or a rejected token.
        val enrolled = withTimeoutOrNull(ENROL_TIMEOUT_MS) {
            store.deviceRunner.status.first { it.enrolled }
        }
        checkNotNull(enrolled) { store.deviceRunner.status.value.note.ifBlank { "huginn did not confirm the enrolment" } }
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
        val mb = plan.needBytes / (1024 * 1024)
        return LocalAiOutcome.Offered(
            "This machine can serve (class ${plan.cls ?: "?"}), ${mb} MB to download. " +
                "Use the card above to read the plan and turn it on.",
        )
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

    private companion object {
        const val DEFAULT_CLAUDE = "claude"
        const val ENROL_TIMEOUT_MS = 25_000L

        /**
         * ⚠ A DEAD TARGET, ON PURPOSE AND WITHDRAWN.
         *
         * [NotifyRequest] requires somewhere to navigate, and a test toast has
         * nowhere honest to go. The chat id is one nothing will ever hold, and
         * the notification is taken down the moment the reader answers — on a
         * backend that cannot withdraw it expires on its own, and the worst
         * outcome of a click is the Chats list.
         */
        val TEST_TARGET = NavTarget(TargetKind.CHATS, "huginn-setup-test")
    }
}
