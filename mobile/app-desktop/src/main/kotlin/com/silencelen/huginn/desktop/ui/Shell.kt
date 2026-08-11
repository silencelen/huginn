package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.DraftBook
import com.silencelen.huginn.data.Session
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.desktop.View
import com.silencelen.huginn.desktop.ui.common.ChatVerbs
import com.silencelen.huginn.desktop.ui.common.DeskType
import com.silencelen.huginn.desktop.ui.common.Frame
import com.silencelen.huginn.desktop.ui.common.NothingOpen
import com.silencelen.huginn.desktop.ui.common.Selection
import com.silencelen.huginn.desktop.ui.common.SessionVerbs
import com.silencelen.huginn.desktop.ui.common.Space
import com.silencelen.huginn.desktop.ui.common.Tints
import com.silencelen.huginn.desktop.ui.common.Tip
import com.silencelen.huginn.desktop.ui.common.WithHuginnMenus
import com.silencelen.huginn.desktop.ui.common.connectionTip
import com.silencelen.huginn.desktop.ui.common.railCountTip
import kotlinx.coroutines.launch
import java.awt.Cursor
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * The desktop frame: nav rail | list pane | detail pane, over a status line.
 *
 * Desktop is always wide, so there is no fold/rotate gymnastics — just panes and a
 * seam the user can drag. Status and Settings have no list, so they span both
 * columns and the splitter is not drawn: a handle on an edge that is not there is
 * a handle that does nothing.
 *
 * WHAT MAKES IT A FRAME RATHER THAN A SCREEN. Three things, and all three are
 * ordinary on a desktop and impossible on a phone:
 *
 *   1. **A status line.** It is the only surface that answers "what is this client
 *      doing right now" without navigating anywhere — route, watch stream, what is
 *      working, what is waiting on a human. It also absorbed the error banner,
 *      which used to appear ABOVE the content and shove the whole detail pane down
 *      by 26px whenever the network hiccuped. Two controls reporting the app's
 *      condition became one, which is the house rule.
 *   2. **A seam that is remembered.** Width persists, double-click resets it, and
 *      Ctrl+[ / Ctrl+] move it from the keyboard. A pane you have to re-drag on
 *      every launch is a pane you stop dragging.
 *   3. **Right-click, everywhere.** [WithHuginnMenus] installs the app's own menu
 *      look once, here, so a menu over a list row and a menu over selected
 *      transcript text are the same object rather than two lookalikes.
 */
@Composable
fun Shell(store: AppStore) {
    val view by store.view.collectAsState()
    val chats by store.chats.collectAsState()
    val sessions by store.sessions.collectAsState()
    val loaded by store.listsLoaded.collectAsState()
    // Its OWN flag. The sessions list used to be told "loaded" by the chats fetch
    // returning, so a start where chats answered and sessions did not drew "No
    // sessions" — a confident claim about a list nothing had read yet.
    val sessionsLoaded by store.sessionsLoaded.collectAsState()
    val chatId by store.chatId.collectAsState()
    val sessionName by store.sessionName.collectAsState()
    val watchConnected by store.watchConnected.collectAsState()
    val error by store.error.collectAsState()
    val route by store.route.collectAsState()
    val status by store.status.collectAsState()
    val plan by store.plan.collectAsState()
    val usage by store.usage.collectAsState()
    val listWidth by store.settings.listWidth.collectAsState()
    val notifyEnabled by store.settings.notifyEnabled.collectAsState(true)
    val scope = rememberCoroutineScope()

    // Multi-select, one per list. Held HERE rather than inside the list so it
    // survives switching to Status and back — a selection that evaporates because
    // you glanced at the host's disk usage is a selection nobody trusts.
    var chatSel by remember { mutableStateOf(Selection()) }
    var sessionSel by remember { mutableStateOf(Selection()) }
    // The lists re-poll every 5s. A selected chat that has since been deleted must
    // not stay in a set that a later "Delete 3 chats" would send to the daemon.
    LaunchedEffect(chats) { chatSel = chatSel.retaining(chats.map { it.id }) }
    LaunchedEffect(sessions) { sessionSel = sessionSel.retaining(sessions.map { it.name }) }

    // The two prompts the verbs need. Nullable state rather than a boolean plus a
    // subject, so "which one are we renaming" cannot get out of step with "is the
    // dialog up".
    var renaming by remember { mutableStateOf<RenameTarget?>(null) }
    var namingSession by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<ConfirmTarget?>(null) }

    // Named `act` rather than `run`: a local function called `run` shadows
    // `kotlin.run` for the whole of this composable, which is exactly the kind of
    // quiet trap that makes a later edit behave differently than it reads.
    fun act(block: suspend () -> Unit) = scope.launch {
        runCatching { block() }.onFailure { store.noteError(it) }
    }

    fun copy(text: String) {
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    }

    val chatVerbs = ChatVerbs(
        open = { store.openChat(it) },
        rename = { renaming = RenameTarget.OfChat(it.id, it.title ?: "") },
        stop = { id -> act { store.client.cancelChat(id); store.refreshChats() } },
        copyId = { copy(it) },
        delete = { ids -> confirming = ConfirmTarget.DeleteChats(ids) },
    )
    val sessionVerbs = SessionVerbs(
        open = { store.openSession(it) },
        rename = { renaming = RenameTarget.OfSession(it.name, it.name) },
        // The pane's own interrupt, sent as the key rather than as a verb the
        // daemon would have to invent: Esc is what a person at that tmux window
        // would press, and it is what the menu item is named after.
        interrupt = { name -> act { store.client.sendKeys(name, keys = listOf("Escape")) } },
        copyName = { copy(it) },
        // No confirm dialog: compaction is cheap and reversible-in-effect (it only
        // rewrites context). Success is silent — the Compacting… marker appears
        // when it starts; a 409 (waiting question / plain shell) surfaces its note.
        compact = { name -> act { store.client.compactSession(name) } },
        softEnd = { names -> confirming = ConfirmTarget.SoftEndSessions(names) },
        kill = { names -> confirming = ConfirmTarget.KillSessions(names) },
    )

    val showsList = view == View.CHATS || view == View.SESSIONS

    WithHuginnMenus {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                NavRail(
                    current = view,
                    chats = chats.size,
                    chatsRunning = chats.count { it.running },
                    sessions = sessions.size,
                    sessionsWaiting = sessions.count { it.state == "attention" },
                    onSelect = { store.openView(it) },
                )
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (showsList) {
                    Box(Modifier.width(listWidth.dp).fillMaxHeight()) {
                        when (view) {
                            View.CHATS -> ChatsList(
                                chats = chats,
                                loaded = loaded,
                                activeId = chatId,
                                selection = chatSel,
                                onSelect = { chatSel = it },
                                onOpen = { store.openChat(it) },
                                onNew = { mode ->
                                    act {
                                        val made = store.client.createChat(mode)
                                        store.openChat(made.id)
                                        store.refreshChats()
                                    }
                                },
                                verbs = chatVerbs,
                            )
                            View.SESSIONS -> SessionsList(
                                sessions = sessions,
                                loaded = sessionsLoaded,
                                activeName = sessionName,
                                selection = sessionSel,
                                onSelect = { sessionSel = it },
                                onOpen = { store.openSession(it) },
                                onNew = { namingSession = true },
                                verbs = sessionVerbs,
                            )
                            else -> Unit
                        }
                    }
                    Splitter(
                        onDrag = { store.settings.nudgeListWidth(it) },
                        onReset = { store.settings.resetListWidth() },
                    )
                }

                Column(Modifier.fillMaxSize()) {
                    when (view) {
                        View.CHATS -> {
                            val open = chatId
                            if (open != null) {
                                ChatView(store.client, open)
                            } else {
                                NothingOpen(
                                    "No chat open",
                                    "Pick one on the left, or start a new one. Ask reads and reasons; Act can change things on the host.",
                                    listOf(
                                        "Ctrl N" to "new Ask chat",
                                        "Ctrl Shift N" to "new Act chat",
                                        "Ctrl K" to "find one by name",
                                    ),
                                )
                            }
                        }

                        View.SESSIONS -> {
                            // Hoisted rather than `sessionName!!`: a `by`-delegated
                            // value is not smart-cast across the read, and `!!` on
                            // the owner's daily driver is a crash waiting for a race.
                            val open = sessionName
                            if (open != null) {
                                SessionView(store, open)
                            } else {
                                NothingOpen(
                                    "No session open",
                                    "Every tmux session on the host is on the left. Opening one shows its conversation and its live screen.",
                                    listOf(
                                        "Ctrl K" to "find one by name",
                                        "Alt ↑ / ↓" to "walk the list",
                                        "Right-click" to "rename, interrupt, end",
                                    ),
                                )
                            }
                        }

                        View.STATUS -> StatusView(status, plan, usage, route, watchConnected)
                        // The whole store: Settings now owns accounts, the update
                        // state and the diagnostics report, and each of those needs
                        // a different corner of it.
                        View.SETTINGS -> SettingsView(store)
                    }
                }
            }

            StatusLine(
                view = view,
                route = route,
                watchConnected = watchConnected,
                notifyEnabled = notifyEnabled,
                chats = chats,
                sessions = sessions,
                selected = if (view == View.SESSIONS) sessionSel.size else chatSel.size,
                error = error,
                onDismissError = { store.clearError() },
                onOpenSession = { store.openSession(it) },
                onClearSelection = { if (view == View.SESSIONS) sessionSel = Selection() else chatSel = Selection() },
            )
        }

        if (namingSession) {
            NewSessionDialog(
                taken = sessions.map { it.name }.toSet(),
                onDismiss = { namingSession = false },
                onConfirm = { name ->
                    namingSession = false
                    act {
                        // Open what tmux CALLED it. The host reads the name back
                        // rather than echoing the request, because tmux rewrites
                        // a '.' to '_' and still succeeds — opening the requested
                        // name would 404 on everything after it.
                        val made = store.client.createSession(name)
                        store.refreshSessions()
                        store.openSession(made)
                    }
                },
            )
        }

        renaming?.let { target ->
            RenameDialog(
                target = target,
                onDismiss = { renaming = null },
                onConfirm = { next ->
                    renaming = null
                    when (target) {
                        is RenameTarget.OfChat -> act {
                            store.client.renameChat(target.id, next)
                            store.refreshChats()
                        }
                        is RenameTarget.OfSession -> act {
                            store.client.renameSession(target.id, next)
                            // MOVED, not dropped. A session's draft is keyed by
                            // name, so a rename orphans it under a key nothing
                            // will ever read again — and half a typed instruction
                            // is worth keeping across a rename. The phone has done
                            // this since sessions became renameable.
                            store.drafts.move(
                                DraftBook.sessionKey(target.id),
                                DraftBook.sessionKey(next),
                            )
                            store.sentHistory.move(
                                DraftBook.sessionKey(target.id),
                                DraftBook.sessionKey(next),
                            )
                            store.refreshSessions()
                            // The open session is addressed by name, so a rename
                            // that did not follow leaves the detail pane polling a
                            // session that no longer exists.
                            if (store.sessionName.value == target.id) store.openSession(next)
                        }
                    }
                },
            )
        }

        confirming?.let { target ->
            ConfirmDialog(
                target = target,
                onDismiss = { confirming = null },
                onConfirm = {
                    confirming = null
                    when (target) {
                        // The drafts go with the targets. The detail views already
                        // clear the OPEN one when it vanishes underneath them, but
                        // a multi-select delete from the list never opens the other
                        // rows — and the draft map is rewritten whole on every
                        // save, so an orphan is paid for on every keystroke in
                        // every other target, forever.
                        is ConfirmTarget.DeleteChats -> act {
                            target.ids.forEach { store.client.deleteChat(it) }
                            target.ids.forEach { store.drafts.clear(DraftBook.chatKey(it)) }
                            target.ids.forEach { store.sentHistory.clear(DraftBook.chatKey(it)) }
                            if (store.chatId.value in target.ids) store.openChat(null)
                            chatSel = Selection()
                            store.refreshChats()
                        }
                        is ConfirmTarget.KillSessions -> act {
                            target.names.forEach { store.client.killSession(it) }
                            target.names.forEach { store.drafts.clear(DraftBook.sessionKey(it)) }
                            target.names.forEach { store.sentHistory.clear(DraftBook.sessionKey(it)) }
                            if (store.sessionName.value in target.names) store.openSession(null)
                            sessionSel = Selection()
                            store.refreshSessions()
                        }
                        // A soft end SENDS a message; the session lives on and may
                        // even stay (a wrap-up question cancels the auto-end) — so
                        // drafts and history are deliberately NOT cleared here.
                        is ConfirmTarget.SoftEndSessions -> act {
                            target.names.forEach { store.client.softEndSession(it) }
                            store.refreshSessions()
                        }
                    }
                },
            )
        }
    }
}

// -------------------------------------------------------------------- rail

@Composable
private fun NavRail(
    current: View,
    chats: Int,
    chatsRunning: Int,
    sessions: Int,
    sessionsWaiting: Int,
    onSelect: (View) -> Unit,
) {
    Column(
        Modifier.width(Frame.railWidth).fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = Space.unit),
        horizontalAlignment = Alignment.Start,
    ) {
        // COUNTS, and they are the reason this rail is no longer three words in a
        // 104×800 void. A count is the cheapest true fact about a list and the one
        // the rail was already the right place for; the attention mark beside
        // "Sessions" is the same dot the row uses, because a second vocabulary for
        // the same state is how a legend becomes necessary.
        RailItem(
            label = "Chats",
            count = chats,
            active = current == View.CHATS,
            tip = railCountTip("chats", chats, chatsRunning, "running"),
            mark = if (chatsRunning > 0) MaterialTheme.colorScheme.primary else null,
        ) { onSelect(View.CHATS) }
        RailItem(
            label = "Sessions",
            count = sessions,
            active = current == View.SESSIONS,
            tip = railCountTip("sessions", sessions, sessionsWaiting, "waiting on you"),
            mark = if (sessionsWaiting > 0) MaterialTheme.colorScheme.error else null,
        ) { onSelect(View.SESSIONS) }
        RailItem(
            label = "Status",
            count = 0,
            active = current == View.STATUS,
            tip = "Host, plan headroom and token usage",
            mark = null,
        ) { onSelect(View.STATUS) }

        Spacer(Modifier.weight(1f))
        RailItem(
            label = "Settings",
            count = 0,
            active = current == View.SETTINGS,
            tip = "Server, accounts, notifications, diagnostics",
            mark = null,
        ) { onSelect(View.SETTINGS) }
    }
}

/**
 * A rail row. Selection is a surface tint and ink weight, NOT a left accent bar —
 * house rule, and the reason is that an accent bar is the single most legible tell
 * of a generated interface.
 */
@Composable
private fun RailItem(
    label: String,
    count: Int,
    active: Boolean,
    tip: String,
    mark: Color?,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Tip(tip) {
        Row(
            Modifier.fillMaxWidth()
                // The SAME "where you are" wash the open list row uses: one mark
                // with one meaning across the whole frame, rather than a grey here
                // and a different grey there.
                .background(
                    when {
                        active -> Tints.here
                        hovered -> Tints.hover
                        else -> Color.Transparent
                    }
                )
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = Space.wide, vertical = Space.unit),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = DeskType.rail,
                color = if (active) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            mark?.let {
                Box(Modifier.size(Frame.markDot).clip(CircleShape).background(it))
                Spacer(Modifier.width(Space.tight))
            }
            if (count > 0) {
                Text(
                    "$count",
                    style = DeskType.rowMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- splitter

/**
 * The draggable seam: a 1px line inside an 8px hit area, which is what a pointer
 * needs and a thumb never had to care about. (It was 5dp, sized to look right
 * rather than to be grabbed.)
 *
 * Double-click resets it. That is the standard desktop escape hatch for a pane
 * someone has dragged into uselessness, and without it the only way back to a sane
 * width is to guess it by eye. `combinedClickable` and `draggable` coexist here
 * deliberately — the drag consumes movement, the click consumes taps, and the
 * single-click branch does nothing on purpose so a stray click on the seam is
 * inert rather than surprising.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Splitter(onDrag: (Float) -> Unit, onReset: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier.width(Frame.splitterHit).fillMaxHeight()
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onDoubleClick = onReset,
                onClick = {},
            )
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { onDrag(it) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // The line thickens on hover rather than lighting up: the seam should say
        // "grabbable", not "selected".
        Box(
            Modifier.width(if (hovered) 2.dp else 1.dp).fillMaxHeight()
                .background(
                    if (hovered) MaterialTheme.colorScheme.outline
                    else MaterialTheme.colorScheme.outlineVariant
                )
        )
    }
}

// ------------------------------------------------------------- status line

/**
 * The foot of the window: route, stream, work, selection, error.
 *
 * NOT A TOAST AND NOT A BANNER. Both of those are events; this is a condition, and
 * a condition belongs somewhere that does not move. It also replaces the error
 * banner that used to push the detail pane down 26px on every transient network
 * error — the same information, in the place already reserved for it, with the
 * full text on hover and a click to dismiss.
 */
@Composable
private fun StatusLine(
    view: View,
    route: String,
    watchConnected: Boolean,
    notifyEnabled: Boolean,
    chats: List<Chat>,
    sessions: List<Session>,
    selected: Int,
    error: String?,
    onDismissError: () -> Unit,
    onOpenSession: (String) -> Unit,
    onClearSelection: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val waiting = sessions.filter { it.state == "attention" }
    val working = sessions.count { it.state == "running" } + chats.count { it.running }

    HorizontalDivider(color = scheme.outlineVariant)
    Row(
        Modifier.fillMaxWidth().height(Frame.statusHeight)
            .background(if (error != null) scheme.error.copy(alpha = 0.12f) else scheme.surfaceVariant)
            .padding(horizontal = Space.wide),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.wide),
    ) {
        if (error != null) {
            Tip(
                "$error\n\nClick to dismiss. The client keeps retrying on its own.",
                Modifier.weight(1f),
            ) {
                Text(
                    error,
                    style = DeskType.status,
                    color = scheme.error,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier
                        .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismissError,
                        ),
                )
            }
        } else {
            // What is happening, in the order it matters. "Waiting on you" first,
            // because it is the only item that is about the reader.
            if (waiting.isNotEmpty()) {
                val names = waiting.joinToString(", ") { it.name }
                Tip("$names\n\nClick to open the first one.") {
                    Row(
                        Modifier
                            .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onOpenSession(waiting.first().name) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(Frame.markDot).clip(CircleShape).background(scheme.error))
                        Spacer(Modifier.width(Space.tight))
                        Text(
                            if (waiting.size == 1) "${waiting.first().name} needs you"
                            else "${waiting.size} sessions need you",
                            style = DeskType.status,
                            color = scheme.error,
                        )
                    }
                }
            }
            if (working > 0) {
                Tip("$working of the things this client watches are mid-turn right now") {
                    Text("$working working", style = DeskType.status, color = scheme.onSurfaceVariant)
                }
            }
            if (waiting.isEmpty() && working == 0) {
                Text("Idle", style = DeskType.status, color = scheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            if (selected > 1) {
                Tip("Click to clear the selection") {
                    Text(
                        "$selected selected",
                        style = DeskType.status,
                        color = scheme.primary,
                        modifier = Modifier
                            .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onClearSelection,
                            ),
                    )
                }
            }
            Text(
                when (view) {
                    View.CHATS -> "${chats.size} chats"
                    View.SESSIONS -> "${sessions.size} sessions"
                    View.STATUS -> "status"
                    View.SETTINGS -> "settings"
                },
                style = DeskType.status,
                color = scheme.onSurfaceVariant,
            )
        }

        // The connection, always last and always in the same place: it is the one
        // mark whose meaning is about something other than what is on screen.
        Tip(connectionTip(watchConnected, route, notifyEnabled)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(Frame.markDot).clip(CircleShape)
                        .background(if (watchConnected) scheme.primary else scheme.error)
                )
                Spacer(Modifier.width(Space.tight))
                Text(
                    route.removePrefix("https://").removePrefix("http://").trimEnd('/'),
                    style = DeskType.status,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ----------------------------------------------------------------- dialogs

sealed interface RenameTarget {
    val id: String
    val current: String

    data class OfChat(override val id: String, override val current: String) : RenameTarget
    data class OfSession(override val id: String, override val current: String) : RenameTarget
}

sealed interface ConfirmTarget {
    data class DeleteChats(val ids: List<String>) : ConfirmTarget
    data class KillSessions(val names: List<String>) : ConfirmTarget
    /** A wrap-up request, not a destruction: the session ends only after it settles. */
    data class SoftEndSessions(val names: List<String>) : ConfirmTarget
}

/**
 * Names a new tmux session.
 *
 * The desktop could list, open and kill sessions but never make one — the empty
 * state said so out loud ("this client watches, it does not create them") while
 * the phone had created them all along, so the only way to start a session from
 * a desk was to SSH in.
 *
 * [taken] is checked here so an existing name is refused while it is being typed,
 * rather than after a round trip that comes back 409.
 */
@Composable
private fun NewSessionDialog(
    taken: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val canon = text.trim().lowercase()
    val wellFormed = canon.matches(SESSION_NAME)
    val clash = wellFormed && canon in taken
    val ok = wellFormed && !clash
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New session", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column {
                DialogField(text, ok || text.isEmpty()) { text = it }
                Text(
                    when {
                        clash -> "There is already a session called $canon."
                        else -> "Letters, digits, _ . and - ; starts with a letter or digit. " +
                            "Claude Code starts in it automatically."
                    },
                    style = DeskType.rowMeta,
                    color = if (clash) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Space.unit),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = { onConfirm(canon) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDialog(
    target: RenameTarget,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(target) { mutableStateOf(target.current) }
    val session = target is RenameTarget.OfSession
    // The daemon's own NAME_RE, so the dialog refuses what the host would refuse
    // rather than sending it and reporting a 400 afterwards.
    val ok = if (session) text.trim().lowercase().matches(SESSION_NAME) else text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (session) "Rename session" else "Rename chat", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column {
                DialogField(text, ok) { text = it }
                Text(
                    if (session) "Letters, digits, _ . and - ; starts with a letter or digit."
                    else "Only the title changes; the chat keeps its history.",
                    style = DeskType.rowMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Space.unit),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = { onConfirm(if (session) text.trim().lowercase() else text.trim()) }) {
                Text("Rename")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DialogField(value: String, ok: Boolean, onChange: (String) -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = Space.wide, vertical = Space.unit),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ConfirmDialog(target: ConfirmTarget, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val (title, body, verb) = when (target) {
        is ConfirmTarget.DeleteChats ->
            if (target.ids.size == 1) {
                Triple("Delete this chat?", "Its transcript goes with it. This cannot be undone.", "Delete")
            } else {
                Triple(
                    "Delete ${target.ids.size} chats?",
                    "Their transcripts go with them. This cannot be undone.",
                    "Delete ${target.ids.size}",
                )
            }
        is ConfirmTarget.KillSessions ->
            if (target.names.size == 1) {
                Triple(
                    "End ${target.names.first()}?",
                    "The tmux session and anything running inside it stop.",
                    "End session",
                )
            } else {
                Triple(
                    "End ${target.names.size} sessions?",
                    "Each tmux session and anything running inside it stops.",
                    "End ${target.names.size}",
                )
            }
        is ConfirmTarget.SoftEndSessions ->
            if (target.names.size == 1) {
                Triple(
                    "Wind down ${target.names.first()}?",
                    "Sends Claude the wrap-up instruction (finish, commit, prepare to end). " +
                        "If auto-end is on for the host, the session ends on its own once it settles; " +
                        "a wrap-up question keeps it open.",
                    "Send wrap-up",
                )
            } else {
                Triple(
                    "Wind down ${target.names.size} sessions?",
                    "Each gets the wrap-up instruction and, with auto-end on, ends once it settles.",
                    "Send wrap-up",
                )
            }
    }
    // A wind-down sends a message; only the truly destructive verbs are red.
    val destructive = target !is ConfirmTarget.SoftEndSessions
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleSmall) },
        text = {
            Text(
                body,
                style = DeskType.rowMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    verb,
                    color = if (destructive) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** What the daemon will route to; kept in step with the phone's copy of it. */
private val SESSION_NAME = Regex("^[a-z0-9_][a-z0-9_.-]{0,49}$")
