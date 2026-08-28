package com.silencelen.huginn.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.DraftBook
import com.silencelen.huginn.data.Device
import com.silencelen.huginn.data.Round
import com.silencelen.huginn.data.Scratchpad
import com.silencelen.huginn.data.Session
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.desktop.Splitter
import com.silencelen.huginn.desktop.View
import com.silencelen.huginn.desktop.ui.common.ChatVerbs
import com.silencelen.huginn.desktop.ui.common.DeskType
import com.silencelen.huginn.desktop.ui.common.Frame
import com.silencelen.huginn.desktop.ui.common.NothingOpen
import com.silencelen.huginn.desktop.ui.common.Selection
import com.silencelen.huginn.desktop.ui.common.noChatOpenCopy
import com.silencelen.huginn.desktop.ui.common.noSessionOpenCopy
import com.silencelen.huginn.desktop.ui.common.SessionVerbs
import com.silencelen.huginn.desktop.ui.common.Space
import com.silencelen.huginn.desktop.ui.common.Tints
import com.silencelen.huginn.desktop.ui.common.Tip
import com.silencelen.huginn.desktop.ui.common.WithHuginnMenus
import com.silencelen.huginn.desktop.ui.common.connectionTip
import com.silencelen.huginn.desktop.ui.common.railCountTip
import com.silencelen.huginn.ui.groupByMachine
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
 *      every launch is a pane you stop dragging — and the same argument is why the
 *      notch's collapse persists too. The notch is the one control that straddles:
 *      a drawer pull on the seam, pointing at what it would do.
 *   3. **Right-click, everywhere.** [WithHuginnMenus] installs the app's own menu
 *      look once, here, so a menu over a list row and a menu over selected
 *      transcript text are the same object rather than two lookalikes.
 */
@Composable
fun Shell(store: AppStore) {
    val view by store.view.collectAsState()
    val chats by store.chats.collectAsState()
    val sessions by store.sessions.collectAsState()
    val rounds by store.rounds.collectAsState()
    val devices by store.devices.collectAsState()
    val pads by store.pads.collectAsState()
    // Null until the probe answers; false hides the rail item outright, because a
    // destination that 404s is worse than one that is not offered.
    val padsAvailable by store.padsAvailable.collectAsState()
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
    val listCollapsed by store.settings.listCollapsed.collectAsState()
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

    // Pages get the list-plus-detail shape too: the list IS the navigation, and an
    // editor with no way to reach the other pages is a page, not a notebook. The
    // answer comes from [Splitter] rather than from an expression here, because
    // the window's key handler has to ask the same question — see its KDoc.
    val showsList = Splitter.showsList(view)

    // THE FRACTION, not the width. Animating `listWidth` itself would put a 150ms
    // lag on every frame of a DRAG — the seam would trail the pointer like wet
    // paint. This is 1 while the pane is open and 0 while it is shut, so a drag
    // moves the pane instantly and only the collapse is animated.
    val openFraction by animateFloatAsState(
        targetValue = if (listCollapsed) 0f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "list pane",
    )

    // The rail and the footer count MACHINES, not credentials: a box serving
    // local AI beside its claude enrolment is one device to the person reading
    // a badge, exactly as it is one card in the list. Rows still exist under
    // the fold; nothing here may count them separately again.
    val machines = groupByMachine(devices)

    WithHuginnMenus {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                NavRail(
                    current = view,
                    chats = chats.size,
                    chatsRunning = chats.count { it.running },
                    sessions = sessions.size,
                    sessionsWaiting = sessions.count { it.state == "attention" },
                    rounds = rounds.size,
                    roundsWanting = rounds.count { it.lastRun?.status == "action" },
                    roundsRunning = rounds.count { it.running },
                    devices = machines.size,
                    devicesOnline = machines.count { it.online },
                    devicesBusy = machines.count { g -> g.rows.any { it.running } },
                    pads = if (padsAvailable == true) pads else null,
                    onSelect = { store.openView(it) },
                )
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (showsList) {
                    // TWO BOXES, and the inner one is the reason it slides rather
                    // than squashes. The outer is what the Row measures, so it is
                    // what narrows to nothing; the inner holds the list at its real
                    // width throughout and the clip eats the difference.
                    //
                    // ⚠⚠ `requiredWidth`, NEVER `width`, AND IT SHIPPED WRONG ONCE.
                    // `Modifier.width` is a PREFERENCE: it is coerced into whatever
                    // constraints arrive, so an inner box asking for 320dp inside an
                    // outer that has animated down to 90 measures 90 — and the whole
                    // list re-lays-out on every frame of the slide. The list header's
                    // "+ Ask" stacked vertically and every title re-truncated, sixty
                    // times a second, to say "gone". `requiredWidth` ignores the
                    // incoming constraints, which is exactly what a thing being
                    // clipped rather than resized needs. It is the same trap the
                    // notch below documents and solves with `requiredSize`.
                    //
                    // ⚠⚠ AND THE FIX FOR THAT ONE BROUGHT ITS OWN, WHICH ALIGNMENT
                    // CANNOT REACH. A `requiredWidth` child VIOLATES its parent's
                    // max width, and Compose does not simply let the overflow hang
                    // off the end: `Placeable` coerces the reported width back into
                    // the constraints and then places the real content at
                    // `apparentToRealOffset` — `(coerced - measured) / 2` — so an
                    // oversized child is silently CENTRED. The measured shift was
                    // exactly `(320 - W) / 2` at every width. `contentAlignment`
                    // does not help and reading it as the pin is the mistake: it
                    // only chooses between positions the constraints can satisfy,
                    // and this child's size is not one of them. So the pane ate its
                    // names FIRST — "…ons 2", "…ress Huginn development notes" —
                    // which is the opposite of the intent.
                    //
                    // `wrapContentWidth(Start, unbounded = true)` is the mechanism
                    // that actually pins it: measure the child with NO width bound
                    // (so nothing is violated and nothing re-wraps), report the
                    // parent's width (so nothing is centred), and place the child's
                    // start edge at zero. The overflow then hangs off the end where
                    // the outer box's clip eats it, and the pane closes toward the
                    // RAIL with the names — the left-hand column of every list here
                    // — the last thing to go.
                    Box(
                        Modifier.width((listWidth * openFraction).dp).fillMaxHeight()
                            .clipToBounds(),
                    ) {
                        Box(
                            Modifier
                                .wrapContentWidth(align = Alignment.Start, unbounded = true)
                                .requiredWidth(listWidth.dp)
                                .fillMaxHeight(),
                        ) {
                            when (view) {
                                View.CHATS -> Column(Modifier.fillMaxSize()) {
                                    // First-launch offer, once and dismissible: the
                                    // machine may be able to SERVE, and the only
                                    // door was a Settings section nobody is told
                                    // about. Gone forever on either button, and
                                    // never shown once anything already serves.
                                    val offerSeen by store.settings.localOfferSeen.collectAsState(initial = true)
                                    if (!offerSeen && devices.none { it.scope == "generate" }) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                                        ) {
                                            Column(Modifier.padding(12.dp)) {
                                                Text("Serve local AI from this PC", style = MaterialTheme.typography.labelLarge)
                                                Text(
                                                    "This machine may be able to run small AI models and offer them " +
                                                        "in huginn's chat menus — private, on your own hardware. " +
                                                        "Setting up shows the exact plan before anything downloads.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                Row(
                                                    horizontalArrangement = Arrangement.End,
                                                    modifier = Modifier.fillMaxWidth(),
                                                ) {
                                                    TextButton(onClick = {
                                                        act { store.settings.setLocalOfferSeen() }
                                                        store.openView(View.SETTINGS)
                                                    }) { Text("Set up") }
                                                    TextButton(onClick = {
                                                        act { store.settings.setLocalOfferSeen() }
                                                    }) { Text("Not now") }
                                                }
                                            }
                                        }
                                    }
                                    Box(Modifier.weight(1f)) {
                                        ChatsList(
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
                                            onNewLocal = if (devices.any { it.scope == "generate" && it.online }) {
                                                { act { store.startLocalChat() } }
                                            } else {
                                                null
                                            },
                                            verbs = chatVerbs,
                                        )
                                    }
                                }
                                View.SCRATCHPADS -> ScratchpadsList(store)
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
                    }
                    Seam(
                        collapsed = listCollapsed,
                        onDrag = { store.settings.nudgeListWidth(it) },
                        // A reset on a SHUT seam has to include being on screen at
                        // all: "put it back the way it was meant to be" cannot
                        // sanely mean resizing something nobody can see. Expanding
                        // and resetting together is the only reading of a
                        // double-click here that leaves anything to look at.
                        onReset = {
                            store.settings.setListCollapsed(false)
                            store.settings.resetListWidth()
                        },
                        onToggle = { store.settings.toggleListCollapsed() },
                    )
                }

                Column(Modifier.fillMaxSize()) {
                    when (view) {
                        View.CHATS -> {
                            val open = chatId
                            if (open != null) {
                                ChatView(store.client, open)
                            } else {
                                // The copy knows whether the list it points at is
                                // on screen — see [noChatOpenCopy].
                                val copy = noChatOpenCopy(listCollapsed)
                                NothingOpen("No chat open", copy.sentence, copy.routes)
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
                                val copy = noSessionOpenCopy(listCollapsed)
                                NothingOpen("No session open", copy.sentence, copy.routes)
                            }
                        }

                        View.SCRATCHPADS -> ScratchpadsDetail(store)

                        // Full width, like Status: a Round row already carries its
                        // report, so there is no detail half to split off.
                        View.ROUNDS -> RoundsPane(store)
                        View.DEVICES -> DevicesPane(store)

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
                rounds = rounds,
                devices = devices,
                pads = pads,
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
                            // Per chat, each with its own cleanup: one refusal (a
                            // run in flight) must not abort the loop mid-way and
                            // leave already-deleted chats' drafts orphaned — the
                            // exact cost the comment above says this exists to
                            // prevent. The refusals that do happen are collected
                            // and NAMED, not reported as one anonymous 409.
                            val refused = mutableListOf<String>()
                            target.ids.forEach { id ->
                                runCatching { store.client.deleteChat(id) }
                                    .onSuccess {
                                        store.drafts.clear(DraftBook.chatKey(id))
                                        store.sentHistory.clear(DraftBook.chatKey(id))
                                        if (store.chatId.value == id) store.openChat(null)
                                    }
                                    .onFailure { e ->
                                        val name = chats.firstOrNull { c -> c.id == id }?.title ?: id.take(8)
                                        refused += "$name (${e.message ?: "refused"})"
                                    }
                            }
                            chatSel = Selection()
                            store.refreshChats()
                            check(refused.isEmpty()) { "not deleted: ${refused.joinToString("; ")}" }
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
    rounds: Int,
    roundsWanting: Int,
    roundsRunning: Int,
    devices: Int,
    devicesOnline: Int,
    devicesBusy: Int,
    /** Null when this daemon has no scratchpads, which removes the item entirely. */
    pads: List<Scratchpad>?,
    onSelect: (View) -> Unit,
) {
    Column(
        Modifier.width(Frame.railWidth).fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = Space.unit),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ICONS with counts; the words live on hover. The rail used to SAY
        // "Chats / Sessions / Status" beside a list pane whose own header says
        // the same word — the same fact twice in adjacent columns (owner,
        // 2026-08-12). The icon carries the destination, the tooltip and the
        // icon's contentDescription carry the word, and the two facts the rail
        // was already the right place for stay: a count is the cheapest true
        // fact about a list, and the attention mark beside the terminal is the
        // same dot the row uses, because a second vocabulary for the same
        // state is how a legend becomes necessary.
        RailItem(
            icon = Icons.Outlined.Chat,
            label = "Chats",
            count = chats,
            active = current == View.CHATS,
            tip = railCountTip(nounFor(chats, "chat"), chats, chatsRunning, "running"),
            mark = if (chatsRunning > 0) MaterialTheme.colorScheme.primary else null,
        ) { onSelect(View.CHATS) }
        // ⚠ OBSERVED ONCE, 2026-08-27, AND NOT REPRODUCED: this row read 7 with
        // two sessions live — 7 being also the number of rail items when the
        // pages one is present. Looked into and NOT found to be positional state:
        // every count here is a PARAMETER (`sessions.size`, computed in Shell),
        // no RailItem remembers one, and Compose keys these by CALL SITE rather
        // than by ordinal — so the conditional `pads?.let` slot below cannot
        // shift a count between items the way an unkeyed `items()` loop would.
        // The likeliest reading is that it was simply right: this counts every
        // tmux session the daemon lists, not the ones actively working, so seven
        // sessions with two of them live is an ordinary Tuesday. If it recurs,
        // capture `sessions.map { it.name }` beside the badge before assuming a
        // rendering fault.
        RailItem(
            icon = Icons.Outlined.Terminal,
            label = "Sessions",
            count = sessions,
            active = current == View.SESSIONS,
            tip = railCountTip(nounFor(sessions, "session"), sessions, sessionsWaiting, "waiting on you"),
            mark = if (sessionsWaiting > 0) MaterialTheme.colorScheme.error else null,
        ) { onSelect(View.SESSIONS) }
        RailItem(
            icon = Icons.Outlined.Schedule,
            label = "Rounds",
            count = rounds,
            active = current == View.ROUNDS,
            tip = railCountTip(nounFor(rounds, "round"), rounds, roundsWanting, "needing you"),
            // The same two-colour vocabulary the rows above use, for the same
            // reason: a third meaning for a dot would need a legend.
            mark = when {
                roundsWanting > 0 -> MaterialTheme.colorScheme.error
                roundsRunning > 0 -> MaterialTheme.colorScheme.primary
                else -> null
            },
        ) { onSelect(View.ROUNDS) }
        RailItem(
            icon = Icons.Outlined.Computer,
            label = "Devices",
            count = devices,
            active = current == View.DEVICES,
            tip = railCountTip(nounFor(devices, "device"), devices, devicesOnline, "reachable"),
            // Busy is the only state worth a mark here. "Offline" is the normal
            // condition of a laptop and marking it would leave the rail permanently
            // lit for something nobody needs to do anything about.
            mark = if (devicesBusy > 0) MaterialTheme.colorScheme.primary else null,
        ) { onSelect(View.DEVICES) }
        pads?.let { list ->
            RailItem(
                icon = Icons.Outlined.EditNote,
                // PAGES, which is what the palette, the panel, the list header and
                // the phone all call them. The rail was the last surface still
                // saying "Scratchpads" — the internal name — so the one word that
                // has to match across four surfaces matched on three.
                label = "Pages",
                count = list.size,
                active = current == View.SCRATCHPADS,
                tip = "Pages · notes you keep, and the one you hand to a message",
                // No mark. A page is only ever changed by the person reading this
                // rail, so there is nothing here that could need them — and a dot
                // that never means anything is a dot nobody reads.
                mark = null,
            ) { onSelect(View.SCRATCHPADS) }
        }
        RailItem(
            icon = Icons.Outlined.Speed,
            label = "Status",
            count = 0,
            active = current == View.STATUS,
            tip = "Status · host, plan headroom and token usage",
            mark = null,
        ) { onSelect(View.STATUS) }

        Spacer(Modifier.weight(1f))
        RailItem(
            icon = Icons.Outlined.Settings,
            label = "Settings",
            count = 0,
            active = current == View.SETTINGS,
            tip = "Settings · server, accounts, notifications, diagnostics",
            mark = null,
        ) { onSelect(View.SETTINGS) }
    }
}

/**
 * A rail item: the icon says where, the count says how much, the dot says "needs
 * you" — and the WORD lives in the tooltip and the icon's contentDescription,
 * because on the rail it only ever repeated the header of the pane it opened.
 * Selection is a surface tint and ink weight, NOT a left accent bar — house rule,
 * and the reason is that an accent bar is the single most legible tell of a
 * generated interface.
 */
@Composable
private fun RailItem(
    icon: ImageVector,
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
        Column(
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
                .padding(vertical = Space.unit),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                Icon(
                    icon,
                    contentDescription = label,
                    modifier = Modifier.size(20.dp),
                    tint = if (active) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The mark rides the icon's shoulder rather than taking a row of
                // its own: same dot, same meaning, no second line to pay for.
                mark?.let {
                    Box(
                        Modifier.align(Alignment.TopEnd)
                            .offset(x = Space.hair, y = -Space.hair)
                            .size(Frame.markDot).clip(CircleShape).background(it)
                    )
                }
            }
            if (count > 0) {
                Spacer(Modifier.height(Space.hair))
                Text(
                    "$count",
                    style = DeskType.rowMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// -------------------------------------------------------------------- seam

/**
 * The draggable seam and the notch on top of it: a 1px line inside an 8px hit
 * area, which is what a pointer needs and a thumb never had to care about. (It was
 * 5dp, sized to look right rather than to be grabbed.)
 *
 * Named `Seam` rather than `Splitter` because [com.silencelen.huginn.desktop.Splitter]
 * — the object holding the bounds, the reopen threshold and the width arithmetic —
 * is now called from inside it, and a composable sharing a name with the object it
 * consults is a line that reads two ways.
 *
 * WHAT EACH GESTURE MEANS depends on one thing only: whether the pane is there.
 *
 *   * **Open** — drag resizes, double-click resets the width, single-click does
 *     nothing on purpose so a stray click is inert rather than surprising.
 *     `combinedClickable` and `draggable` coexist deliberately: the drag consumes
 *     movement, the click consumes taps.
 *   * **Shut** — there is no width to change, so a drag OUTWARD past
 *     [Splitter.REOPEN_PULL] brings the pane back instead, and a double-click does
 *     the same. The way back is exactly where the way out was, which is the whole
 *     argument for keeping the seam at full size with nothing beside it.
 *
 * The notch is a separate hit target sitting on top, and the box that holds them
 * both carries NO pointer modifiers of its own. That is load-bearing: the notch is
 * wider than the seam and overhangs it, and a parent that handled pointers itself
 * would be asked about a point outside its own bounds and answer for its children.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Seam(
    collapsed: Boolean,
    onDrag: (Float) -> Unit,
    onReset: () -> Unit,
    onToggle: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // The gesture in progress, reset by `onDragStarted` rather than keyed on
    // `collapsed`: the pull that reopens FLIPS `collapsed` halfway through
    // itself, so a key on that state would clear the very "already fired" mark
    // that has to outlive it.
    var pull by remember { mutableStateOf(Splitter.NO_PULL) }
    Box(Modifier.width(Frame.splitterHit).fillMaxHeight()) {
        Box(
            Modifier.fillMaxSize()
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
                    state = rememberDraggableState { delta ->
                        when {
                            // Whatever is left of the pull that reopened the pane.
                            // The `collapsed` read has already flipped by now, so
                            // without this the tail of the gesture lands on the
                            // resize branch and nudges the width the reopen just
                            // restored — momentum arriving as intent.
                            pull.spent -> Unit
                            !collapsed -> onDrag(delta)
                            else -> {
                                val next = Splitter.pull(pull, delta)
                                if (Splitter.fired(pull, next)) onToggle()
                                pull = next
                            }
                        }
                    },
                    // A gesture is the unit here, so the accumulator belongs to
                    // one: the next drag starts clean and resizes normally.
                    onDragStarted = { pull = Splitter.NO_PULL },
                ),
            contentAlignment = Alignment.Center,
        ) {
            // ONE RULE, NOT TWO. With the pane shut the seam sits against the
            // rail's own VerticalDivider — two hairlines 5px apart, which reads as
            // a double rule somebody forgot to clean up rather than as a seam. So
            // the seam draws nothing while collapsed: the rail's divider is the
            // line, the notch sits on it, and the 8dp strip goes on being the drag
            // and double-click target while drawing no ink at all. The notch is
            // what marks where it is, which is the whole reason a hidden target is
            // findable.
            //
            // Expanded, unchanged: the line thickens on hover rather than lighting
            // up, because the seam should say "grabbable", not "selected".
            if (!collapsed) {
                Box(
                    Modifier.width(if (hovered) 2.dp else 1.dp).fillMaxHeight()
                        .background(
                            if (hovered) MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.outlineVariant
                        )
                )
            }
        }
        SeamNotch(collapsed = collapsed, onToggle = onToggle)
    }
}

/**
 * The notch: a drawer pull on the top of the seam, and the only element in the
 * frame that straddles rather than sits beside.
 *
 * `requiredSize` rather than `size` because it has to be WIDER than the 8dp column
 * it lives in — 3dp of overhang each side is what makes it read as a tab attached
 * to a line rather than a button parked next to one. A plain `size` would be
 * clamped to the parent's 8dp and the whole shape would collapse into the seam.
 *
 * QUIET AT REST, PRESSABLE ON HOVER, NEVER SELECTED. Same doctrine as the seam's
 * own hover-thicken and the same two colours: [outlineVariant] and muted ink until
 * the pointer arrives, [outline] and full ink once it has. No tint, no accent fill,
 * no shadow — a lit-up pull would claim to be the state of the pane rather than a
 * way to change it, and an accent fill is the house's most legible tell of a
 * generated interface.
 *
 * The chevron points at what the click DOES, not at where the pane is: ‹ while the
 * list is open ("this shuts it"), › while it is shut ("this brings it back").
 *
 * ⚠ AND IT FOLLOWS THE LINE IT STRADDLES, WHICH MOVES. Centred in the 8dp strip
 * the notch lands on the seam's own rule for free — but only while the seam draws
 * one. Shut, the seam is blank and the rail's divider just outside the strip is
 * the only rule left, so the notch has to step out to meet it or the line comes
 * out through its left edge and the tab reads as hanging off the divider rather
 * than riding it. [Frame.notchCollapsedShift] is that step; the offset is a
 * layout one, so the click target goes with it.
 */
@Composable
private fun BoxScope.SeamNotch(collapsed: Boolean, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Frame.notchCorner)
    val what = if (collapsed) "Show list" else "Hide list"
    Tip(
        what,
        Modifier.align(Alignment.TopCenter)
            .offset(x = if (collapsed) -Frame.notchCollapsedShift else 0.dp)
            .padding(top = Frame.notchInset)
            .requiredSize(width = Frame.notchWidth, height = Frame.notchHeight),
    ) {
        Box(
            Modifier.fillMaxSize()
                .clip(shape)
                // surfaceVariant is the rail's own ground: the notch is a piece of
                // frame, raised just enough to be a thing rather than a gap.
                .background(scheme.surfaceVariant)
                .border(1.dp, if (hovered) scheme.outline else scheme.outlineVariant, shape)
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                .clickable(interactionSource = interaction, indication = null, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (collapsed) Icons.Outlined.ChevronRight else Icons.Outlined.ChevronLeft,
                contentDescription = what,
                modifier = Modifier.size(Frame.notchChevron),
                tint = if (hovered) scheme.onSurface else scheme.onSurfaceVariant,
            )
        }
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
    rounds: List<Round>,
    devices: List<Device>,
    pads: List<Scratchpad>,
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
                    View.CHATS -> countWords(chats.size, "chat")
                    View.SESSIONS -> countWords(sessions.size, "session")
                    View.ROUNDS -> countWords(rounds.size, "round")
                    // Machines, not credential rows — same count as the rail badge.
                    View.DEVICES -> countWords(groupByMachine(devices).size, "device")
                    View.SCRATCHPADS -> countWords(pads.size, "page")
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

// ------------------------------------------------------------------ counting

/**
 * The noun for a count, and the count with it.
 *
 * The status line printed "1 rounds", "1 chats", "1 devices" — every pane, every
 * time the frame held exactly one of something, which on this fleet is most days.
 * It is the smallest possible tell that a sentence was assembled rather than
 * written, and the rail's own tooltip shares the fix so the two never disagree
 * about the same number. Every noun in the frame is regular; the day one is not,
 * this takes a second parameter rather than a special case at a call site.
 */
private fun nounFor(n: Int, singular: String) = if (n == 1) singular else "${singular}s"

private fun countWords(n: Int, singular: String) = "$n ${nounFor(n, singular)}"

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
            // The same sentence as ChatTopBar's dialog, deliberately: the audit
            // caught the two confirms CONTRADICTING each other about data loss
            // for one and the same DELETE. This is the true one — huginn's
            // record goes, the Claude session transcript on the host stays.
            if (target.ids.size == 1) {
                Triple("Delete this chat?", "Removes it from huginn. The underlying transcript file stays on the host.", "Delete")
            } else {
                Triple(
                    "Delete ${target.ids.size} chats?",
                    "Removes them from huginn. The underlying transcript files stay on the host.",
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
