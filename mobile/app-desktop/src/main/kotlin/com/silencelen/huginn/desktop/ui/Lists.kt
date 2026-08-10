package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.Session
import com.silencelen.huginn.desktop.ui.common.ChatVerbs
import com.silencelen.huginn.desktop.ui.common.DeskType
import com.silencelen.huginn.desktop.ui.common.EmptyBlock
import com.silencelen.huginn.desktop.ui.common.Frame
import com.silencelen.huginn.desktop.ui.common.LoadingBlock
import com.silencelen.huginn.desktop.ui.common.RowMenu
import com.silencelen.huginn.desktop.ui.common.Selection
import com.silencelen.huginn.desktop.ui.common.SessionVerbs
import com.silencelen.huginn.desktop.ui.common.Space
import com.silencelen.huginn.desktop.ui.common.Tints
import com.silencelen.huginn.desktop.ui.common.Tip
import com.silencelen.huginn.desktop.ui.common.bgWorkTip
import com.silencelen.huginn.desktop.ui.common.chatMenu
import com.silencelen.huginn.desktop.ui.common.chatStateTip
import com.silencelen.huginn.desktop.ui.common.clickSelection
import com.silencelen.huginn.desktop.ui.common.opensOnClick
import com.silencelen.huginn.desktop.ui.common.sessionMenu
import com.silencelen.huginn.desktop.ui.common.sessionStateTip
import com.silencelen.huginn.desktop.ui.common.timeTip
import java.awt.Cursor

/**
 * The list pane, both flavours.
 *
 * ROW SHAPE, and it is a house rule rather than a preference: no left accent bar
 * on a row or a card. Selection is a surface tint, state is a small dot in the
 * text flow, and a count is a muted suffix — marks that live in the row's own
 * vernacular rather than a badge shouting over it.
 *
 * WHAT A MOUSE ADDS, and it is most of what changed here:
 *
 *   - **Right-click.** Every verb this client has for a chat or a session lives in
 *     one menu ([chatMenu], [sessionMenu]) rather than as hover icons in the list
 *     and buttons in a header. The house rule about one control per verb is easy
 *     to keep when the control costs nothing to add.
 *   - **Hover.** The 7px state dot and the muted counts now answer for themselves
 *     ([Tip]), which is what lets them stay 7px. A legend would have cost a row of
 *     chrome on every list forever to say the same thing worse.
 *   - **Modifiers.** Ctrl and Shift build a selection the menu then addresses as a
 *     whole. A phone answers this with a long-press mode and a second app bar; a
 *     pointer answers it with no chrome at all.
 *
 * DENSITY. These rows were the phone's, and a phone's row is sized for a thumb —
 * 14sp on a 20sp line inside 8dp of padding, 55dp of pitch. On a 1400px window
 * that is not generous, it is a phone screenshot at desktop scale. The numbers now
 * come from [Space] and [DeskType]: 13/17 titles, 4dp of padding, ~44dp of pitch,
 * everything on the 2/4/8 grid.
 */
@Composable
fun ChatsList(
    chats: List<Chat>,
    loaded: Boolean,
    activeId: String?,
    selection: Selection,
    onSelect: (Selection) -> Unit,
    onOpen: (String) -> Unit,
    onNew: (String) -> Unit,
    verbs: ChatVerbs,
) {
    Column(Modifier.fillMaxSize()) {
        ListHeader("Chats", chats.size, selection.size) {
            TextButton(onClick = { onNew("ask") }) { Text("+ Ask", style = DeskType.rail) }
            TextButton(onClick = { onNew("act") }) { Text("+ Act", style = DeskType.rail) }
        }
        // Loading and empty are DIFFERENT SENTENCES. `loaded` is false only until
        // the first fetch settles, and a cold start that says "No chats yet" is a
        // client asserting something it has not been told.
        if (chats.isEmpty()) {
            if (loaded) {
                EmptyBlock(
                    "No chats yet",
                    "Ask answers questions and reads; Act can run commands and change files on the host.",
                )
            } else {
                LoadingBlock("chats")
            }
            return@Column
        }
        val order = remember(chats) { chats.map { it.id } }
        LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState()) {
            itemsIndexed(chats, key = { _, it -> it.id }) { i, chat ->
                RowMenu({ chatMenu(chat, selection.ids, verbs) }) {
                    ChatRow(
                        chat = chat,
                        active = chat.id == activeId,
                        selected = chat.id in selection && selection.size > 1,
                        onClick = { ctrl, shift ->
                            onSelect(clickSelection(selection, chat.id, order, ctrl, shift))
                            if (opensOnClick(ctrl, shift)) onOpen(chat.id)
                        },
                    )
                }
                // Between rows only. A rule under the LAST row draws a line across
                // empty space and is the clearest tell that a list was laid out for
                // a screen that always scrolls.
                if (i < chats.lastIndex) RowRule()
            }
        }
    }
}

@Composable
private fun ChatRow(
    chat: Chat,
    active: Boolean,
    selected: Boolean,
    onClick: (ctrl: Boolean, shift: Boolean) -> Unit,
) {
    val now = remember(chat) { System.currentTimeMillis() / 1000 }
    RowFrame(active = active, selected = selected, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The dot is the whole state mark, and hover is what makes that
            // affordable: "running" plus how long, plus what is queued behind it.
            Tip(chatStateTip(chat.running, chat.pending, chat.turns, chat.updatedAt, now)) {
                if (chat.running) StateDot(MaterialTheme.colorScheme.primary)
                else Spacer(Modifier.width(TEXT_INDENT))
            }
            Text(
                chat.title ?: "Untitled",
                style = DeskType.rowTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (chat.mode == "act") {
                Tip("Act mode — this chat can run commands and change files on the host") {
                    Muted("act", Modifier.padding(start = Space.tight))
                }
            }
            Tip(timeTip("Last activity", chat.updatedAt, now)) {
                Muted(relTime(chat.updatedAt), Modifier.padding(start = Space.unit))
            }
        }
        val snippet = chat.lastSnippet
        if (chat.pending > 0 || !snippet.isNullOrBlank()) {
            Row(
                Modifier.padding(top = Space.hair, start = TEXT_INDENT),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (chat.pending > 0) Muted("+${chat.pending} queued", Modifier.padding(end = Space.unit))
                if (!snippet.isNullOrBlank()) {
                    Muted(snippet.replace('\n', ' '), Modifier.weight(1f), maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun SessionsList(
    sessions: List<Session>,
    loaded: Boolean,
    activeName: String?,
    selection: Selection,
    onSelect: (Selection) -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    verbs: SessionVerbs,
) {
    Column(Modifier.fillMaxSize()) {
        ListHeader("Sessions", sessions.size, selection.size) {
            TextButton(onClick = onNew) { Text("+ New", style = DeskType.rail) }
        }
        if (sessions.isEmpty()) {
            if (loaded) {
                EmptyBlock(
                    "No tmux sessions",
                    "New starts one on the host with Claude Code already running in it. " +
                        "Sessions started from a terminal appear here too.",
                )
            } else {
                LoadingBlock("sessions")
            }
            return@Column
        }
        val order = remember(sessions) { sessions.map { it.name } }
        LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState()) {
            itemsIndexed(sessions, key = { _, it -> it.name }) { i, s ->
                RowMenu({ sessionMenu(s, selection.ids, verbs) }) {
                    SessionRow(
                        session = s,
                        active = s.name == activeName,
                        selected = s.name in selection && selection.size > 1,
                        onClick = { ctrl, shift ->
                            onSelect(clickSelection(selection, s.name, order, ctrl, shift))
                            if (opensOnClick(ctrl, shift)) onOpen(s.name)
                        },
                    )
                }
                if (i < sessions.lastIndex) RowRule()
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: Session,
    active: Boolean,
    selected: Boolean,
    onClick: (ctrl: Boolean, shift: Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val now = remember(session) { System.currentTimeMillis() / 1000 }
    val dot = when (session.state) {
        "running" -> scheme.primary
        "attention" -> scheme.error
        else -> null
    }
    RowFrame(active = active, selected = selected, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tip(sessionStateTip(session.state, session.stateSince, now)) {
                if (dot != null) StateDot(dot)
                else Spacer(Modifier.width(TEXT_INDENT))
            }
            Text(
                session.title ?: session.name,
                style = DeskType.rowTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Tip(timeTip("Last pane activity", session.activityAt, now)) {
                Muted(relTime(session.activityAt), Modifier.padding(start = Space.unit))
            }
        }
        Row(
            Modifier.padding(top = Space.hair, start = TEXT_INDENT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The tmux name, shown only when the title is something else — two
            // identical strings on two lines is the row wasting half its height.
            if (session.title != null && session.title != session.name) {
                Muted(session.name, Modifier.padding(end = Space.unit))
            }
            val work = bgWorkTip(session.bgShells, session.bgAgents, session.bgTask)
            if (work.isNotEmpty()) {
                Tip(work) {
                    Muted(bgLabel(session.bgShells, session.bgAgents), Modifier.padding(end = Space.unit))
                }
            }
            session.preview.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                Muted(it, Modifier.weight(1f), maxLines = 1)
            }
        }
    }
}

/**
 * How far the second line hangs, so both lines of a row share one text column.
 *
 * The dot's own width plus its gap. Without it the title starts 11dp right of its
 * own snippet and every row in the list has a small ragged step in it — visible in
 * a screenshot the moment the rows got dense enough to sit close together, and
 * invisible before that.
 */
private val TEXT_INDENT = Frame.dot + Space.tight

/** "2 bg", "1 bg · 3 agents" — the shortest true form of what the tip spells out. */
fun bgLabel(bgShells: Int, bgAgents: Int): String = buildList {
    if (bgShells > 0) add("$bgShells bg")
    if (bgAgents > 0) add("$bgAgents agent${if (bgAgents == 1) "" else "s"}")
}.joinToString(" · ")

// ------------------------------------------------------------------ pieces

/**
 * The shared row body: tint, hover, pointer, and a click that reports its
 * modifier keys.
 *
 * `clickable` throws the modifier keys away and the whole multi-select story is
 * Ctrl and Shift, so they are OBSERVED off the pointer stream instead — on the
 * Initial pass, consuming nothing, so every other gesture on this row still works.
 *
 * BOTH SHORTER ROUTES WERE TRIED AND BOTH FAILED, silently and differently:
 *
 *   - `LocalWindowInfo.keyboardModifiers` read inside the click lambda is still
 *     EMPTY at the moment the click lands. Every Ctrl-click then behaved as a
 *     plain click and replaced the selection instead of extending it.
 *   - `Modifier.mouseClickable`, which does carry the modifiers, awaits the first
 *     button-down of ANY button and consumes it — which killed the context menu on
 *     every row at the same time. Its `buttons` are also read at release, when
 *     nothing is pressed, so the primary-button guard was never true and rows
 *     stopped opening at all.
 *
 * Neither produced a warning or a log line. The first showed up as a menu offering
 * the singular verb; the second as a menu that did not open. Both were found by
 * looking at screenshots.
 */
@Composable
private fun RowFrame(
    active: Boolean,
    selected: Boolean,
    onClick: (ctrl: Boolean, shift: Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // Deliberately NOT snapshot state: it is read once inside the click callback
    // and never drawn, and making it observable would recompose every visible row
    // on every pointer event that crosses the list.
    val mods = remember { Mods() }
    // OPEN is the row whose detail fills the pane, SELECTED is a row a bulk verb
    // would address, HOVER is where the pointer is — three facts that can be true
    // of three different rows at once. A phone needs only the first. See [Tints].
    val tint = when {
        active -> Tints.here
        selected -> Tints.marked
        hovered -> Tints.hover
        else -> Color.Transparent
    }
    Column(
        Modifier.fillMaxWidth()
            .background(tint)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        // Initial pass, outermost node, nothing consumed: this is a
                        // tap on the wire, not a gesture handler.
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        mods.ctrl = e.keyboardModifiers.isCtrlPressed
                        mods.shift = e.keyboardModifiers.isShiftPressed
                    }
                }
            }
            .clickable(interactionSource = interaction, indication = null) {
                onClick(mods.ctrl, mods.shift)
            }
            .padding(horizontal = Space.wide, vertical = Space.tight),
        content = content,
    )
}

/** The last modifier state the pointer carried into this row. */
private class Mods {
    var ctrl = false
    var shift = false
}

/** Between rows. Inset so it separates content rather than boxing it. */
@Composable
private fun RowRule() {
    HorizontalDivider(
        Modifier.padding(start = Space.wide),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * @param count what the pane holds, shown as a muted numeral beside the title —
 *   the cheapest true fact about a list, and the one a scrollbar only implies.
 * @param selected how many rows a bulk verb would address, shown ONLY while
 *   multi-select is live. It is the one piece of state that is otherwise invisible
 *   if the selected rows have scrolled out of view.
 */
@Composable
private fun ListHeader(title: String, count: Int, selected: Int, actions: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = Space.wide, end = Space.tight, top = Space.tight, bottom = Space.tight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = DeskType.paneTitle)
            if (count > 0) Muted("$count", Modifier.padding(start = Space.unit))
            if (selected > 1) {
                Tip("$selected selected — right-click any of them to act on all $selected") {
                    Text(
                        "$selected selected",
                        style = DeskType.rowMeta,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = Space.unit),
                    )
                }
            }
        }
        Row { actions() }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Working. A dot inside the row's text flow — not a bar, not a badge. */
@Composable
fun StateDot(color: Color) {
    Box(Modifier.padding(end = Space.tight).size(Frame.dot).clip(CircleShape).background(color))
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, maxLines: Int = 1) {
    Text(
        text,
        style = DeskType.rowMeta,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Epoch SECONDS, as the daemon reports every timestamp on a list row. */
fun relTime(epochSec: Long): String {
    if (epochSec <= 0) return ""
    val s = (System.currentTimeMillis() / 1000 - epochSec).coerceAtLeast(0)
    return when {
        s < 60 -> "now"
        s < 3600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3600}h"
        else -> "${s / 86_400}d"
    }
}
