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
import androidx.compose.foundation.text.selection.DisableSelection
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.silencelen.huginn.data.ProjectMemberState
import com.silencelen.huginn.data.ProjectRow
import com.silencelen.huginn.data.QuickActions
import com.silencelen.huginn.data.Session
import com.silencelen.huginn.ui.EndVerbs
import com.silencelen.huginn.ui.ProjectRules
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.silencelen.huginn.ui.QuickActionRules
import com.silencelen.huginn.ui.SelectionAction
import com.silencelen.huginn.ui.VerbTone
import com.silencelen.huginn.ui.theme.verbInk

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
 * can ask the one extra question the base class cannot answer — how hard this
 * row lands.
 *
 * ⚠ THE DESTRUCTIVE-VERB RULE, which used to be a boolean and is now three
 * tones ([VerbTone]):
 *
 *  * [VerbTone.DESTRUCTIVE] — the full `error` red. Work can be lost: the
 *    deletes, and `Kill session`.
 *  * [VerbTone.SOFT] — a LIGHTER red (`error` at [SOFT_VERB_ALPHA], one token in
 *    `:ui`'s theme so the phone's menu and this one cannot drift apart).
 *    `Wrap up` and nothing else: it ends the session, and it loses nothing,
 *    and drawing it as an ordinary row left the menu's two ending verbs looking
 *    like unrelated things.
 *  * [VerbTone.PLAIN] — everything else, INCLUDING `Archive…`, which ends a
 *    session and keeps every part of it. Red there would claim a verb does
 *    something it does not.
 */
class HuginnMenuItem(
    label: String,
    val tone: VerbTone = VerbTone.PLAIN,
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
    private val softInk: androidx.compose.ui.graphics.Color,
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
            // ⚠ A MENU ROW IS NOT SELECTABLE TEXT. This popup is the
            // representation for every context menu in the app, and one of them
            // opens over the transcript's own selection — inside its
            // `SelectionContainer`, whose registrar this content would otherwise
            // inherit across a layout-root boundary. See
            // OverlaysDisableSelectionTest for what that costs.
            DisableSelection {
                Surface(
                    color = background,
                    shape = RoundedCornerShape(6.dp),
                    tonalElevation = 8.dp,
                    modifier = Modifier
                        .widthIn(min = 168.dp, max = 320.dp)
                        .border(1.dp, outline, RoundedCornerShape(6.dp))
                        // Escape closes. The Popup is focusable so the key lands
                        // here rather than in the shell behind it, which would
                        // otherwise navigate the app out from under an open menu.
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
    }

    @Composable
    private fun MenuRow(item: ContextMenuItem, close: () -> Unit) {
        val tone = (item as? HuginnMenuItem)?.tone ?: VerbTone.PLAIN
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
                color = when (tone) {
                    VerbTone.PLAIN -> ink
                    VerbTone.SOFT -> softInk
                    VerbTone.DESTRUCTIVE -> destructiveInk
                },
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
            softInk = verbInk(VerbTone.SOFT, scheme),
            destructiveInk = verbInk(VerbTone.DESTRUCTIVE, scheme),
            hover = scheme.surfaceContainerHighest,
            outline = scheme.outline,
        )
    }
    CompositionLocalProvider(LocalContextMenuRepresentation provides look, content = content)
}

/**
 * Wraps a row so secondary-click opens [items] over it.
 *
 * ⚠ [onOpenChange] EXISTS BECAUSE THE LIST MOVES (P-02). `SessionsList` sorts by
 * `activityAt` and re-renders on every poll, so a menu opened on one row can be
 * pressed against another — the desktop walker wound down the phone walker's
 * project lead exactly that way. The list freezes its order while any row menu is
 * open, and the only thing that can tell it one is open is this.
 *
 * The state is hoisted rather than observed after the fact: `ContextMenuState`
 * carries the open/closed status and `ContextMenuArea` accepts one, so this reads
 * the real thing instead of guessing from clicks.
 */
@Composable
fun RowMenu(
    items: () -> List<ContextMenuItem>,
    onOpenChange: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val state = remember { ContextMenuState() }
    val open = state.status is ContextMenuState.Status.Open
    LaunchedEffect(open) { onOpenChange(open) }
    ContextMenuArea(items = items, state = state, content = content)
}

/**
 * THE SAME MENU, WITH SOMETHING TO PRESS.
 *
 * ⚠⚠ A RIGHT-CLICK IS NOT AN AFFORDANCE. Rename / Pause / Archive / Delete on a
 * project existed only behind a secondary click on the project's TITLE in the
 * detail header: no chevron, no ⋮, no hover mark, and a right-click on the
 * project row in the list offered nothing at all. The verbs themselves are good
 * — the three-way delete dialog is the best destructive dialog in the product —
 * and they were unreachable without guessing.
 *
 * So the same `List<ContextMenuItem>` gets a visible door. The list is built by
 * the same pure `…Menu()` function the right-click uses, which is what keeps the
 * two from offering different verbs about one object: the trap here is a second
 * hand-written copy of the items, which drifts the first time a verb is added.
 *
 * ⚠ IT DRAWS THE TONES. `HuginnMenuItem` carries whether a verb is destructive,
 * and a `DropdownMenuItem` that ignored it would put Delete in the same ink as
 * Open — in the one menu whose whole job is to be found by somebody who has not
 * used it before. Same three tones as the context-menu representation, from the
 * same `verbInk`.
 */
@Composable
fun MenuButton(
    items: () -> List<ContextMenuItem>,
    description: String,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Box(modifier) {
        IconButton(onClick = { open = true }, modifier = Modifier.size(MENU_BUTTON_DP)) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = description,
                modifier = Modifier.size(MENU_GLYPH_DP),
                tint = scheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items().forEach { item ->
                val tone = (item as? HuginnMenuItem)?.tone ?: VerbTone.PLAIN
                DropdownMenuItem(
                    text = {
                        Text(
                            item.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = verbInk(tone, scheme),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = { open = false; item.onClick() },
                )
            }
        }
    }
}

/** The ⋮ button's square and its glyph — the size every icon in a header bar is. */
private val MENU_BUTTON_DP = 28.dp

private val MENU_GLYPH_DP = 18.dp

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
            HuginnMenuItem("Delete ${selection.size} chats", VerbTone.DESTRUCTIVE) { verbs.delete(ids) },
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
    items += HuginnMenuItem("Delete", VerbTone.DESTRUCTIVE) { verbs.delete(listOf(chat.id)) }
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
    /** Ask Claude to wrap up (and, host willing, end on settle). The SOFT end. */
    val softEnd: (List<String>) -> Unit,
    /**
     * End for good AND keep the way back — the cwd, a copy of the conversation
     * and the exact resume command.
     *
     * NULL on a daemon without the feature, which is what keeps the item out of
     * the menu: a verb whose only outcome is a 404 is worse than no verb.
     */
    val archive: ((List<String>) -> Unit)? = null,
    val kill: (List<String>) -> Unit,
)

fun sessionMenu(session: Session, selection: Set<String>, verbs: SessionVerbs): List<ContextMenuItem> {
    val multi = selection.size > 1 && session.name in selection
    if (multi) {
        val names = selection.toList()
        return buildList {
            add(HuginnMenuItem(EndVerbs.soft(selection.size), VerbTone.SOFT) { verbs.softEnd(names) })
            verbs.archive?.let { a -> add(HuginnMenuItem("Archive ${selection.size} sessions…") { a(names) }) }
            add(HuginnMenuItem(EndVerbs.hard(selection.size), VerbTone.DESTRUCTIVE) { verbs.kill(names) })
        }
    }
    return buildList {
        add(HuginnMenuItem("Open") { verbs.open(session.name) })
        add(HuginnMenuItem("Rename…") { verbs.rename(session) })
        // Esc into the pane. Named for the key so the menu teaches the keyboard
        // rather than competing with it.
        add(HuginnMenuItem("Interrupt (Esc)") { verbs.interrupt(session.name) })
        add(HuginnMenuItem("Copy session name") { verbs.copyName(session.name) })
        // The context manager: types "/compact" so the owner can reclaim context
        // without opening the pane. Host guards a plain shell / waiting question.
        add(HuginnMenuItem("Compact context") { verbs.compact(session.name) })
        // The graceful sibling of "Kill session": sends the wrap-up phrase, and the
        // host (auto-end on) ends the session once it settles. A LIGHTER red than
        // the kill — it ends the session and loses nothing — and the two are a
        // pair, which is what the old "Wind down" / "End session" pairing failed
        // to say. Both words are EndVerbs'; the phone draws the same two.
        add(HuginnMenuItem(EndVerbs.soft(1), VerbTone.SOFT) { verbs.softEnd(listOf(session.name)) })
        // BETWEEN the wrap-up and the kill, because that is what it is: a wrap-up
        // that leaves something behind. PLAIN, not either red — ending a session
        // you can bring back is the least destructive of the three.
        verbs.archive?.let { a -> add(HuginnMenuItem("Archive…") { a(listOf(session.name)) }) }
        add(HuginnMenuItem(EndVerbs.hard(1), VerbTone.DESTRUCTIVE) { verbs.kill(listOf(session.name)) })
    }
}

/**
 * The handlers a project row's menu needs, and the two a MEMBER row's does.
 *
 * One class for both because they are one menu with two shapes — the project and
 * the sessions inside it — and splitting them would let a call site wire the
 * member's End to the project's Delete without anything noticing.
 */
class ProjectVerbs(
    val open: (ProjectRow) -> Unit,
    val rename: (ProjectRow) -> Unit,
    /** Pause, or resume. Which one is decided by the row's status, not by the caller. */
    val setStatus: (ProjectRow, String) -> Unit,
    val delete: (ProjectRow) -> Unit,
    /** Open one member's session detail, without leaving Projects. */
    val openMember: (ProjectRow, ProjectMemberState) -> Unit,
    /** Type a line into that member, from the lead. Rides the send queue. */
    val message: (ProjectRow, ProjectMemberState) -> Unit,
    /** End that member's tmux session. The project keeps the membership row. */
    val endMember: (ProjectRow, ProjectMemberState) -> Unit,
)

/**
 * What to offer over a PROJECT row.
 *
 * Pause and Resume are one slot, and which word it carries is
 * [ProjectRules.canTransition]'s answer rather than this file's opinion — the
 * daemon owns the transition table, and a menu that offered "Resume" on a project
 * that cannot be resumed would be a 409 with a friendly label on it. A project
 * with neither move available (an archived one) gets neither row.
 */
fun projectMenu(project: ProjectRow, verbs: ProjectVerbs): List<ContextMenuItem> = buildList {
    add(HuginnMenuItem("Open") { verbs.open(project) })
    // ⚠ THE PROJECT RENAMES, THE SLUG DOES NOT. `slug` is the tmux and peer
    // namespace every member is named in and it never moves; this changes the
    // display name and nothing else. That is also why a MEMBER cannot be renamed
    // at all — see the overload below.
    add(HuginnMenuItem("Rename…") { verbs.rename(project) })
    // ⚠ `canTransition` ANSWERS TRUE FOR A MOVE TO WHERE YOU ALREADY ARE, which
    // is right for a save that changes nothing and wrong for a menu: asked alone
    // it puts "Resume" on a running project and "Pause" on a paused one. So the
    // WORD comes from the status this project is in, and the rules say whether
    // that word is a move the daemon will take.
    val status = ProjectRules.statusWord(project.status)
    if (status == "active" && ProjectRules.canTransition(status, "paused")) {
        add(HuginnMenuItem("Pause") { verbs.setStatus(project, "paused") })
    }
    if (status == "paused" && ProjectRules.canTransition(status, "active")) {
        add(HuginnMenuItem("Resume") { verbs.setStatus(project, "active") })
    }
    if (status != "archived" && ProjectRules.canTransition(status, "archived")) {
        add(HuginnMenuItem("Archive") { verbs.setStatus(project, "archived") })
    }
    // Destructive because it forgets the record. Whether it also ENDS the sessions
    // is the dialog's question and the daemon's default is "nothing" — a delete
    // that silently killed twelve live sessions is not a delete anybody meant.
    add(HuginnMenuItem("Delete…", VerbTone.DESTRUCTIVE) { verbs.delete(project) })
}

/**
 * What to offer over a MEMBER row inside a project.
 *
 * ⚠⚠ NO RENAME, EVER, AND IT IS NOT A STYLE CHOICE. A member's tmux name is
 * `<slug>-<role>` and its peer name is `<slug>/<role>`; both are how the daemon,
 * the lead and every sibling session address it. The rename route REFUSES a
 * project member with a 409, so the item could only ever produce an error — and
 * an item that always fails teaches people the whole menu is decoration. It is
 * absent rather than disabled for the same reason [SessionVerbs.archive] is
 * absent on a daemon without archive.
 *
 * Three verbs, and they are the three things you do to a session you did not
 * start: look at it, say something to it, and stop it.
 */
fun projectMenu(
    project: ProjectRow,
    member: ProjectMemberState,
    verbs: ProjectVerbs,
): List<ContextMenuItem> = buildList {
    add(HuginnMenuItem("Open") { verbs.openMember(project, member) })
    // ⚠ THE DAEMON TYPES THIS; the sessions' own `SendMessage` does not come
    // through here at all. Named "Message" rather than "Send" because it lands in
    // a composer through the send queue and its gates, which is a message being
    // delivered rather than a key being pressed.
    add(HuginnMenuItem("Message…") { verbs.message(project, member) })
    add(HuginnMenuItem(EndVerbs.hard(1), VerbTone.DESTRUCTIVE) { verbs.endMember(project, member) })
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
    val clipboard = LocalClipboardManager.current
    val menu = remember(verbs, actions, localization, clipboard) {
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
                val items = remember(state.status, textManager, clipboard) {
                    {
                        selectionMenu({ textManager.selectedText.text }, actions, verbs) +
                            listOfNotNull(
                                textManager.cut?.let { ContextMenuItem(localization.cut, it) },
                                // ⚠⚠ OUR COPY, NOT THE TOOLKIT'S (D-5). A table is
                                // drawn cell by cell inside the SelectionContainer,
                                // so the platform hands back every cell run
                                // together: `PlanetMoonsEarthThe Moon…`. `TableGrid`
                                // draws invisible row/cell marks and
                                // `QuickActionRules.copyText` turns them back into
                                // markdown rows — which the toolkit's own Copy
                                // cannot do, and which would otherwise put the
                                // zero-width marks on the clipboard verbatim.
                                textManager.copy?.let {
                                    ContextMenuItem(localization.copy) {
                                        val text = QuickActionRules.copyText(textManager.selectedText.text)
                                        if (text.isNotEmpty()) clipboard.setText(AnnotatedString(text))
                                        state.status = ContextMenuState.Status.Closed
                                    }
                                },
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
