package com.silencelen.huginn.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/**
 * TWO TOOLBARS FOR ONE GESTURE, and the app's was underneath.
 *
 * A long press on a transcript row does two things at once on the phone. The row
 * reports it to [TranscriptSelectionHost], which raises the app's own
 * [SelectionActionBar] above the composer with the WHOLE row's text and the verbs
 * the host actually configured — Explain, Execute, Quote, Ask in a new chat,
 * Copy. And the [androidx.compose.foundation.text.selection.SelectionContainer]
 * the rows live inside sees the same press, selects the one WORD under the thumb,
 * and floats Android's own Copy / Select all popup over the middle of the
 * conversation.
 *
 * The walk on the owner's Fold caught exactly that: a grey platform popup sitting
 * on top of the sentence it had just half-selected, with the app's five-verb bar
 * drawn below it and the word "permissions" highlighted — one press, two answers,
 * neither of them the one the design specified.
 *
 * The platform toolbar is the one that loses. It offers Copy, which the app's bar
 * already carries, and Select all, which selects the whole scrolling transcript;
 * the app's bar offers the four verbs that are the reason the gesture exists at
 * all. So while the app's bar is up, this stands in front of the platform's and
 * refuses to show it.
 *
 * ⚠ NOT a no-op toolbar. Everything except `showMenu` still goes to the real one:
 * `hide()` must reach it or a popup raised a moment before the bar came up would
 * be orphaned on screen with nothing able to dismiss it, and `status` is read by
 * the selection machinery to decide whether a menu is already showing. Only the
 * SHOWING is gated, and only while the app is showing something better.
 *
 * ⚠ The word-level selection itself is left alone. Dragging the handles to pull a
 * phrase out of a message is a real thing to want and the platform is good at it;
 * what it may not do is claim the press that already has an answer. The app's bar
 * was handed the row's text explicitly at the press — see
 * [TranscriptSelectionHost] — so which words the container happens to have
 * highlighted never decides what Quote or Explain acts on.
 */
class GatedTextToolbar(
    private val delegate: TextToolbar,
    private val suppressed: State<Boolean>,
) : TextToolbar {

    override val status: TextToolbarStatus get() = delegate.status

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        // Hide rather than simply decline: the container asks for the menu on
        // every selection change, and one of those changes is the press that has
        // just raised the app's bar. Whatever is already floating goes away.
        if (suppressed.value) {
            delegate.hide()
            return
        }
        delegate.showMenu(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
    }

    override fun hide() = delegate.hide()
}

/**
 * The platform's toolbar, gated on whether the app's selection bar is up.
 *
 * Provide it for [LocalTextToolbar] around the transcript ONLY. The composer is a
 * text FIELD: its own long-press toolbar is the only one it has, and gating that
 * would take paste away from the one place on this screen that can use it.
 */
@Composable
fun rememberGatedTextToolbar(appBarShowing: Boolean): TextToolbar {
    val platform = LocalTextToolbar.current
    // The flag is read inside showMenu, which is called from outside composition
    // and at a moment this function cannot predict — so it is read through a
    // State that keeps updating rather than captured by value.
    val showing = rememberUpdatedState(appBarShowing)
    return remember(platform) { GatedTextToolbar(platform, showing) }
}
