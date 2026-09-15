package com.silencelen.huginn.desktop.ui.common

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.background
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.foundation.text.TextContextMenuArea
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLocalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.rememberCursorPositionProvider
import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.QuickActions
import com.silencelen.huginn.data.Session
import com.silencelen.huginn.ui.QuickActionRules
import com.silencelen.huginn.ui.SelectionAction

/**
 * Right-click menus.
 *
 * THE VERB SURFACE A DESKTOP ALREADY HAS. Before this, a chat could not be
 * renamed or deleted from this client at all, and the fix was never going to be a
 * row of icons: a hover control in the list and a header button in the detail pane
 * are two controls doing one verb, which is the house rule this violates most
 * often. Right-click is one place for all of them, it costs no pixels, and it is
 * where a mouse looks first.
 *
 * BUILT ON THE TOOLKIT'S OWN [ContextMenuArea] rather than a hand-rolled popup:
 * it already owns the parts that are tedious and easy to get subtly wrong — the
 * secondary-click detector, dismissal on outside click and on window blur, and the
 * cursor-anchored position provider that keeps a menu opened near the bottom edge
 * on screen. What is ours is the LOOK ([HuginnMenuLook]) and the CONTENT (the
 * builders at the foot of this file, which are pure and asserted).
 *
 * A menu row is [HuginnMenuItem]: it SUBCLASSES the toolkit's `ContextMenuItem`
 * rather than replacing it, so `ContextMenuArea` still accepts it while the look
 * can ask the one extra question the base class cannot answer — does this row
 * destroy something.
 */
class HuginnMenuItem(
    label: String,
    val destructive: Boolean = false,
    onClick: () -> Unit,
) : ContextMenuItem(label, onClick)

/**
 * The menu, drawn in the app's palette instead of the toolkit's default grey.
 *
 * A context menu is the one surface a user is certain came from the operating
 * system unless it says otherwise, and the default representation genuinely does
 * look like a different program — light chrome over a near-black window.
 */
private class HuginnMenuLook(
    private val background: androidx.compose.ui.graphics.Color,
    private val ink: androidx.compose.ui.graphics.Color,
    private val destructiveInk: androidx.compose.ui.graphics.Color,
    private val hover: androidx.compose.ui.graphics.Color,
    private val outline: androidx.compose.ui.graphics.Color,
) : ContextMenuRepresentation {

    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
        val status = state.status
        if (status !is ContextMenuState.Status.Open) return
        fun close() { state.status = ContextMenuState.Status.Closed }

        Popup(
            popupPositionProvider = rememberCursorPositionProvider(offset = DpOffset(2.dp, 2.dp)),
            onDismissRequest = ::close,
            properties = PopupProperties(focusable = true),
        ) {
            Surface(
                color = background,
                shape = RoundedCornerShape(6.dp),
                tonalElevation = 8.dp,
                modifier = Modifier
                    .widthIn(min = 168.dp, max = 320.dp)
                    .border(1.dp, outline, RoundedCornerShape(6.dp))
                    // Escape closes. The Popup is focusable so the key lands here
                    // rather than in the shell behind it, which would otherwise
                    // navigate the app out from under an open menu.
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                            close(); true
                        } else {
                            false
                        }
                    },
            ) {
                // IntrinsicSize.Max, and it is what stops a four-item menu from
                // opening 320dp wide. The rows `fillMaxWidth` so the hover
                // highlight spans the menu; inside a Surface whose only other
                // constraint is a `widthIn` MAX, that fill resolves to the max and
                // the menu is as wide as it is allowed to be rather than as wide
                // as its longest label. Measuring the column first makes the fill
                // relative to the content again.
                Column(
                    Modifier.width(IntrinsicSize.Max).padding(vertical = Space.tight),
                ) {
                    items().forEach { item ->
                        MenuRow(item, ::close)
                    }
                }
            }
        }
    }

    @Composable
    private fun MenuRow(item: ContextMenuItem, close: () -> Unit) {
        val destructive = (item as? HuginnMenuItem)?.destructive == true
        // `hoverable` + the interaction source rather than the desktop-only
        // `onPointerEvent`: it is stable API, it is the same source the click
        // already needs, and hover is what a pointer expects a menu to answer.
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        Box(
            Modifier.fillMaxWidth()
                .padding(horizontal = Space.tight)
                .clip(RoundedCornerShape(4.dp))
                .background(if (hovered) hover else androidx.compose.ui.graphics.Color.Transparent)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null) { close(); item.onClick() }
                .padding(horizontal = Space.wide, vertical = Space.tight),
        ) {
            Text(
                item.label,
                style = MaterialTheme.typography.labelMedium,
                color = if (destructive) destructiveInk else ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Installs the app's menu look for everything below. Applied once, at the shell's
 * root, so a menu opened from a list row and a menu opened from selected text in
 * the transcript are the same object.
 */
@Composable
fun WithHuginnMenus(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val look = remember(scheme) {
        HuginnMenuLook(
            background = scheme.surfaceContainerHigh,
            ink = scheme.onSurface,
            destructiveInk = scheme.error,
            hover = scheme.surfaceContainerHighest,
            outline = scheme.outline,
        )
    }
    CompositionLocalProvider(LocalContextMenuRepresentation provides look, content = content)
}

/** Wraps a row so secondary-click opens [items] over it. */
@Composable
fun RowMenu(items: () -> List<ContextMenuItem>, content: @Composable () -> Unit) {
    ContextMenuArea(items = items, content = content)
}

// ------------------------------------------------------------------ content
//
// PURE, so the menus can be asserted. What a menu offers is a decision — a chat
// that is running offers "Stop", one that is not does not; a multi-row selection
// offers one delete rather than N — and a decision expressed only inside a
// composable lambda is a decision nobody can test.

/** The handlers a chat row's menu needs. Named so a call site cannot swap two. */
class ChatVerbs(
    val open: (String) -> Unit,
    val rename: (Chat) -> Unit,
    val stop: (String) -> Unit,
    val copyId: (String) -> Unit,
    val delete: (List<String>) -> Unit,
)

/**
 * @param selection every chat id currently selected. When the clicked row is one
 *   of several, the destructive verb addresses the whole selection and SAYS so —
 *   a menu that reads "Delete" and removes four things is the worst possible
 *   version of this feature.
 */
fun chatMenu(chat: Chat, selection: Set<String>, verbs: ChatVerbs): List<ContextMenuItem> {
    val multi = selection.size > 1 && chat.id in selection
    if (multi) {
        val ids = selection.toList()
        return listOf(
            HuginnMenuItem("Delete ${selection.size} chats", destructive = true) { verbs.delete(ids) },
        )
    }
    val items = mutableListOf<ContextMenuItem>(
        HuginnMenuItem("Open") { verbs.open(chat.id) },
        HuginnMenuItem("Rename…") { verbs.rename(chat) },
    )
    // Only while there is something to stop. A verb that is always present and
    // usually inert teaches people to ignore the menu.
    if (chat.running) items += HuginnMenuItem("Stop this run") { verbs.stop(chat.id) }
    items += HuginnMenuItem("Copy chat id") { verbs.copyId(chat.id) }
    items += HuginnMenuItem("Delete", destructive = true) { verbs.delete(listOf(chat.id)) }
    return items
}

/** The handlers a session row's menu needs. */
class SessionVerbs(
    val open: (String) -> Unit,
    val rename: (Session) -> Unit,
    val interrupt: (String) -> Unit,
    val copyName: (String) -> Unit,
    /** Compact the session's context (types "/compact"). Not destructive. */
    val compact: (String) -> Unit,
    /** Ask Claude to wrap up (and, host willing, end on settle). Not destructive. */
    val softEnd: (List<String>) -> Unit,
    val kill: (List<String>) -> Unit,
)

fun sessionMenu(session: Session, selection: Set<String>, verbs: SessionVerbs): List<ContextMenuItem> {
    val multi = selection.size > 1 && session.name in selection
    if (multi) {
        val names = selection.toList()
        return listOf(
            HuginnMenuItem("Wind down ${selection.size} sessions") { verbs.softEnd(names) },
            HuginnMenuItem("End ${selection.size} sessions", destructive = true) { verbs.kill(names) },
        )
    }
    return listOf(
        HuginnMenuItem("Open") { verbs.open(session.name) },
        HuginnMenuItem("Rename…") { verbs.rename(session) },
        // Esc into the pane. Named for the key so the menu teaches the keyboard
        // rather than competing with it.
        HuginnMenuItem("Interrupt (Esc)") { verbs.interrupt(session.name) },
        HuginnMenuItem("Copy session name") { verbs.copyName(session.name) },
        // The context manager: types "/compact" so the owner can reclaim context
        // without opening the pane. Host guards a plain shell / waiting question.
        HuginnMenuItem("Compact context") { verbs.compact(session.name) },
        // The graceful sibling of "End session": sends the wrap-up phrase, and the
        // host (auto-end on) ends the session once it settles. Red stays on the
        // kill — this one only sends a message.
        HuginnMenuItem("Wind down…") { verbs.softEnd(listOf(session.name)) },
        HuginnMenuItem("End session", destructive = true) { verbs.kill(listOf(session.name)) },
    )
}

/** Labels only — what a test asserts, and what a screenshot should show. */
fun labelsOf(items: List<ContextMenuItem>): List<String> = items.map { it.label }

// -------------------------------------------------------- selected text
//
// The verb surface a transcript already has: exactly one, the toolkit's Copy.
// These four join it — Explain, Execute, Quote, Ask in a new chat — and every one
// of them STAGES TEXT IN A COMPOSER. None of them sends. That is what makes
// right-click safe to put over a conversation at all: the worst outcome of a
// mis-click is text to delete.

/**
 * The handlers the selection menu needs. Named, so a call site cannot swap two —
 * and swapping Explain for Execute here would be a mis-fire nobody could see in
 * the menu, only in what appeared in the box.
 */
class SelectionVerbs(
    val explain: (String) -> Unit,
    val execute: (String) -> Unit,
    val quote: (String) -> Unit,
    val askInNewChat: (String) -> Unit,
)

/**
 * What to offer over selected transcript text.
 *
 * Pure, and the decision it carries is [QuickActionRules.offered]'s — shared with
 * the phone's action bar so the two clients never disagree about which verbs a
 * selection deserves. An EMPTY list is the right answer more often than it looks:
 * these items are PREPENDED to the toolkit's own, so returning nothing leaves
 * Copy exactly as it was.
 *
 * ⚠⚠ [selection] IS A FUNCTION, AND THAT IS THE WHOLE POINT. It is called TWICE
 * and the two calls answer different questions: once now, to decide which verbs
 * this selection deserves, and again INSIDE the click, to read the text that is
 * actually staged. A captured `String` was the 1.1.0 bug — see the header of
 * [WithTranscriptSelectionMenu] for why the list a menu was built from can be
 * older than the selection the reader is looking at.
 *
 * @param actions the host's wording, or null on a daemon older than 3.0.1 — which
 *   narrows this to Quote, the one verb whose text this client writes itself.
 */
fun selectionMenu(
    selection: () -> String,
    actions: QuickActions?,
    verbs: SelectionVerbs,
): List<ContextMenuItem> = QuickActionRules.offered(selection(), actions).map { action ->
    // Not destructive, any of them: red would be claiming a verb does something
    // that cannot be undone, and staging text in a composer is undone by Delete.
    HuginnMenuItem(action.label) {
        // READ AT CLICK TIME, never at build time. Every verb goes through this
        // one line, so Explain, Execute and Ask in a new chat cannot drift from
        // Quote — they were all staging the same stale string.
        val text = selection()
        when (action) {
            SelectionAction.EXPLAIN -> verbs.explain(text)
            SelectionAction.EXECUTE -> verbs.execute(text)
            SelectionAction.QUOTE -> verbs.quote(text)
            SelectionAction.ASK_IN_NEW_CHAT -> verbs.askInNewChat(text)
        }
    }
}

/**
 * The same menu for a selection that cannot change under it — a fixed string.
 *
 * For callers (and assertions) that hold the text outright rather than a live
 * manager. It is a convenience, NOT the path the transcript takes: anything
 * reading a real selection must pass the function so the click can re-read it.
 */
fun selectionMenu(
    selection: String,
    actions: QuickActions?,
    verbs: SelectionVerbs,
): List<ContextMenuItem> = selectionMenu({ selection }, actions, verbs)

/**
 * Installs [selectionMenu] over the text selection inside [content].
 *
 * ⚠⚠ THIS IS [LocalTextContextMenu], NOT the [LocalContextMenuRepresentation]
 * that [WithHuginnMenus] provides at the shell root. Two different composition
 * locals, trivially conflated, and they answer different questions: the
 * representation is the LOOK (drawn once, for every menu in the app), this is the
 * CONTENT of the menu the toolkit opens over selected text. Providing the wrong
 * one gets you a correctly-styled menu with no new entries in it and looks for
 * all the world like the feature silently failed.
 *
 * Provided TIGHTLY, around the transcript's `SelectionContainer` and nothing
 * else. Every `TextField` in the app reads the same local, so providing this any
 * higher would put "Explain" in the right-click menu of the composer the text is
 * being staged into.
 *
 * Our items are PREPENDED to the toolkit's own — Copy, and whatever else the
 * selection manager offers — rather than replacing them, which is why this
 * rebuilds the default's item list (from [LocalLocalization], so the wording
 * stays the platform's) instead of calling `TextContextMenu.Default`: the default
 * builds the area itself and there is no seam to add to.
 *
 * ⚠⚠ THE ITEM LIST IS CACHED BY THE TOOLKIT, ONCE, FOR THE LIFE OF ONE
 * `ContextMenuData`. `ContextMenuArea` wraps the lambda handed to
 * [TextContextMenuArea] in a `ContextMenuData` whose `allItems` is `by lazy`, and
 * that object is only rebuilt when `ContextMenuArea` itself recomposes. Nothing
 * about dragging out a new selection recomposes it — the selection lives in the
 * `SelectionManager`, which this area never reads — so in 1.1.0 the list built on
 * the FIRST right-click of a session was the list every later right-click used,
 * with the first selection's text frozen inside every verb. Quote staged text the
 * reader had highlighted minutes earlier, and a right-click with nothing selected
 * at all still offered four verbs.
 *
 * TWO THINGS FIX IT AND BOTH ARE LOAD-BEARING:
 *
 *  * `remember(state.status, textManager)` below reads the menu's open/closed
 *    state DURING COMPOSITION, so this area recomposes every time a menu opens.
 *    That mints a fresh lambda, which mints a fresh `ContextMenuData`, which
 *    recomputes `allItems` — the labels are decided against the selection the
 *    reader can actually see. It is keyed on the OPEN rather than on the
 *    selection on purpose: a selection key would rebuild this on every pointer
 *    move of a drag, and reading `selectedText` to do it would rebuild the whole
 *    selected string each frame.
 *  * [selectionMenu] takes a FUNCTION, so the text a verb stages is read inside
 *    the click rather than captured when the row was built. The list is cheap to
 *    get wrong again; the text no longer depends on it being right.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WithTranscriptSelectionMenu(
    verbs: SelectionVerbs,
    actions: QuickActions?,
    content: @Composable () -> Unit,
) {
    val localization = LocalLocalization.current
    val menu = remember(verbs, actions, localization) {
        object : TextContextMenu {
            @Composable
            override fun Area(
                textManager: TextContextMenu.TextManager,
                state: ContextMenuState,
                content: @Composable () -> Unit,
            ) {
                // The keys are the fix, not decoration: reading `state.status`
                // here subscribes this area to the menu opening, and a new lambda
                // identity is what makes the toolkit throw away its cached list.
                // See the header. `verbs`, `actions` and `localization` are fixed
                // for the life of this object, so they cannot be keys.
                val items = remember(state.status, textManager) {
                    {
                        selectionMenu({ textManager.selectedText.text }, actions, verbs) +
                            listOfNotNull(
                                textManager.cut?.let { ContextMenuItem(localization.cut, it) },
                                textManager.copy?.let { ContextMenuItem(localization.copy, it) },
                                textManager.paste?.let { ContextMenuItem(localization.paste, it) },
                                textManager.selectAll?.let { ContextMenuItem(localization.selectAll, it) },
                            )
                    }
                }
                TextContextMenuArea(textManager, items, state, content)
            }
        }
    }
    CompositionLocalProvider(LocalTextContextMenu provides menu, content = content)
}
