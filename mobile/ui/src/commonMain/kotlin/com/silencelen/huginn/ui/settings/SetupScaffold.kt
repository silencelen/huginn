package com.silencelen.huginn.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.silencelen.huginn.ui.theme.LocalHuginnColors
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import com.silencelen.huginn.settings.SetupFlow
import com.silencelen.huginn.ui.system.ListRow
import com.silencelen.huginn.ui.system.Scale
import com.silencelen.huginn.settings.SetupState
import com.silencelen.huginn.settings.SetupStep
import com.silencelen.huginn.settings.StepStatus

/**
 * The frame the first-run flow is drawn in — one step at a time, with the rest
 * of them visible beside it so nobody wonders how much is left.
 *
 * ⚠ IT HOSTS THE EXISTING CONTROLS RATHER THAN COPYING THEM. Every step body is
 * supplied by the shell and is the SAME composable the matching Settings row
 * draws: the route list, the token field, the device form, the local-AI consent
 * card. That is `docs/ADDING-A-FEATURE.md`'s one-composable rule, and here it
 * buys something specific — a wizard with its own copies of four controls is a
 * second place for the route guard's refusal, the consent card's sizes and the
 * device scopes to drift, and the drift would only ever be visible on a screen
 * people see once.
 *
 * IN `:ui` RATHER THAN IN THE DESKTOP, though only the desktop wires it today.
 * The step ORDER, the words and the button labels are the same question on a
 * phone; the probes are not (a phone cannot spawn `claude --version`), which is
 * exactly the split the shells are for. When the phone grows a pairing flow it
 * hosts this and supplies its own bodies.
 *
 * ⚠ NO LEFT ACCENT BARS. State is marked here the way it is marked everywhere
 * else in this product — a dot and a tint. Same vernacular as the Settings
 * category list and the rail.
 */

/** What a step's dot is SAYING, decided without a colour so it can be asserted. */
enum class SetupTone {
    /** Not attempted. */
    WAITING,

    /** Proven. */
    PROVEN,

    /** Attempted and refused. */
    REFUSED,

    /** Answered "no". Not a failure — deliberately quieter than one. */
    DECLINED,

    /**
     * The check ran and the next move is the reader's (D-15).
     *
     * Its own mark because it is none of the other four: not waiting (something
     * HAS happened), not proven (nothing is serving yet), not refused (the check
     * worked), and not declined (nobody has declined anything).
     */
    WAITING_ON_YOU,

    /** Underway in the background; nobody needs to act. */
    WORKING,
}

/**
 * Every decision this screen makes, as plain functions.
 *
 * There is no compose-ui-test in these modules, so the rules live here and the
 * composable is a thin drawing of them — the same shape as
 * [SettingsScaffoldRules]. The decisions worth asserting are all words and
 * states: a primary button that says "Next" over a step nobody has checked, or
 * a skipped step drawn in the failure colour, are both wrong in a way that
 * never throws and that a screenshot review would have to catch by eye.
 */
object SetupScaffoldRules {

    /** The screen's own title. Deliberately not "Wizard" or "Onboarding". */
    const val TITLE: String = "Set up huginn on this computer"

    // Short and plain (owner, 2026-10-16: the first run read as complicated).
    const val BLURB: String =
        "Connect this computer to huginn, then choose what it does. Skip anything you like; " +
            "Settings can run this again."

    fun title(step: SetupStep): String = when (step) {
        SetupStep.ROUTE -> "huginn's address"
        SetupStep.TOKEN -> "huginn's token"
        SetupStep.CLAUDE -> "Claude on this computer"
        SetupStep.DEVICE -> "Let huginn run work here"
        SetupStep.LOCAL_AI -> "Serve local AI from this computer"
        SetupStep.NOTIFY -> "Notifications"
        SetupStep.AUTOSTART -> "Open at sign-in"
    }

    /** One line under the title: what the step will actually DO, not what it is about. */
    fun blurb(step: SetupStep): String = when (step) {
        SetupStep.ROUTE -> "The address of your huginn host, for example 192.168.1.10 or huginn.example.net."
        SetupStep.TOKEN ->
            "On the huginn host, sudo cat /etc/huginn-appd/token prints it. Connecting hands it to " +
                "huginn core, which keeps it."
        SetupStep.CLAUDE -> "Finds claude on this computer and checks that it starts."
        SetupStep.DEVICE ->
            "Lets huginn run sessions here. Nothing listens on a port: huginn core asks huginn for work."
        SetupStep.LOCAL_AI ->
            "Runs small models here and offers them in huginn's chat menus. The plan is shown in full " +
                "before anything downloads."
        SetupStep.NOTIFY -> "Sends a test notification; tell it whether it appeared."
        SetupStep.AUTOSTART -> "huginn core's tray already starts at sign-in. This opens the window too."
    }

    /** The short name beside the step number in the rail. */
    fun railLabel(step: SetupStep): String = when (step) {
        SetupStep.ROUTE -> "Address"
        SetupStep.TOKEN -> "Token"
        SetupStep.CLAUDE -> "claude"
        SetupStep.DEVICE -> "This computer"
        SetupStep.LOCAL_AI -> "Local AI"
        SetupStep.NOTIFY -> "Notifications"
        SetupStep.AUTOSTART -> "Autostart"
    }

    /** What the primary button does, when the step has not been attempted yet. */
    fun verb(step: SetupStep): String = when (step) {
        SetupStep.ROUTE -> "Check the address"
        SetupStep.TOKEN -> "Connect"
        SetupStep.CLAUDE -> "Find claude"
        SetupStep.DEVICE -> "Enrol this computer"
        SetupStep.LOCAL_AI -> "Show the plan"
        SetupStep.NOTIFY -> "Send a test notification"
        SetupStep.AUTOSTART -> "Turn it on"
    }

    /**
     * The primary button's label.
     *
     * The three states it must tell apart are the reason this is a function: a
     * step nobody has run yet needs a VERB, a step that failed needs a retry
     * that admits it failed, and an answered step needs the way forward — which
     * on the last step is "Done" rather than a "Next" pointing at nothing.
     */
    fun primaryLabel(state: SetupState): String {
        if (state.finished) return CLOSE
        val step = state.current
        val last = step == SetupFlow.STEPS.last()
        return when (state.statusOf(step)) {
            StepStatus.Pending -> verb(step)
            is StepStatus.Failed -> "Try again"
            else -> if (last) "Done" else "Next"
        }
    }

    /**
     * The finish line's heading.
     *
     * ⚠ NOT "SETUP COMPLETE". The flow's whole argument is that a single green
     * sentence over two failures is the thing this product does not write; the
     * heading says the flow is over and [SetupFlow.summary] says how it went.
     */
    const val FINISHED_TITLE: String = "That is everything"

    /** Under the heading: what to do about whatever did not prove itself. */
    const val FINISHED_BLURB: String =
        "Anything that did not prove itself is listed above with the reason. " +
            "Nothing here is final — Settings can run this again, and a re-run changes nothing that already works."

    /**
     * ⚠ ONE WAY OUT, CALLED ONE THING (D-14).
     *
     * The finish bar carried "Close setup" in the footer AND "Close" as the
     * primary, 8 dp apart, both doing the one thing — the same defect as the
     * add-route form's two Cancels, and the same fix: one control, one word. The
     * footer's hatch is what stands down, because on the finish card the primary
     * IS the way out and a flow whose last screen has no obvious exit is the
     * thing [CLOSE] exists to prevent.
     */
    const val CLOSE: String = "Close setup"

    /** Everywhere except the finish card, where [primaryLabel] already says it. */
    fun showsCloseButton(state: SetupState): Boolean = !state.finished

    /**
     * ⚠ EVERY STEP IS SKIPPABLE, AND ONE VERB SAYS SO (D-14).
     *
     * It said "Skip for now" on the address and the token and "Not now" on
     * claude, this computer and local AI — a distinction between duties and
     * offers that is real to whoever wrote it and invisible to whoever reads it,
     * since no step is a duty. One verb, and it is the one the flow RECORDS:
     * the rail says "skipped" and the tally counts "2 skipped", so a button
     * saying "Not now" left the reader to work out that those were the same
     * answer.
     */
    const val SKIP: String = "Skip for now"

    /**
     * A step already answered has nothing to decline; the button goes rather than greys.
     *
     * ⚠ A CHECKED STEP IS STILL DECLINABLE. Its probe has run, but the answer is
     * the reader's and one of the two answers is no — see [StepStatus.Checked].
     */
    fun canSkip(state: SetupState): Boolean = !state.finished &&
        state.statusOf(state.current).let { it == StepStatus.Pending || it is StepStatus.Checked }

    /**
     * ⚠ THE WAY OUT OF A FAILED STEP, and it had to be its own button.
     *
     * Found by walking the real flow: a step that FAILS is resolved, so the
     * decline button goes — and its primary is a retry, so the only two things
     * on screen were "Try again" and "Back". A reader whose daemon is down, or
     * whose machine genuinely has no claude, was trapped on step one of seven
     * with nothing to press. [SetupFlow.canAdvance] said they could move on the
     * whole time; nothing on screen offered it.
     *
     * It is NOT the skip button under another name: skipping OVERWRITES the
     * result, and a failure quietly rewritten as "skipped" loses the one line
     * the finish summary needs — "1 could not be proven" — and with it the
     * reason. So this moves on and leaves the failure exactly where it is.
     */
    fun canMoveOn(state: SetupState): Boolean =
        !state.finished && state.statusOf(state.current) is StepStatus.Failed

    /** Said plainly: the step did not work and you are going past it anyway. */
    const val MOVE_ON: String = "Continue anyway"

    fun toneOf(status: StepStatus): SetupTone = when (status) {
        StepStatus.Pending -> SetupTone.WAITING
        is StepStatus.Passed -> SetupTone.PROVEN
        is StepStatus.Failed -> SetupTone.REFUSED
        is StepStatus.Checked -> SetupTone.WAITING_ON_YOU
        is StepStatus.Working -> SetupTone.WORKING
        is StepStatus.Skipped -> SetupTone.DECLINED
    }

    /**
     * How many lines a rail entry's state line may use (D-16).
     *
     * ⚠ IT WAS ONE, inside `widthIn(max = 320.dp)`, so the notification step's
     * failure read "nothing on this computer can show a notification: …" and
     * stopped at the colon — with no tooltip, no expand, and no way to read the
     * rest except by navigating back to the step. A [StepStatus.Failed]'s reason
     * is the far end's own sentence and is the entire argument for running the
     * probe; a line that ends before it has not been given. Three, the same
     * answer `EditorNoteLines` and the archived row's warning arrived at.
     */
    const val RAIL_REASON_LINES: Int = 3

    /**
     * What a step's state reads as under its name.
     *
     * A Passed step shows WHAT PROVED IT rather than the word "done": the appd
     * version, the claude version, the path. That line is the whole difference
     * between this flow and the four fields it replaces, and dropping it for a
     * tick would give back exactly the screen that could be all green and
     * entirely broken.
     */
    fun stateWords(status: StepStatus): String = when (status) {
        StepStatus.Pending -> "not checked yet"
        is StepStatus.Passed -> status.detail.ifBlank { "checked" }
        is StepStatus.Failed -> status.reason.ifBlank { "did not answer" }
        // ⚠ BOTH HALVES, and in this order (D-15). The check's own finding comes
        // after the state because the state is what the row was getting wrong:
        // this line used to read "not checked yet" beside a printed verdict.
        is StepStatus.Checked ->
            if (status.detail.isBlank()) CHECKED_WORDS else "$CHECKED_WORDS — ${status.detail}"
        is StepStatus.Working -> if (status.detail.isBlank()) WORKING_WORDS else "$WORKING_WORDS — ${status.detail}"
        is StepStatus.Skipped -> if (status.why.isBlank()) "skipped" else "skipped — ${status.why}"
    }

    /** A [StepStatus.Working] step: it carries on whether or not anyone watches. */
    const val WORKING_WORDS: String = "setting up"

    /** What a [StepStatus.Checked] step is, in four words: the machine has answered, you have not. */
    const val CHECKED_WORDS: String = "checked, waiting on you"
}

/**
 * The whole flow.
 *
 * @param state the machine, from `:core`. This composable never advances it —
 *   every verb goes back out to the shell, which owns the probes.
 * @param stepBody the shell's control for one step. The EXISTING settings
 *   control, not a copy of it.
 * @param busy a probe is in flight, so the buttons must not start a second one.
 *   Held by the shell OUTSIDE its composition, so navigating away cannot
 *   cancel an install in flight — the property `LocalServeSection` already has
 *   and the one thing a flow like this must inherit.
 */
@Composable
fun SetupScaffold(
    state: SetupState,
    onPrimary: () -> Unit,
    onSkip: () -> Unit,
    onMoveOn: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onOpenStep: (SetupStep) -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    note: String? = null,
    stepBody: @Composable ColumnScope.(SetupStep) -> Unit,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            // ⚠ CAP BEFORE FILL. The other order compiles, reads as a capped
            // column and spans the whole window; `CapBeforeFillTest` greps for it.
            Modifier.widthIn(max = SETTINGS_READING_WIDTH).fillMaxWidth().padding(20.dp),
        ) {
            Text(SetupScaffoldRules.TITLE, style = MaterialTheme.typography.titleMedium)
            Text(
                SetupScaffoldRules.BLURB,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        HorizontalDivider(color = LocalHuginnColors.current.outlineHairline)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.Start,
            ) {
                Column(
                    Modifier.widthIn(max = SETTINGS_READING_WIDTH).fillMaxWidth().padding(20.dp),
                ) {
                    SetupRail(state, onOpenStep)

                    // ⚠ THE LAST ANSWER USED TO CLOSE THE WINDOW. `SetupFlow`
                    // counts a finish line — "what works, what you chose not to
                    // do, what is actually broken", three numbers because they
                    // mean three different things — and it was drawn only in the
                    // footer, where it updates as you go. So answering the last
                    // step advanced the flow, the controller hid the window, and
                    // the reader's final sight of the tally was the one BEFORE
                    // their own last answer was in it. The rail above still shows
                    // every step and its reason; this is the count, and the way
                    // out is the primary button.
                    if (state.finished) {
                        Text(
                            SetupScaffoldRules.FINISHED_TITLE,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 20.dp),
                        )
                        Text(
                            SetupFlow.summary(state),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(
                            SetupScaffoldRules.FINISHED_BLURB,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        return@Column
                    }

                    val step = state.current
                    val status = state.statusOf(step)
                    Text(
                        "${SetupFlow.number(step)} · ${SetupScaffoldRules.title(step)}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 20.dp),
                    )
                    Text(
                        SetupScaffoldRules.blurb(step),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                    )

                    stepBody(step)

                    // The outcome, in the world's own words. Under the control
                    // rather than over it: it is an answer to what the control
                    // just did, and a line above would be read as an instruction.
                    if (status != StepStatus.Pending || note != null) {
                        Text(
                            note ?: SetupScaffoldRules.stateWords(status),
                            style = MaterialTheme.typography.labelMedium,
                            color = toneColour(SetupScaffoldRules.toneOf(status)),
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = LocalHuginnColors.current.outlineHairline)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!state.finished && SetupFlow.canGoBack(state)) {
                TextButton(onClick = onBack, enabled = !busy) { Text("Back") }
            }
            Text(
                SetupFlow.summary(state),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.weight(1f))
            // Reachable at every step: a flow you cannot leave is one people
            // force-quit rather than finish. ⚠ EXCEPT ON THE FINISH CARD, where
            // the primary button is already this action under a second name —
            // see [SetupScaffoldRules.CLOSE].
            if (SetupScaffoldRules.showsCloseButton(state)) {
                TextButton(onClick = onClose, enabled = !busy) { Text(SetupScaffoldRules.CLOSE) }
            }
            if (SetupScaffoldRules.canSkip(state)) {
                TextButton(onClick = onSkip, enabled = !busy) { Text(SetupScaffoldRules.SKIP) }
            }
            // A failed step's primary is a retry, so without this there is
            // nothing on screen that moves forward — see [canMoveOn].
            if (SetupScaffoldRules.canMoveOn(state)) {
                TextButton(onClick = onMoveOn, enabled = !busy) {
                    Text(SetupScaffoldRules.MOVE_ON)
                }
            }
            Button(onClick = onPrimary, enabled = !busy, modifier = Modifier.padding(start = 8.dp)) {
                Text(if (busy) "Working…" else SetupScaffoldRules.primaryLabel(state))
            }
        }
    }
}

/** Every step at a glance, with the one being answered marked. */
@Composable
private fun SetupRail(state: SetupState, onOpenStep: (SetupStep) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        for (step in SetupFlow.STEPS) {
            val status = state.statusOf(step)
            val here = step == state.current
            val tone = SetupScaffoldRules.toneOf(status)
            // THE HOUSE ROW (UI-AUDIT section 3): the step's state dot, its
            // number and name, and the state words trailing. The step being
            // answered is the house selection (Low tier plus SemiBold), not an
            // accent tint.
            ListRow(
                title = "${SetupFlow.number(step)}  ${SetupScaffoldRules.railLabel(step)}",
                selected = here,
                onClick = { onOpenStep(step) },
                modifier = Modifier.clip(RoundedCornerShape(Scale.radiusCard)),
                leading = {
                    // One dot column: the live 8 dp mark, and the quiet 6 dp one
                    // for a step that is only waiting or was declined.
                    val quiet = tone == SetupTone.WAITING || tone == SetupTone.DECLINED
                    Box(Modifier.size(Scale.dot), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.size(if (quiet) Scale.dotQuiet else Scale.dot)
                                .clip(CircleShape)
                                .background(toneColour(tone)),
                        )
                    }
                },
                trailing = {
                    // ⚠ IT WRAPS (D-16). Right-aligned so the column of state lines
                    // still reads as one edge, and capped in width so a long reason
                    // cannot push the step's own name off the other side.
                    Text(
                        SetupScaffoldRules.stateWords(status),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = SetupScaffoldRules.RAIL_REASON_LINES,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.widthIn(max = 320.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun toneColour(tone: SetupTone): Color = when (tone) {
    SetupTone.WAITING -> MaterialTheme.colorScheme.onSurfaceVariant
    SetupTone.PROVEN -> LocalHuginnColors.current.stateSuccess
    SetupTone.REFUSED -> MaterialTheme.colorScheme.error
    // Quieter than a failure and quieter than a pass: a decline is a settled
    // answer nobody needs to act on.
    SetupTone.DECLINED -> MaterialTheme.colorScheme.outline
    // Something has happened and a person has to act: the brand's needs-you
    // colour, the accent lit up (BRAND.md section 2), the same step the tray and
    // the session rows use. Proven is the success step, not the accent.
    SetupTone.WAITING_ON_YOU -> LocalHuginnColors.current.stateAttention
    // The same colour a working session's row carries.
    SetupTone.WORKING -> LocalHuginnColors.current.stateWorking
}
