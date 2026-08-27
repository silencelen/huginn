package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.Session
import com.silencelen.huginn.desktop.ui.common.DeskType
import com.silencelen.huginn.desktop.ui.common.Space

/**
 * Ctrl+K over everything: every chat, every session, and the handful of verbs
 * worth reaching without hunting.
 *
 * The thing a desktop has that a phone does not is a keyboard, and the palette is
 * what that buys — on a phone the list IS the navigation, but here the list is
 * 300px of a 1400px window and scrolling to a session by eye is the slow path.
 *
 * Ranking and filtering live in [filterPalette] so they can be asserted; this
 * file is the frame around them.
 */
@Composable
fun CommandPalette(
    chats: List<Chat>,
    sessions: List<Session>,
    pads: List<com.silencelen.huginn.data.Scratchpad> = emptyList(),
    onPick: (PaletteItem) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(0) }
    val all = remember(chats, sessions, pads) { paletteItems(chats, sessions, pads) }
    val shown = remember(all, query) { filterPalette(all, query) }
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    // A filter that shortens the list must not leave the highlight past its end.
    LaunchedEffect(shown.size) { if (selected >= shown.size) selected = 0 }
    LaunchedEffect(selected) { if (selected in shown.indices) listState.animateScrollToItem(selected) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
            // A click anywhere off the card dismisses; the card itself swallows
            // clicks so selecting text inside does not close it.
            .clickable(indication = null, interactionSource = remember { MutableInteraction() }) { onDismiss() },
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            Modifier.padding(top = 96.dp).width(620.dp)
                .clickable(indication = null, interactionSource = remember { MutableInteraction() }) {},
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
        ) {
            Column {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it; selected = 0 },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().padding(Space.gutter)
                        .focusRequester(focus)
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionDown -> {
                                    selected = stepIndex(selected, shown.size, 1); true
                                }
                                Key.DirectionUp -> {
                                    selected = stepIndex(selected, shown.size, -1); true
                                }
                                Key.Enter, Key.NumPadEnter -> {
                                    shown.getOrNull(selected)?.let(onPick) ?: onDismiss(); true
                                }
                                Key.Escape -> { onDismiss(); true }
                                else -> false
                            }
                        },
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Muted("Find a chat or session, or type a verb")
                        }
                        inner()
                    },
                )

                if (shown.isEmpty()) {
                    Muted("Nothing matches.", Modifier.padding(16.dp))
                } else {
                    LazyColumn(state = listState, modifier = Modifier.heightIn(max = 360.dp)) {
                        itemsIndexed(shown) { i, item ->
                            PaletteRow(item, i == selected) { onPick(item) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteRow(item: PaletteItem, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            // Selection is a surface tint, never a left accent bar.
            .background(
                if (active) MaterialTheme.colorScheme.surfaceContainerHighest
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .clickable(onClick = onClick)
            // 16/4 rather than 16/8: the palette is a list you arrow through, so
            // more rows in the same 360dp is strictly better, and the row is a
            // single line of text.
            .padding(horizontal = Space.gutter, vertical = Space.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item.label,
            style = DeskType.rowTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Space.wide))
        Muted(item.detail, maxLines = 1)
    }
}

/** Compose wants an InteractionSource for an indication-less clickable. */
private fun MutableInteraction() =
    androidx.compose.foundation.interaction.MutableInteractionSource()

/**
 * The input model, shown rather than remembered — BOTH halves of it.
 *
 * It used to list keys only, which quietly said the pointer was for clicking rows.
 * On this client the pointer now carries the whole verb surface (right-click), the
 * whole state legend (hover) and multi-select (Ctrl, Shift), and none of that is
 * discoverable if nothing ever mentions it. Two columns, because they are two
 * different hands.
 */
@Composable
fun Cheatsheet(onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
            .clickable(indication = null, interactionSource = remember { MutableInteraction() }) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
            modifier = Modifier.clickable(
                indication = null,
                interactionSource = remember { MutableInteraction() },
            ) {},
        ) {
            Column(Modifier.padding(Space.section)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.section)) {
                    HelpColumn("Keyboard", SHORTCUT_HELP, keyWidth = 118.dp)
                    HelpColumn("Pointer", POINTER_HELP, keyWidth = 118.dp)
                }
                Muted("Esc or F1 closes this.", Modifier.padding(top = Space.gutter))
            }
        }
    }
}

@Composable
private fun HelpColumn(title: String, rows: List<Pair<String, String>>, keyWidth: Dp) {
    Column {
        Text(title, style = DeskType.paneTitle, color = MaterialTheme.colorScheme.onSurface)
        rows.forEach { (keys, what) ->
            Row(Modifier.padding(top = Space.unit)) {
                Text(
                    keys,
                    style = DeskType.rowMeta,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(keyWidth),
                )
                Muted(what)
            }
        }
    }
}
