package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.DraftBook
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.desktop.View
import com.silencelen.huginn.ui.ScratchpadEditorView
import com.silencelen.huginn.ui.ScratchpadListView
import com.silencelen.huginn.ui.SendTargetPanel
import kotlinx.coroutines.launch

/**
 * The desktop's two homes for a page.
 *
 * The RAIL VIEW is the full one — every page on the left, the editor on the
 * right — and the PANEL is the same editor 360dp wide beside a conversation,
 * which is what the owner actually asked for: the notes and the thing they are
 * about, on screen together, with one button to move text from one to the other.
 *
 * Both draw the SHARED editor. The only thing this file owns is which page is
 * open, where "send" goes, and how wide the panel is.
 */

/** Where a page is being sent when the panel already knows the answer. */
sealed interface PadTarget {
    val draftKey: String
    val label: String

    data class Chat(val id: String) : PadTarget {
        override val draftKey: String get() = DraftBook.chatKey(id)
        override val label: String get() = "Send to this chat"
    }

    data class Session(val name: String) : PadTarget {
        override val draftKey: String get() = DraftBook.sessionKey(name)
        override val label: String get() = "Send to this session"
    }
}

/** The rail view's list half. */
@Composable
fun ScratchpadsList(store: AppStore) {
    val pads by store.pads.collectAsState()
    val open by store.padSaver.pad.collectAsState()
    val scope = rememberCoroutineScope()
    ScratchpadListView(
        pads = pads,
        selectedId = open?.id,
        nowMs = System.currentTimeMillis(),
        onOpen = { p -> scope.launch { store.openPad(p.id) } },
        onCreate = { name -> scope.launch { store.createPad(name) } },
        onDelete = { p -> scope.launch { store.deletePad(p.id) } },
    )
}

/** The rail view's editor half. Sends go through the general picker. */
@Composable
fun ScratchpadsDetail(store: AppStore) {
    val pads by store.pads.collectAsState()
    val scope = rememberCoroutineScope()
    // Opening the view opens SOMETHING, so the right half is never a blank pane
    // beside a list of pages that clearly exist.
    LaunchedEffect(pads.size) {
        if (store.padSaver.pad.value == null) pads.firstOrNull { it.main }?.let { store.openPad(it.id) }
    }
    PadEditor(store = store, target = null, modifier = Modifier.fillMaxSize())
}

/**
 * The side panel: the editor beside the conversation, at a fixed width.
 *
 * Fixed rather than draggable on purpose — there is already one seam in this
 * window and a second one is a second thing to get wrong. 360dp is a readable
 * measure for plain text and leaves a 1280pt window a conversation column that is
 * still a conversation.
 */
@Composable
fun ScratchpadSidePanel(store: AppStore, target: PadTarget) {
    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Box(Modifier.width(PANEL_WIDTH).fillMaxHeight()) {
        PadEditor(store = store, target = target, modifier = Modifier.fillMaxSize())
    }
}

/** Below this the panel would leave no conversation worth reading beside it. */
const val PANEL_MIN_WINDOW_DP: Int = 900

val PANEL_WIDTH = 360.dp

// ------------------------------------------------------- is it on screen at all
//
// ⚠ THREE CONDITIONS DECIDE WHETHER THE PANEL IS DRAWN, and for a while only ONE
// of them decided whether Esc closed it. `store.padPanel` is a flag that says
// "the reader would like the panel"; it can be true in Settings, true against a
// daemon that has no pages, and true in a window too narrow to hold it. Esc read
// that flag alone, so in all three cases Escape silently closed a panel that was
// not there instead of leaving the conversation — the key doing nothing visible
// is the worst kind of broken, because the reader presses it again.
//
// So the rule lives here, next to the number it depends on, and the keyboard and
// the render sites ask the same question rather than each keeping their own half.

/**
 * Whether this window has anywhere to PUT the panel: the daemon has pages, and
 * the reader is in a conversation (a chat or a session), which is the only place
 * the panel is drawn beside anything.
 */
fun padPanelHasHome(
    view: View,
    chatOpen: Boolean,
    sessionOpen: Boolean,
    padsAvailable: Boolean?,
): Boolean = padsAvailable == true && when (view) {
    View.CHATS -> chatOpen
    View.SESSIONS -> sessionOpen
    else -> false
}

/**
 * …and whether the pane it would come out of is wide enough to still be a
 * conversation afterwards.
 *
 * @param detailWidthDp the width of the pane the panel takes its 360dp from — NOT
 *   the window: the rail and the list pane are already spoken for.
 */
fun padPanelFits(detailWidthDp: Float): Boolean = detailWidthDp >= PANEL_MIN_WINDOW_DP

/** All three: what the render sites draw, and the only thing Esc may close. */
fun padPanelShowing(
    open: Boolean,
    view: View,
    chatOpen: Boolean,
    sessionOpen: Boolean,
    padsAvailable: Boolean?,
    detailWidthDp: Float,
): Boolean = open && padPanelHasHome(view, chatOpen, sessionOpen, padsAvailable) && padPanelFits(detailWidthDp)

@Composable
private fun PadEditor(store: AppStore, target: PadTarget?, modifier: Modifier) {
    val pads by store.pads.collectAsState()
    val pad by store.padSaver.pad.collectAsState()
    val state by store.padSaver.state.collectAsState()
    val note by store.padSaver.note.collectAsState()
    val sessions by store.sessions.collectAsState()
    val chats by store.chats.collectAsState()
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }

    // The write that is still in the air lands as the editor goes, on the STORE's
    // scope — this composition's is cancelled at the same instant.
    DisposableEffect(Unit) { onDispose { store.padSaver.flush() } }

    val text = pad?.content.orEmpty()

    Column(modifier) {
        ScratchpadEditorView(
            pad = pad,
            pads = pads,
            state = state,
            note = note,
            onEdit = { store.padSaver.set(it) },
            onSwitch = { p -> scope.launch { store.openPad(p.id) } },
            onDismissNote = { store.padSaver.clearNote() },
            modifier = Modifier.fillMaxSize(),
            sendHereLabel = target?.label ?: "",
            // The direct send is offered only where the destination is already
            // known. In the rail view it is not, so there is one button and it
            // asks — rather than a button that means something different
            // depending on which surface it is drawn on.
            // An EMPTY page offers neither: a button that does nothing when
            // pressed is a broken button as far as the person pressing it can
            // tell, and "nothing happened" is the least useful thing to say
            // about a page with nothing on it.
            onSendHere = target?.takeIf { text.isNotBlank() }?.let { t ->
                { store.stagePadInDraft(t.draftKey, text) }
            },
            onSendElsewhere = if (text.isNotBlank()) ({ picking = true }) else null,
            onRename = { name -> scope.launch { store.renamePad(pad?.id ?: return@launch, name) } },
        )
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = {},
            text = {
                SendTargetPanel(
                    sessions = sessions,
                    chats = chats,
                    title = "Send this page to",
                    // No "new chat" row: a page is being sent from somewhere, and
                    // minting an empty conversation on the host to receive it is a
                    // side effect nobody asked the picker for.
                    onNewChat = null,
                    onSession = { name ->
                        picking = false
                        store.stagePadInDraft(DraftBook.sessionKey(name), text)
                        store.openSession(name)
                        store.openView(View.SESSIONS)
                    },
                    onChat = { id ->
                        picking = false
                        store.stagePadInDraft(DraftBook.chatKey(id), text)
                        store.openChat(id)
                        store.openView(View.CHATS)
                    },
                )
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        )
    }
}
