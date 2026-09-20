package com.silencelen.huginn.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    /**
     * Where the platform's own selection is RECORDED as it changes (P-06/P-07).
     * Null for callers that only want the gate.
     */
    private val native: NativeSelection? = null,
) : TextToolbar {

    override val status: TextToolbarStatus get() = delegate.status

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        // ⚠⚠ P-07 RIDES ON THIS CALL AND NOTHING ELSE. Compose asks for the menu
        // on every selection change and hands over a copy callback that is
        // non-null EXACTLY when a non-empty selection exists — which is the only
        // access an Android client has to the platform's selection at all
        // (`SelectionRegistrar` is internal, and the desktop's
        // `TextContextMenu.TextManager.selectedText` has no common twin). So the
        // callback is what tells the app's bar that its verbs have a narrower
        // scope than the whole row.
        native?.offered(onCopyRequested)
        // Hide rather than simply decline: the container asks for the menu on
        // every selection change, and one of those changes is the press that has
        // just raised the app's bar. Whatever is already floating goes away.
        if (suppressed.value) {
            delegate.hide()
            return
        }
        delegate.showMenu(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
    }

    override fun hide() {
        native?.offered(null)
        delegate.hide()
    }

    /**
     * ⚠⚠ THE X DID NOT CANCEL ANYTHING (P-06). Tapping it took the app's bar
     * down and left the platform selection exactly where it was: the word still
     * highlighted, both amber drag handles on screen (the left one clipped off
     * x=0) and Android's own Copy / Select all popup floating over the
     * conversation. Only tapping empty space cleared it. The bar's whole job is
     * to be the way out of a gesture, and it was the way out of half of one.
     *
     * The popup is this call. The SELECTION itself is not reachable from a
     * `TextToolbar` — `SelectionContainer`'s manager is internal — so the shells
     * clear it the one way the public API allows: by re-keying the container, see
     * `rememberSelectionReset`.
     */
    fun cancelled() {
        native?.offered(null)
        delegate.hide()
    }

    /**
     * The app's bar has just gone up. Take down anything the platform is already
     * floating.
     *
     * ⚠ THE OTHER HALF OF THE ORDER PROBLEM. [showMenu] can only refuse a popup
     * it is asked for; it cannot refuse one it was asked for a moment EARLIER,
     * while the bar was still down — and that is the order a long press produces
     * whenever the container asks first. Declining the next request never comes,
     * because there is no next request: the popup simply stays on screen beside
     * the app's bar, which is the exact pair the walk photographed.
     */
    fun appBarRaised() = delegate.hide()
}

/**
 * The platform's toolbar, gated on whether the app's selection bar is up.
 *
 * ⚠⚠ PROVIDE IT **AROUND** THE SelectionContainer, NOT AMONG ITS CHILDREN.
 * `SelectionContainer` reads `LocalTextToolbar.current` in its own composition
 * scope — `manager.textToolbar = LocalTextToolbar.current`, before it invokes the
 * content lambda — so a value provided inside that lambda reaches nothing at all.
 * Both phone screens did exactly that from 3.1.1 to 3.5.0: the gate was present,
 * correct, tested, and installed where the selection could not see it, which is
 * why the walk still photographed two toolbars for one press.
 *
 * Transcript only, either way. The composer is a text FIELD: its own long-press
 * toolbar is the only one it has, and gating that would take paste away from the
 * one place on this screen that can use it.
 */
@Composable
fun rememberGatedTextToolbar(appBarShowing: Boolean, native: NativeSelection? = null): GatedTextToolbar {
    val platform = LocalTextToolbar.current
    // The flag is read inside showMenu, which is called from outside composition
    // and at a moment this function cannot predict — so it is read through a
    // State that keeps updating rather than captured by value.
    val showing = rememberUpdatedState(appBarShowing)
    val gate = remember(platform, native) { GatedTextToolbar(platform, showing, native) }
    // And the order the gate cannot decline its way out of: the container asking
    // for the menu BEFORE the row's long press has raised the app's bar. Nothing
    // asks again afterwards, so the bar going up is itself the signal.
    LaunchedEffect(appBarShowing) { if (appBarShowing) gate.appBarRaised() }
    return gate
}

/**
 * What the platform's selection machinery is currently offering, and the only
 * handle an Android client has on the selected TEXT (P-07).
 *
 * ⚠⚠ THE HIGHLIGHT AND THE VERB DISAGREED. A long press highlights ONE WORD —
 * `pong-` out of `pong-one` in the walk — while the app's bar was handed the
 * whole row by `TranscriptSelectionHost`, so Quote staged `> pong-one` with one
 * partial word lit. On a long paragraph a person sees a word highlighted and gets
 * the entire block quoted.
 *
 * ⚠ AND READING IT COSTS A CLIPBOARD ROUND TRIP, deliberately. Compose on
 * Android exposes no way to READ a `SelectionContainer`'s text — the registrar and
 * the manager are both internal — and the one operation the toolbar contract
 * offers is "put the selection on the clipboard". So [read] invokes exactly that,
 * reads it back, and puts the previous clipboard contents back. It is ugly and it
 * is the whole API; the alternative is a bar whose buttons act on text the reader
 * did not select.
 */
class NativeSelection {

    private var copy: (() -> Unit)? by mutableStateOf(null)

    /** Whether the platform is holding a non-empty selection right now. */
    val present: Boolean get() = copy != null

    /** Called from the gate on every selection change; null when it collapsed. */
    internal fun offered(onCopyRequested: (() -> Unit)?) { copy = onCopyRequested }

    /**
     * The selected text, or null when there is no platform selection.
     *
     * ⚠ THE SELECTION IS CONSUMED. The toolkit's copy callback releases the
     * selection as it copies — which is what the reader wants after pressing a
     * verb, and which is why this is called once, at the press, and never while
     * merely drawing.
     *
     * ⚠ THE PREVIOUS CLIPBOARD IS NEVER READ, AND SO NEVER PUT BACK. Reading a
     * clip another app wrote is the one thing Android 12+ announces on screen
     * ("Huginn pasted from your clipboard"), once per verb, for a paste that
     * never happened. So a verb leaves the selection on the clipboard — exactly
     * what a long-press Copy would have left there — instead of restoring what
     * was under it. The only clip this reads is the one the toolkit just wrote.
     *
     * @param read the clipboard right after the toolkit's copy.
     * @param write puts text on the clipboard: the cleaned selection, when
     *   [keepOnClipboard] and the marks changed it.
     */
    fun read(read: () -> String?, write: (String) -> Unit, keepOnClipboard: Boolean): String? {
        val take = copy ?: return null
        take()
        val raw = read()
        // The marks `TableGrid` draws into its cells (D-5) become markdown rows
        // here, so a table copied out of a conversation pastes as a table.
        val text = raw?.let { QuickActionRules.copyText(it) }
        if (keepOnClipboard && text != null && text != raw) write(text)
        return text?.takeIf { it.isNotBlank() }
    }
}

/**
 * A key that changes when the transcript's selection must be thrown away
 * (P-06).
 *
 * ⚠⚠ THERE IS NO `selectionManager.clear()` TO CALL. `SelectionContainer`'s
 * public overload takes only a modifier and its content; the overload that
 * carries the selection and an `onSelectionChange` is `internal`, and so is the
 * registrar underneath it. The one thing a caller CAN do is stop being the same
 * container — `key(reset) { SelectionContainer { … } }` disposes the manager and
 * composes a fresh one, which is a selection that no longer exists.
 *
 * Cheap where it is used: the list state is hoisted outside the `key`, so the
 * scroll position survives, and this only fires on a deliberate dismissal.
 *
 * ```
 * val reset = rememberSelectionReset()
 * key(reset.value) { SelectionContainer { … } }
 * // …the bar's X:
 * gate.cancelled(); reset.bump()
 * ```
 */
class SelectionReset {
    var value: Int by mutableStateOf(0)
        private set

    fun bump() { value += 1 }
}

@Composable
fun rememberSelectionReset(): SelectionReset = remember { SelectionReset() }
