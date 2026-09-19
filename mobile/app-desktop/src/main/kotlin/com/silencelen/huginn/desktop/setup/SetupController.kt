package com.silencelen.huginn.desktop.setup

import com.silencelen.huginn.desktop.DesktopSettings
import com.silencelen.huginn.settings.PreAnswers
import com.silencelen.huginn.settings.SetupFlow
import com.silencelen.huginn.settings.SetupState
import com.silencelen.huginn.settings.SetupStep
import com.silencelen.huginn.settings.StepStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What each step PROVES, as calls this shell can actually make.
 *
 * An interface because the probes reach three different places — the daemon
 * client, the device runner, the notifier — none of which the controller should
 * hold, and because the controller's own decisions are then testable without a
 * daemon, a `claude` or a screen.
 *
 * Every one returns the PROOF on success and the world's own words on failure.
 * Nothing here invents a sentence: the value of this flow over the four fields
 * it replaces is that it repeats what the far end said.
 */
interface SetupProbes {

    /** `/v1/ping` on the active route. No token — this proves a daemon, not a bearer. */
    suspend fun route(): Result<String>

    /** An AUTHENTICATED call. The check nothing in this product did before. */
    suspend fun token(): Result<String>

    /** `claude --version`, over a candidate list. See [ClaudePath]. */
    suspend fun claude(): Result<String>

    /** Enrol this machine and wait for the daemon to say it is enrolled. */
    suspend fun device(): Result<String>

    /**
     * What this machine can serve.
     *
     * Three outcomes rather than two, which is why it is not a plain [Result]:
     * already serving is a pass, a refusal is a failure, and a machine that
     * COULD serve but has not been told to is neither — the consent card is
     * right there on the same screen and pressing it is a human's job.
     */
    suspend fun localAi(): LocalAiOutcome

    /** Posts a real notification. Whether it ARRIVED is the reader's to say. */
    suspend fun postTestNotification(): Result<String>

    /**
     * Takes the test notification back down once it has been answered.
     *
     * A test toast has nowhere honest to navigate, so leaving it on screen
     * leaves a button whose only destination is a chat that does not exist. A
     * no-op on a backend that cannot withdraw, where it expires on its own.
     */
    suspend fun clearTestNotification()

    /** Writes or removes the startup entry, and proves the file. */
    suspend fun autostart(on: Boolean): Result<String>
}

sealed interface LocalAiOutcome {
    data class Serving(val detail: String) : LocalAiOutcome
    data class Refused(val reason: String) : LocalAiOutcome

    /** It can, and nobody has said yes yet. [plan] is shown; the step stays open. */
    data class Offered(val plan: String) : LocalAiOutcome
}

/**
 * THE FLOW, HELD OUTSIDE THE COMPOSITION.
 *
 * ⚠ THAT IS THE LOAD-BEARING PROPERTY, and it is inherited rather than invented:
 * `LocalServeSection` learned it the hard way, when state in `remember {}` plus
 * work in the section's own scope meant that clicking away from Settings
 * mid-download abandoned the log and CANCELLED the reader while the elevated
 * child kept running unwatched. A setup flow has the same shape and worse
 * stakes — an enrolment, a service install and a shortcut write all happen
 * inside it — so it lives here, beside the window rather than inside it.
 *
 * Every transition is [SetupFlow]'s; this file only decides WHEN to run a probe
 * and what to do with its answer. The progress is written through to the
 * settings file on every change, because this window hides to the tray rather
 * than quitting and "halfway through" is a state that lasts days.
 */
class SetupController(
    private val settings: DesktopSettings,
    private val probes: SetupProbes,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val _state = MutableStateFlow(restore())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * A line that is not a step RESULT — the local-AI plan, or the question the
     * notification step has to ask. Cleared on every move, because a note left
     * over from the last step reads as a statement about this one.
     */
    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note.asStateFlow()

    /** The notification was posted and only a human can say whether it landed. */
    private val _awaitingAnswer = MutableStateFlow(false)
    val awaitingAnswer: StateFlow<Boolean> = _awaitingAnswer.asStateFlow()

    // ------------------------------------------------------------- opening

    /**
     * Should the window open straight into setup?
     *
     * Pure and static so it can be asserted: the failure it prevents is a flow
     * that either never shows (and the feature does not exist) or shows every
     * launch (and people learn to close it without reading). The route book
     * being EMPTY is the honest signal for "this install has never been
     * connected" — desktop 1.2.0 made a fresh install an empty book plus
     * `NO_ROUTE`, precisely so a first run is distinguishable from an upgrade.
     */
    fun interestedOnLaunch(routeBookEmpty: Boolean): Boolean =
        interested(routeBookEmpty, settings.setupDoneNow(), !_state.value.pre.isEmpty)

    /** First run, or an installer that left answers to replay. Never after a finish. */
    fun open(routeBookEmpty: Boolean) {
        if (interestedOnLaunch(routeBookEmpty)) _visible.value = true
    }

    /**
     * "Run setup again", from Settings.
     *
     * IDEMPOTENT AND NEVER DESTRUCTIVE, which is the owner's requirement and the
     * only thing that makes it safe to press on a working install: the results
     * are cleared so every step is checked afresh, and nothing that has been
     * configured is turned off. A re-run of a healthy machine passes seven steps
     * and changes nothing.
     */
    fun rerun(at: SetupStep = SetupFlow.STEPS.first()) {
        put(SetupState(current = at, pre = _state.value.pre))
        _note.value = null
        _awaitingAnswer.value = false
        _visible.value = true
    }

    /** The dormant local-AI card's door: the same flow, opened at its step. */
    fun openAt(step: SetupStep) {
        put(_state.value.copy(current = step, finished = false))
        _note.value = null
        _awaitingAnswer.value = false
        _visible.value = true
    }

    /** Leaves the flow exactly where it is, so coming back resumes rather than restarts. */
    fun close() {
        _visible.value = false
        _awaitingAnswer.value = false
        // A half-typed bearer is not something to keep in memory once the screen
        // holding it is gone. See [SetupDrafts].
        SetupDrafts.clear()
    }

    /** Pre-answers arriving from the installer, once, at startup. */
    fun adopt(pre: PreAnswers) {
        if (pre.isEmpty) return
        put(SetupFlow.applyPreAnswers(_state.value.copy(pre = pre)))
    }

    // ----------------------------------------------------------- navigation

    fun goTo(step: SetupStep) {
        _note.value = null
        _awaitingAnswer.value = false
        put(_state.value.copy(current = step, finished = false))
    }

    fun back() {
        _note.value = null
        _awaitingAnswer.value = false
        put(SetupFlow.back(_state.value))
    }

    fun skip() {
        if (_busy.value) return
        _note.value = null
        _awaitingAnswer.value = false
        put(SetupFlow.skip(_state.value))
        finishIfDone()
    }

    /**
     * Past a step that would not prove itself, WITHOUT rewriting its result.
     *
     * The failure stays on the record, so the finish line still says "1 could
     * not be proven" and the rail still carries the reason. A reader whose
     * daemon is down must not be held on step one of seven, and must not have
     * that outage quietly relabelled as a choice they made.
     */
    fun moveOn() {
        if (_busy.value) return
        advance()
    }

    /**
     * The one button. What it does depends on where the step stands, which is
     * [com.silencelen.huginn.ui.settings.SetupScaffoldRules.primaryLabel]'s
     * question too — the label and the action read the same state, so they
     * cannot disagree about whether this is a check or a step forward.
     */
    fun primary() {
        if (_busy.value) return
        val s = _state.value
        // The finish card's button is the way out, not a way on.
        if (s.finished) { close(); return }
        when (s.statusOf(s.current)) {
            is StepStatus.Passed, is StepStatus.Skipped -> advance()
            else -> run(s.current)
        }
    }

    /** The notification step's honest answer, from the only witness there is. */
    fun answerNotify(seen: Boolean) {
        _awaitingAnswer.value = false
        _note.value = null
        scope.launch { runCatching { probes.clearTestNotification() } }
        put(
            if (seen) SetupFlow.pass(_state.value, "a test notification reached this screen")
            else SetupFlow.fail(_state.value, NOTIFY_MISSED),
        )
    }

    private fun advance() {
        _note.value = null
        _awaitingAnswer.value = false
        val next = SetupFlow.next(_state.value)
        put(next)
        finishIfDone()
    }

    /**
     * The flow is over — RECORDED, AND STILL ON SCREEN.
     *
     * ⚠ IT USED TO HIDE THE WINDOW HERE, and that is how the finish line got
     * lost. [SetupFlow.summary] is the whole point of the machine — what works,
     * what was declined, what could not be proven — and answering the last step
     * advanced the flow straight into this, so the reader's final sight of the
     * tally was the count taken BEFORE their own last answer. "Not now" on step
     * seven dropped them into the app having never read it.
     *
     * `setupDone` is written immediately regardless: the flow is finished whether
     * or not anybody reads the summary, and a crash between here and the Close
     * button must not raise the whole wizard again on the next launch.
     */
    private fun finishIfDone() {
        if (!_state.value.finished) return
        settings.setSetupDone(true)
        _note.value = null
        _awaitingAnswer.value = false
    }

    // --------------------------------------------------------------- probes

    private fun run(step: SetupStep) {
        // A retry starts from Pending, so a stale failure is never on screen
        // beside a probe that is running.
        put(SetupFlow.retry(_state.value))
        _note.value = null
        _busy.value = true
        scope.launch {
            try {
                when (step) {
                    SetupStep.ROUTE -> record(probes.route())
                    SetupStep.TOKEN -> record(probes.token())
                    SetupStep.CLAUDE -> record(probes.claude())
                    SetupStep.DEVICE -> record(probes.device())
                    SetupStep.LOCAL_AI -> when (val o = probes.localAi()) {
                        is LocalAiOutcome.Serving -> put(SetupFlow.pass(_state.value, o.detail))
                        is LocalAiOutcome.Refused -> put(SetupFlow.fail(_state.value, o.reason))
                        // Neither. The consent card is on this very screen and
                        // pressing it is a human's job — the step stays open
                        // rather than claiming a machine serves when it does not.
                        is LocalAiOutcome.Offered -> _note.value = o.plan
                    }
                    SetupStep.NOTIFY -> probes.postTestNotification().fold(
                        onSuccess = {
                            _note.value = NOTIFY_ASK
                            _awaitingAnswer.value = true
                        },
                        onFailure = { put(SetupFlow.fail(_state.value, reason(it))) },
                    )
                    SetupStep.AUTOSTART -> probes.autostart(true).fold(
                        onSuccess = {
                            settings.setAutostart(true)
                            put(SetupFlow.pass(_state.value, it))
                        },
                        onFailure = { put(SetupFlow.fail(_state.value, reason(it))) },
                    )
                }
            } catch (t: Throwable) {
                // A probe that throws is a step that failed, never a window that
                // dies: this runs on a supervisor scope beside the UI, and an
                // unhandled failure here would leave the flow busy forever.
                put(SetupFlow.fail(_state.value, reason(t)))
            } finally {
                _busy.value = false
            }
        }
    }

    private fun record(result: Result<String>) = put(
        result.fold(
            onSuccess = { SetupFlow.pass(_state.value, it) },
            onFailure = { SetupFlow.fail(_state.value, reason(it)) },
        ),
    )

    // ------------------------------------------------------------- plumbing

    private fun put(next: SetupState) {
        _state.value = next
        settings.setSetupProgress(SetupFlow.encode(next))
    }

    private fun restore(): SetupState =
        SetupFlow.decode(settings.setupProgressNow()) ?: SetupState()

    companion object {

        /**
         * Whether an unattended launch should raise the flow.
         *
         * Static and pure so the rule can be asserted in isolation. The
         * pre-answer clause is what makes a Windows install that ticked three
         * components land on the flow even if the route book somehow is not
         * empty — the answers have nowhere else to go.
         */
        fun interested(routeBookEmpty: Boolean, setupDone: Boolean, hasPreAnswers: Boolean): Boolean =
            !setupDone && (routeBookEmpty || hasPreAnswers)

        const val NOTIFY_ASK: String =
            "A test notification has just been posted. Did it appear on this screen?"

        /**
         * ⚠ NOT "notifications are broken". A notification can be missed for
         * reasons that are nobody's fault — a full screen, a focus assist rule,
         * a compositor that eats them — and the honest report is what was tried,
         * not a verdict on the machine.
         */
        const val NOTIFY_MISSED: String =
            "nothing appeared. huginn will keep using Telegram for anything that needs you; " +
                "Notifications in Settings shows which path this computer is using."

        /** A throwable's own sentence, never its class name if it has one. */
        fun reason(t: Throwable): String =
            t.message?.takeIf { it.isNotBlank() } ?: (t::class.simpleName ?: "did not answer")
    }
}

/**
 * The one instance, reachable from the composition.
 *
 * ⚠ A HOLDER RATHER THAN A CONSTRUCTOR ARGUMENT, and for the same reason the
 * notifier and the single-instance guard are built in `main()`: this object
 * outlives the window. A probe in flight must survive the window being hidden to
 * the tray, and the two doors into the flow — the first-run entry in the shell
 * and "Run setup again" in Settings — are two different compositions that must
 * reach the SAME flow, or a re-run would start a second one beside the first.
 *
 * Set once, by `main()`, before `application {}`. Null in a unit test and in any
 * build that never opened a window, which is why every reader goes through
 * [ifReady] rather than through `!!`.
 */
object SetupHost {

    @Volatile
    var controller: SetupController? = null
        private set

    fun install(c: SetupController) { controller = c }

    /** Runs [block] only if there is a flow to run it on. Never throws. */
    inline fun ifReady(block: (SetupController) -> Unit) { controller?.let(block) }
}
