package com.silencelen.huginn.desktop.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Empty, loading and nothing-selected — told apart.
 *
 * THE BUG THIS FIXES IS A SENTENCE. The list pane said "No chats yet." during the
 * first fetch and "No chats yet." when the daemon really had none, and on a cold
 * start those are five seconds of a client claiming an answer it does not have.
 * The store already knows the difference (`listsLoaded`); only the copy did not.
 *
 * A spinner is not the whole fix either. Two words centred in 300px of black reads
 * as a failure whichever word it is, so each of these carries ONE orienting
 * sentence: what this pane is for, or what to do next. Plain and calm — no
 * exclamation, no illustration, no call to action dressed as a button.
 */

/** A fetch that has not settled. Distinguishable from empty at a glance. */
@Composable
fun LoadingBlock(what: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(Space.gutter),
        verticalArrangement = Arrangement.spacedBy(Space.unit),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Loading $what…",
                style = DeskType.rowMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Space.unit),
            )
        }
    }
}

/** A fetch that settled on nothing. */
@Composable
fun EmptyBlock(headline: String, sentence: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(Space.gutter),
        verticalArrangement = Arrangement.spacedBy(Space.tight),
    ) {
        Text(headline, style = DeskType.rowTitle, color = MaterialTheme.colorScheme.onSurface)
        Text(
            sentence,
            style = DeskType.rowMeta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------- what an empty pane may say
//
// ⚠ THE COPY DEPENDS ON A PANE THAT IS NOT THIS ONE. Every sentence below used
// to point at the list — "pick one on the left", "every tmux session on the host
// is on the left", "walk the list", "right-click to rename" — and since the notch
// arrived the list can be shut. Directions to a pane that is not there are worse
// than no directions at all: the reader looks left, finds a nav rail, and
// concludes the client is broken rather than that they closed something.
//
// So the choice is a function the render sites consult rather than a literal at
// each call. That is also the only way to assert it, and asserting it is the
// point: nothing crashes, nothing draws wrong, and a screenshot of the stale
// version looks entirely correct unless you already know the pane is shut.

/** The two halves of an empty detail pane that can go stale. */
data class EmptyPaneCopy(val sentence: String, val routes: List<Pair<String, String>>)

/** Chats, with none open. */
fun noChatOpenCopy(listCollapsed: Boolean): EmptyPaneCopy = if (listCollapsed) {
    EmptyPaneCopy(
        "The list is hidden — the notch on the seam brings it back. " +
            "Or start a new one from here: Ask reads and reasons; Act can change things on the host.",
        listOf(
            "Ctrl B" to "show the list",
            "Ctrl N" to "new Ask chat",
            "Ctrl Shift N" to "new Act chat",
            "Ctrl K" to "find one by name",
        ),
    )
} else {
    EmptyPaneCopy(
        "Pick one on the left, or start a new one. Ask reads and reasons; Act can change things on the host.",
        listOf(
            "Ctrl N" to "new Ask chat",
            "Ctrl Shift N" to "new Act chat",
            "Ctrl K" to "find one by name",
        ),
    )
}

/**
 * Sessions, with none open.
 *
 * The collapsed half drops right-click rather than rewording it: the menu hangs
 * off a LIST ROW, so with the list shut there is nothing on screen to open it on.
 * Alt+arrow survives because it really does still work — it walks the sessions
 * whether or not they are drawn — so it is renamed to say what it does rather
 * than where it does it.
 */
fun noSessionOpenCopy(listCollapsed: Boolean): EmptyPaneCopy = if (listCollapsed) {
    EmptyPaneCopy(
        "The list is hidden. Every tmux session on the host is still in it, " +
            "and the notch on the seam brings it back.",
        listOf(
            "Ctrl B" to "show the list",
            "Ctrl K" to "find one by name",
            "Alt ↑ / ↓" to "previous / next session",
        ),
    )
} else {
    EmptyPaneCopy(
        "Every tmux session on the host is on the left. Opening one shows its conversation and its live screen.",
        listOf(
            "Ctrl K" to "find one by name",
            "Alt ↑ / ↓" to "walk the list",
            "Right-click" to "rename, interrupt, end",
        ),
    )
}

/**
 * The detail pane with nothing open. Centred, because unlike the list pane there
 * is no content above it for the text to belong to.
 *
 * @param routes the keyboard ways in. On a desktop this is the honest answer to
 *   "what do I do here", and it is also where the shortcuts get learned — a
 *   cheatsheet behind F1 is a cheatsheet nobody opens.
 */
@Composable
fun NothingOpen(headline: String, sentence: String, routes: List<Pair<String, String>> = emptyList()) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 380.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.tight),
        ) {
            Text(headline, style = MaterialTheme.typography.titleSmall)
            Text(
                sentence,
                style = DeskType.rowMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (routes.isNotEmpty()) {
                Column(
                    Modifier.padding(top = Space.wide),
                    verticalArrangement = Arrangement.spacedBy(Space.hair),
                ) {
                    routes.forEach { (keys, what) ->
                        Row {
                            Text(
                                keys,
                                style = DeskType.rowMeta,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(104.dp),
                            )
                            Text(
                                what,
                                style = DeskType.rowMeta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
