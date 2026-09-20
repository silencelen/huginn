package com.silencelen.huginn.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * FIRST RUN, AS A MACHINE RATHER THAN AS A SCREEN.
 *
 * What this replaces is not a bad wizard, it is the ABSENCE of one. Setting this
 * client up meant finding four unrelated controls in Settings — an address, a
 * token, a path to claude, a device toggle — none of which validated anything.
 * The route was shape-checked and never dialled; the token was saved and never
 * used; the claude path was not checked at all, and its failure arrived at the
 * FIRST JOB as "could not start claude on <host>", which `DeviceRunner` itself
 * calls "the most likely first-run failure by a wide margin". A person could
 * complete every field correctly-looking and have a client that worked for
 * nothing.
 *
 * So every step here PROVES itself against the real world, and the proof is the
 * step's own detail line. What a step cannot prove it says it cannot prove; what
 * the reader declines is [StepStatus.Skipped], which is an ANSWER and not a
 * failure. Nothing in this file is destructive and nothing here is mandatory —
 * "Run setup again" has to be safe to press on a working install, or nobody will
 * press it on a broken one.
 *
 * ⚠ PURE, and in `:core` for the usual reason: the shells disagree about frames
 * and agree about order. The probes themselves belong to a shell (a desktop
 * spawns `claude --version`; a phone cannot), but WHICH STEP COMES NEXT, what a
 * pre-answer may do and when, and what the finish line says are one answer for
 * both — and all three are decisions that fail without throwing. A flow that
 * replays the installer's "yes, enrol this machine" BEFORE the daemon has
 * answered enrols nothing and reports success.
 */
enum class SetupStep {
    /** The address huginn answers on. Proven by `/v1/ping`, which needs no token. */
    ROUTE,

    /** The bearer. Proven by an AUTHENTICATED call — a ping proves a daemon, not a token. */
    TOKEN,

    /** Which `claude` this machine runs. Proven by `claude --version`. */
    CLAUDE,

    /** This machine as a device huginn may run work on. Proven by the enrolment. */
    DEVICE,

    /** Serving local models from here. Proven by the manager's own plan. */
    LOCAL_AI,

    /** That a notification actually lands on this screen. Proven by a reader. */
    NOTIFY,

    /** Starting with the session. Proven by the file it writes. */
    AUTOSTART,
}

/**
 * Where one step stands.
 *
 * FIVE STATES AND NOT THREE. "Skipped" exists so that declining a step is
 * representable as an answer rather than as a failure — the owner's rule for
 * this flow, and the difference between a finish line that reads "3 set up, 4
 * skipped" and one that reads like a broken install. [Failed] carries the
 * reason VERBATIM, because the daemon's own sentence about a refused token is
 * worth more than any wording invented here.
 *
 * ⚠ AND [Checked], WHICH THE FLOW WENT WITHOUT AND LIED FOR (D-15). The local-AI
 * step runs a real capability check whose answer is *not* an outcome: the
 * machine can serve, and whether it will is a click on a consent card that only
 * a person may make. With nowhere to put that, the controller wrote the verdict
 * to a NOTE — which is not part of the step's state — and left the step
 * [Pending], so a screen that had just printed "This machine can serve (class
 * C), 2548 MB to download." also said "not checked yet", counted nothing in the
 * tally, and still offered the check that had just run.
 */
sealed interface StepStatus {

    /** Not attempted yet. The state every step starts in and returns to on retry. */
    data object Pending : StepStatus

    /** Proven. [detail] is what proved it — a version, a name, a path. */
    data class Passed(val detail: String) : StepStatus

    /** Attempted and refused. [reason] is the world's own words, never a paraphrase. */
    data class Failed(val reason: String) : StepStatus

    /**
     * The probe ran; the choice is still the reader's. [detail] is what it found.
     *
     * ⚠ NOT A PASS AND NOT A SKIP, and that is the whole reason it exists.
     * [Passed] would claim this machine serves models it has not downloaded;
     * [Skipped] would record a decision nobody made. It is the honest middle:
     * the machine has said its half.
     */
    data class Checked(val detail: String) : StepStatus

    /**
     * The reader said no, or the step could not be offered. [why] is empty for a
     * plain decline; a gated step that never got its chance says so.
     */
    data class Skipped(val why: String = "") : StepStatus

    /**
     * Answered either way — the only question [SetupFlow.next] asks.
     *
     * ⚠ [Checked] IS NOT RESOLVED. A step waiting on a person has not been
     * answered, whatever the machine now knows about it; counting it as answered
     * would walk the reader past the one question the flow cannot answer for
     * them, and would let [allResolved] call a flow finished over an open choice.
     */
    val resolved: Boolean get() = this !is Pending && this !is Checked
}

/**
 * What the installer answered on the reader's behalf, before this app ever ran.
 *
 * TRI-STATE PER STEP, and that is the whole reason this is a map of nullable
 * booleans rather than a set. `true` means the components page was ticked,
 * `false` means it was deliberately unticked, and ABSENT means the installer
 * said nothing about that step at all — a `.deb`, a build that predates the
 * page, a corrupt file. Collapsing absent into false would turn "the Linux
 * package asks nothing" into "the user declined everything".
 */
data class PreAnswers(val answers: Map<SetupStep, Boolean> = emptyMap()) {

    operator fun get(step: SetupStep): Boolean? = answers[step]

    val isEmpty: Boolean get() = answers.isEmpty()

    /** The steps the reader ticked. These are ARMED, never pre-passed. */
    val wanted: Set<SetupStep> get() = answers.filterValues { it }.keys

    /** The steps the reader unticked. These are skipped when reached. */
    val declined: Set<SetupStep> get() = answers.filterValues { !it }.keys

    companion object {
        val NONE = PreAnswers()
    }
}

/**
 * The flow, entire. Immutable — every verb returns a new one, so a half-applied
 * transition is unrepresentable and the whole thing can be written to disk at
 * any moment.
 */
data class SetupState(
    val current: SetupStep = SetupStep.ROUTE,
    val results: Map<SetupStep, StepStatus> = emptyMap(),
    val pre: PreAnswers = PreAnswers.NONE,
    val finished: Boolean = false,
) {
    fun statusOf(step: SetupStep): StepStatus = results[step] ?: StepStatus.Pending
}

object SetupFlow {

    /** Every step, in the only order they can honestly be attempted. */
    val STEPS: List<SetupStep> = SetupStep.entries.toList()

    /**
     * The two steps that need the daemon before they mean anything.
     *
     * `devices.*` and `devices.local-ai` are gated on the host having answered
     * (`SettingsFacts.desktopProbe`), and for a hard reason rather than a
     * cosmetic one: enrolment is a POST to the daemon and local serving seeds
     * the daemon's own token into a service. Replaying an installer pre-answer
     * for either before [ROUTE] and [TOKEN] have passed does nothing and reports
     * that it worked — which is the exact failure this ordering exists to make
     * unrepresentable.
     */
    val GATED: Set<SetupStep> = setOf(SetupStep.DEVICE, SetupStep.LOCAL_AI)

    /** What a gated step needs first. Both, and both PASSED — skipping is not passing. */
    val GATE: Set<SetupStep> = setOf(SetupStep.ROUTE, SetupStep.TOKEN)

    /** The sentence a gated step carries when it never got its chance. */
    const val GATE_SKIP: String = "huginn has to answer first"

    // ------------------------------------------------------------- questions

    /** Is the daemon half settled — both gate steps proven, not merely answered. */
    fun gateSatisfied(state: SetupState): Boolean =
        GATE.all { state.statusOf(it) is StepStatus.Passed }

    /** May this step be attempted right now? */
    fun available(state: SetupState, step: SetupStep): Boolean =
        step !in GATED || gateSatisfied(state)

    /** The current step is answered, so the flow may move on. */
    fun canAdvance(state: SetupState): Boolean = state.statusOf(state.current).resolved

    /** There is somewhere to go back to. The first step has no behind. */
    fun canGoBack(state: SetupState): Boolean = STEPS.indexOf(state.current) > 0

    /** Every step has an answer. Not the same as every step having PASSED. */
    fun allResolved(state: SetupState): Boolean = STEPS.all { state.statusOf(it).resolved }

    /**
     * The steps the installer asked for that may be ACTED ON right now.
     *
     * ⚠ THIS IS THE ORDERING RULE, expressed as one function so it can be
     * asserted. A ticked "enrol this machine" is a wanted step, and wanting it
     * changes nothing until the daemon has answered — enrolment is a POST and
     * local serving seeds the daemon's token into a service, so replaying either
     * against [StepStatus.Pending] gate steps enrols nothing and says it worked.
     */
    fun replayable(state: SetupState): Set<SetupStep> =
        state.pre.wanted.filterTo(LinkedHashSet()) {
            available(state, it) && !state.statusOf(it).resolved
        }

    // ------------------------------------------------------------ transitions

    fun pass(state: SetupState, detail: String): SetupState =
        record(state, state.current, StepStatus.Passed(detail))

    fun fail(state: SetupState, reason: String): SetupState =
        record(state, state.current, StepStatus.Failed(reason))

    /**
     * The probe answered and the next move is the reader's. See [StepStatus.Checked].
     *
     * Records WITHOUT advancing, unlike [skip]: the step the reader is standing
     * on is the step whose question is still open, and moving them off it is the
     * one thing this state must not do.
     */
    fun checked(state: SetupState, detail: String): SetupState =
        record(state, state.current, StepStatus.Checked(detail))

    /** The reader declined. An ANSWER — the flow moves on and the finish line counts it. */
    fun skip(state: SetupState): SetupState =
        next(record(state, state.current, StepStatus.Skipped()))

    /**
     * Back to Pending, so the step can be attempted again.
     *
     * Clears the RESULT and nothing else: a retry after a failed token must not
     * forget that the route passed, or the gate closes under the reader's feet.
     */
    fun retry(state: SetupState): SetupState =
        state.copy(results = state.results - state.current, finished = false)

    fun back(state: SetupState): SetupState {
        val i = STEPS.indexOf(state.current)
        if (i <= 0) return state
        return state.copy(current = STEPS[i - 1], finished = false)
    }

    /**
     * On to the next step that is worth showing.
     *
     * Two things happen on the way, and both are why this is a function rather
     * than an increment. A gated step whose gate never closed is SKIPPED with a
     * reason rather than offered as a dead form; and a step the installer
     * explicitly unticked is skipped the moment it becomes applicable, which for
     * the gated ones is only ever after the daemon has answered.
     */
    fun next(state: SetupState): SetupState {
        var s = applyPreAnswers(state)
        val from = STEPS.indexOf(s.current)
        for (i in (from + 1)..STEPS.lastIndex) {
            val step = STEPS[i]
            if (s.statusOf(step).resolved) continue
            // ⚠ A CHECKED STEP IS LANDED ON, NOT REASONED ABOUT. Its probe has
            // already run, so neither of the two clauses below can be true of it
            // honestly: an installer's decline is older than the check, and
            // "huginn has to answer first" would relabel a reader's open choice
            // as a decline they never made when the gate re-opens under it (a
            // retry on the address does exactly that).
            if (s.statusOf(step) is StepStatus.Checked) {
                return s.copy(current = step, finished = false)
            }
            // The DECLINE is checked before the gate on purpose: when a reader
            // unticked a component AND the daemon never answered, the truthful
            // reason is the one they gave, not the one the machine would have.
            if (s.pre[step] == false) {
                s = record(s, step, StepStatus.Skipped())
                continue
            }
            if (!available(s, step)) {
                // Not offered rather than offered-and-broken. A form that cannot
                // do anything is worse than a line saying why.
                s = record(s, step, StepStatus.Skipped(GATE_SKIP))
                continue
            }
            return s.copy(current = step, finished = false)
        }
        return s.copy(finished = true)
    }

    /**
     * The installer's DECLINES, folded in. Only the declines.
     *
     * A ticked box is never turned into a pass, and never acted on here. It arms
     * the step; the step still has to prove itself, and whether it MAY be
     * attempted yet is [replayable]'s question — the owner's rule is that the
     * installer pre-answers and installs nothing, so it cannot have proven
     * anything either.
     *
     * The step the reader is standing on is left alone: a question already on
     * screen must not answer itself under them.
     */
    fun applyPreAnswers(state: SetupState): SetupState {
        var s = state
        for (step in STEPS) {
            if (s.pre[step] != false) continue
            if (s.statusOf(step).resolved) continue
            if (step == s.current) continue // the reader is standing on it
            s = s.copy(results = s.results + (step to StepStatus.Skipped()))
        }
        return s
    }

    private fun record(state: SetupState, step: SetupStep, status: StepStatus): SetupState =
        state.copy(results = state.results + (step to status))

    // ----------------------------------------------------------------- words

    /** `1`…`7`, for the house's "1 · 2 · 3" step marker. */
    fun number(step: SetupStep): Int = STEPS.indexOf(step) + 1

    fun total(): Int = STEPS.size

    /**
     * The finish line, counted rather than claimed.
     *
     * Says the three numbers separately because they mean different things to
     * the person reading them: what works, what they chose not to do, and what
     * is actually broken. A single "setup complete" over two failures is the
     * sentence this product does not write.
     */
    fun summary(state: SetupState): String {
        val passed = STEPS.count { state.statusOf(it) is StepStatus.Passed }
        val waiting = STEPS.count { state.statusOf(it) is StepStatus.Checked }
        val skipped = STEPS.count { state.statusOf(it) is StepStatus.Skipped }
        val failed = STEPS.count { state.statusOf(it) is StepStatus.Failed }
        val parts = buildList {
            add("$passed of ${STEPS.size} set up")
            // Second, and before the two settled counts: it is the only clause
            // that names something still to do. A checked step counted as
            // nothing at all is how "4 of 7" came to sit under a printed verdict.
            if (waiting > 0) add("$waiting waiting on you")
            if (skipped > 0) add("$skipped skipped")
            if (failed > 0) add("$failed could not be proven")
        }
        return parts.joinToString(" · ")
    }

    // ------------------------------------------------------------ pre-answers

    /**
     * The installer's answer file, as text.
     *
     * TOLERANT OF EVERYTHING, and it has to be: this is written by an NSIS
     * script in another language, read once, and then deleted. Junk, a newer
     * schema, a half-written file and no file at all all mean the same thing —
     * no pre-answers — because the flow is perfectly usable without them and
     * refusing to launch over a hint would be absurd.
     */
    fun parsePreAnswers(raw: String?): PreAnswers {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return PreAnswers.NONE
        val file = runCatching { JSON.decodeFromString(FirstRunFileV1.serializer(), text) }
            .getOrNull() ?: return PreAnswers.NONE
        val f = file.features ?: return PreAnswers.NONE
        val answers = buildMap {
            f.claudePath?.let { put(SetupStep.CLAUDE, it) }
            f.device?.let { put(SetupStep.DEVICE, it) }
            f.localAi?.let { put(SetupStep.LOCAL_AI, it) }
            f.autostart?.let { put(SetupStep.AUTOSTART, it) }
        }
        return PreAnswers(answers)
    }

    /** A fresh flow carrying whatever the installer said. */
    fun fromPreAnswers(raw: String?): SetupState =
        SetupState(pre = parsePreAnswers(raw))

    /**
     * ⚠ THE KEYS ARE A CONTRACT WITH `huginn-desktop-kt.nsi`, which writes them
     * with `FileWrite` and cannot be refactored by a compiler. Renaming one here
     * silently drops that component's answer — the flow still works, it simply
     * asks a question the reader already answered. `release-desktop.sh` gates on
     * every one of these names appearing in the installer source.
     */
    @Serializable
    internal data class FirstRunFileV1(
        val version: Int = 1,
        val source: String = "",
        val features: Features? = null,
    ) {
        @Serializable
        internal data class Features(
            val claudePath: Boolean? = null,
            val device: Boolean? = null,
            val localAi: Boolean? = null,
            val autostart: Boolean? = null,
        )
    }

    // ------------------------------------------------------------- progress

    /**
     * Progress, as one string a settings store can hold.
     *
     * Persisted because this window can be closed halfway through — it hides to
     * the tray rather than quitting, so "halfway through" is a state that lasts
     * days — and starting a person over at the address they typed on Tuesday is
     * how a flow gets skipped forever. Unreadable input reads as a FRESH flow
     * rather than as a throw, same rule as [parsePreAnswers].
     */
    fun encode(state: SetupState): String = JSON.encodeToString(
        StoredProgress.serializer(),
        StoredProgress(
            current = state.current.name,
            finished = state.finished,
            steps = STEPS.mapNotNull { step ->
                when (val st = state.statusOf(step)) {
                    StepStatus.Pending -> null
                    is StepStatus.Passed -> StoredStep(step.name, "passed", st.detail)
                    is StepStatus.Failed -> StoredStep(step.name, "failed", st.reason)
                    is StepStatus.Checked -> StoredStep(step.name, "checked", st.detail)
                    is StepStatus.Skipped -> StoredStep(step.name, "skipped", st.why)
                }
            },
            pre = state.pre.answers.map { (k, v) -> StoredPre(k.name, v) },
        ),
    )

    fun decode(raw: String?): SetupState? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val stored = runCatching { JSON.decodeFromString(StoredProgress.serializer(), text) }
            .getOrNull() ?: return null
        val results = buildMap {
            for (s in stored.steps) {
                val step = stepOrNull(s.step) ?: continue
                val status = when (s.state) {
                    "passed" -> StepStatus.Passed(s.detail)
                    "failed" -> StepStatus.Failed(s.detail)
                    "checked" -> StepStatus.Checked(s.detail)
                    "skipped" -> StepStatus.Skipped(s.detail)
                    // A state written by a build this one does not know is not a
                    // reason to throw away the rest of the file.
                    else -> continue
                }
                put(step, status)
            }
        }
        val pre = PreAnswers(
            stored.pre.mapNotNull { p -> stepOrNull(p.step)?.let { it to p.on } }.toMap(),
        )
        return SetupState(
            current = stepOrNull(stored.current) ?: SetupStep.ROUTE,
            results = results,
            pre = pre,
            finished = stored.finished,
        )
    }

    private fun stepOrNull(name: String): SetupStep? = STEPS.firstOrNull { it.name == name }

    @Serializable
    private data class StoredProgress(
        val version: Int = 1,
        val current: String = SetupStep.ROUTE.name,
        val finished: Boolean = false,
        val steps: List<StoredStep> = emptyList(),
        val pre: List<StoredPre> = emptyList(),
    )

    @Serializable
    private data class StoredStep(val step: String, val state: String, val detail: String = "")

    @Serializable
    private data class StoredPre(val step: String, val on: Boolean)

    private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}
