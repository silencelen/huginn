package com.silencelen.huginn.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * THE ONE STATE LANGUAGE. assets/brand/BRAND.md sections 2, 4 and 8: seven words,
 * one colour ladder, one dot. Every dot, state word, rail mark, status line,
 * widget row and round verdict reads this file; nothing else maps a state to a
 * colour. (assets/brand/UI-AUDIT.md found eight private mappings and five idle
 * colours before this existed.)
 *
 * The wire words the daemon speaks (`running`, `attention`, `idle`) map here
 * once; the words a person reads are the brand's: working · needs you ·
 * waiting · shell · ok · error · info.
 *
 * Needs-you is the accent lit up ([HuginnColors.stateAttention]), never the
 * error red: a question is not a failure. Error is for things that failed.
 */
enum class StateKind { WORKING, NEEDS_YOU, WAITING, SHELL, OK, ERROR, INFO }

/** The daemon's state word for a session or a project member, mapped once. */
fun stateKindOf(wire: String?): StateKind = when (wire) {
    "running" -> StateKind.WORKING
    "attention" -> StateKind.NEEDS_YOU
    "idle" -> StateKind.WAITING
    "error", "failed" -> StateKind.ERROR
    else -> StateKind.SHELL
}

/** The word a person reads for a state. */
fun stateWord(kind: StateKind): String = when (kind) {
    StateKind.WORKING -> "working"
    StateKind.NEEDS_YOU -> "needs you"
    StateKind.WAITING -> "waiting"
    StateKind.SHELL -> "shell"
    StateKind.OK -> "ok"
    StateKind.ERROR -> "error"
    StateKind.INFO -> "info"
}

/** The word for a wire state: `stateWord(stateKindOf(wire))`. */
fun stateWord(wire: String?): String = stateWord(stateKindOf(wire))

/** The colour a state takes, from the theme's ladder. Composable because it reads the theme. */
@Composable
fun stateColor(kind: StateKind): Color {
    val c = LocalHuginnColors.current
    return when (kind) {
        StateKind.WORKING -> c.stateWorking
        StateKind.NEEDS_YOU -> c.stateAttention
        StateKind.WAITING -> c.stateIdle
        StateKind.SHELL -> androidx.compose.material3.MaterialTheme.colorScheme.outline
        StateKind.OK -> c.stateSuccess
        StateKind.ERROR -> c.stateError
        StateKind.INFO -> c.stateInfo
    }
}

@Composable
fun stateColor(wire: String?): Color = stateColor(stateKindOf(wire))

/** The live dot: 8 dp. [quiet] is the 6 dp dot for waiting, offline and settled things. */
val STATE_DOT: Dp = 8.dp
val STATE_DOT_QUIET: Dp = 6.dp

/** One dot, in the state's colour. Pass [quiet] for the smaller dot. */
@Composable
fun StateDot(kind: StateKind, modifier: Modifier = Modifier, quiet: Boolean = kind == StateKind.WAITING) {
    Box(modifier.size(if (quiet) STATE_DOT_QUIET else STATE_DOT).clip(CircleShape).background(stateColor(kind)))
}

@Composable
fun StateDot(wire: String?, modifier: Modifier = Modifier) = StateDot(stateKindOf(wire), modifier)
