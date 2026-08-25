package com.silencelen.huginn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silencelen.huginn.data.Screen

/**
 * The live pane. Not a terminal emulator (there is no PTY here), but a real
 * character grid: the screen is parsed into cells and drawn at exact cell
 * metrics, and the phone reports the geometry it can actually display so the
 * server can resize the tmux window to match. That last part is the difference
 * between reading a laptop-shaped layout through a keyhole and reading a layout
 * drawn for this screen.
 */
@Composable
fun TerminalScreen(
    session: String,
    screen: Screen?,
    scrollback: List<String>,
    loadingScrollback: Boolean,
    onLoadScrollback: () -> Unit,
    draft: String,
    onDraft: (String) -> Unit,
    fontScale: Float,
    onFontScale: (Float) -> Unit,
    onGeometry: (Int, Int) -> Unit,
    onSendText: (String, Boolean) -> Unit,
    onSendKeys: (List<String>) -> Unit,
    onLive: (LiveInput.Op) -> Unit,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    onAnswerPrompt: (Int) -> Unit,
    onAnswerMulti: (List<Int>) -> Unit,
    onForceResize: () -> Unit,
) {
    val density = LocalDensity.current
    val fg = MaterialTheme.colorScheme.onSurface
    val bg = MaterialTheme.colorScheme.background
    // Live mode: the soft keyboard types straight into the pane, keystroke by
    // keystroke, instead of composing in the bubble and sending. Per-visit rather
    // than persisted: it is a way of leaning in, not a configuration.
    var liveTyping by rememberSaveable(session) { mutableStateOf(false) }
    // Optimistic echo state for live typing; rules live in LocalEcho (pure).
    var echo by remember(session) { mutableStateOf(LocalEcho.Echo()) }
    var prevCursor by remember(session) { mutableStateOf<Pair<Int, Int>?>(null) }
    // Each authoritative frame consumes what its cursor movement explains.
    LaunchedEffect(screen?.hash) {
        val s2 = screen ?: return@LaunchedEffect
        val cur = s2.cursorX to s2.cursorY
        echo = LocalEcho.frame(echo, prevCursor, cur)
        prevCursor = cur
    }
    // The glyph blit for this platform. The GRID WALK — runs, backgrounds,
    // underlines, cursor, echo — lives in :ui and is the same code the desktop
    // client draws with; only the blit is per-platform, and it is passed in
    // rather than chosen by an expect/actual.
    val painter = remember(fontScale, density) {
        AndroidCellPainter(with(density) { fontScale.sp.toPx() })
    }

    Column(Modifier.fillMaxSize()) {
        if (screen?.resizeBlocked == true) {
            AttachedBanner(screen.attachedClients, onForceResize)
        }

        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(bg)
                // Pinch to zoom. Fewer columns at a bigger size is a real trade a
                // reader wants to make per situation, and because the geometry is
                // reported upstream the pane re-wraps instead of clipping.
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom != 1f) onFontScale((fontScale * zoom).coerceIn(5.5f, 22f))
                    }
                }
        ) {
            val cols = with(density) { (maxWidth.toPx() / painter.cellWidth).toInt() }.coerceIn(20, 300)
            val rows = with(density) { (maxHeight.toPx() / painter.cellHeight).toInt() }.coerceIn(10, 200)
            LaunchedEffect(cols, rows) { onGeometry(cols, rows) }

        // ⚠⚠ THE PROMPT CARD IS AN OVERLAY, NOT A SIBLING, AND MUST STAY ONE.
        // The box it floats over takes weight(1f) and MEASURES ITSELF INTO TMUX
        // ROWS. As a sibling this card cost ~16 rows the moment Claude asked a
        // question and gave them back when it was answered — a real resize of the
        // owner's terminal, twice per question, seen by every attached client. The
        // pane re-wrapped under the reader exactly while they were trying to read
        // the thing being asked. Overlaying costs nothing and keeps the promise
        // that a question is always answerable from here.
            //
            // A detected choice prompt becomes buttons. This is the single biggest
            // phone win over a terminal: answering "1/2/3" without hunting for
            // digits on a soft keyboard while the TUI redraws under your thumb.
            screen?.prompt?.let { prompt ->
                Box(Modifier.align(Alignment.BottomCenter).padding(8.dp)) {
                    PromptCard(prompt, onAnswerPrompt, onAnswerMulti)
                }
            }

            if (screen == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            } else {
                val hScroll = rememberScrollState()
                val vScroll = rememberScrollState()
                val grid = remember(screen.lines, screen.width, fg, bg) {
                    TerminalGrid.parse(screen.lines, screen.width, fg, bg)
                }
                val historyGrid = remember(scrollback, screen.width, fg, bg) {
                    if (scrollback.isEmpty()) null
                    else TerminalGrid.parse(scrollback, screen.width, fg, bg)
                }
                // Loading history grows the content ABOVE the viewport, which would
                // otherwise leave the reader looking at old output; land them back
                // on the live screen and let them scroll up into the history.
                LaunchedEffect(scrollback.size) {
                    if (scrollback.isNotEmpty()) vScroll.scrollTo(vScroll.maxValue)
                }
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(vScroll)
                        .horizontalScroll(hScroll)
                ) {
                    if (historyGrid == null) {
                        // A full-screen program (which Claude Code is) runs on the
                        // terminal's ALTERNATE screen, and that has no scrollback
                        // at all — verified: every Claude pane reports
                        // historySize 0, while a shell pane reports hundreds. So
                        // offering to load history here would be a button that
                        // silently does nothing; say where the history actually is.
                        if (screen.historySize > 0) {
                            LoadEarlierRow(loading = loadingScrollback, onClick = onLoadScrollback)
                        } else {
                            NoScrollbackNote(altScreen = screen.altScreen)
                        }
                    } else {
                        TerminalCanvas(
                            grid = historyGrid,
                            painter = painter,
                            cursor = null,
                            cursorColor = MaterialTheme.colorScheme.primary,
                        )
                        // Marks where history ends and the live pane begins, which
                        // is otherwise invisible: both are the same terminal text.
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    TerminalCanvas(
                        grid = grid,
                        painter = painter,
                        cursor = screen.cursorX to screen.cursorY,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        echo = if (liveTyping && echo.visible) echo.text else "",
                    )
                }
            }
        }

        KeyRow(onSendKeys, liveTyping, onToggleLive = { liveTyping = !liveTyping })

        LiveKeyboardField(
            active = liveTyping,
            // Through the ordered queue, not the fire-and-forget path: per-keystroke
            // coroutines can reorder characters in flight, and a queue that merges a
            // burst into one request is also what makes typing feel immediate.
            // Each keystroke ALSO feeds the local echo, which is what makes it
            // feel instant: render now, reconcile when the pane confirms.
            onText = {
                echo = LocalEcho.typed(echo, it)
                onLive(LiveInput.Op.Text(it))
            },
            onKeys = { keys ->
                echo = if (keys.all { k -> k == "BSpace" }) {
                    keys.fold(echo) { acc, _ -> LocalEcho.backspace(acc) }
                } else LocalEcho.otherKey(echo)
                onLive(LiveInput.Op.Key(keys))
            },
        )

        if (liveTyping) {
            // A slim strip in place of the composer: says what is happening, and is
            // the tap target that brings the keyboard back if it was dismissed.
            Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { liveTyping = false }
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Keyboard,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Typing straight into $session — every key goes to the pane",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Done",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        } else Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraft,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type into $session") },
                    maxLines = 5,
                    shape = RoundedCornerShape(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                DictationMicButton(
                    micGranted = micGranted,
                    onRequestMic = onRequestMic,
                    onText = { heard -> onDraft(appendDictation(draft, heard)) },
                )
                // Send-without-newline: Claude Code's composer takes multi-line
                // input, so putting text in the box is a distinct action from
                // submitting it.
                IconButton(
                    onClick = { if (draft.isNotEmpty()) onSendText(draft, false) },
                    enabled = draft.isNotEmpty(),
                ) {
                    Text("↦", style = MaterialTheme.typography.titleLarge)
                }
                IconButton(
                    onClick = {
                        if (draft.isNotEmpty()) onSendText(draft, true) else onSendKeys(listOf("Enter"))
                    },
                    modifier = Modifier
                        .size(46.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(23.dp)),
                ) {
                    Icon(
                        Icons.Filled.KeyboardReturn,
                        contentDescription = "Send and press Enter",
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadEarlierRow(loading: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        AssistChip(
            onClick = { if (!loading) onClick() },
            label = { Text(if (loading) "Loading…" else "Load earlier output") },
        )
    }
}

@Composable
private fun NoScrollbackNote(altScreen: Boolean) {
    Text(
        if (altScreen) "This pane keeps no scrollback: a full-screen program only has the " +
            "screen you see. The Conversation tab has the whole session."
        else "Nothing scrolled off this pane yet.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun AttachedBanner(clients: Int, onForce: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Another client is attached, so the pane is still its size. Resizing would shrink their window.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Fit anyway",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onForce)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun PromptCard(
    prompt: com.silencelen.huginn.data.PanePrompt,
    onAnswer: (Int) -> Unit,
    onAnswerMulti: (List<Int>) -> Unit,
) {
    // Local checkbox state, seeded from what the dialog already shows — the
    // question may have been half-answered in tmux, and starting from blank
    // would silently discard those choices. The host receives the full desired
    // set and diffs, so both sides agree about what "these ones" means.
    val chosen = remember(prompt.question, prompt.options.size) {
        androidx.compose.runtime.mutableStateListOf<Int>().apply {
            prompt.options.filter { it.checked == true }.forEach { add(it.number) }
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                prompt.question.ifBlank { "Claude is asking" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                prompt.options.forEach { o ->
                    if (prompt.multiSelect && o.checked != null) {
                        // A checkbox row: toggling is local; nothing reaches the
                        // pane until Answer, so half-formed sets are never typed.
                        val on = chosen.contains(o.number)
                        Surface(
                            onClick = { if (on) chosen.remove(o.number) else chosen.add(o.number) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (on) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.Checkbox(
                                    checked = on,
                                    onCheckedChange = { if (on) chosen.remove(o.number) else chosen.add(o.number) },
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(o.label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    } else {
                        Button(
                            onClick = { onAnswer(o.number) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = if (o.selected && !prompt.multiSelect) ButtonDefaults.buttonColors()
                            else ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        ) {
                            Text(
                                "${o.number}.  ${o.label}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                if (prompt.multiSelect) {
                    Button(
                        onClick = { onAnswerMulti(chosen.toList().sorted()) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            if (chosen.isEmpty()) "Answer with none selected"
                            else "Answer with ${chosen.size} selected",
                        )
                    }
                }
            }
        }
    }
}

/** The keys a phone keyboard cannot send, plus the live-typing toggle. */
@Composable
private fun KeyRow(
    onSendKeys: (List<String>) -> Unit,
    liveTyping: Boolean = false,
    onToggleLive: (() -> Unit)? = null,
) {
    val hScroll = rememberScrollState()
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(hScroll)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onToggleLive?.let { toggle ->
                // First, because it changes what the whole keyboard means.
                AssistChip(
                    onClick = toggle,
                    label = {
                        Icon(
                            Icons.Filled.Keyboard,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = if (liveTyping) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Live",
                            fontSize = 12.sp,
                            color = if (liveTyping) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
            KeyChip("Esc") { onSendKeys(listOf("Escape")) }
            KeyChip("⇧Tab") { onSendKeys(listOf("BTab")) }
            KeyChip("Tab") { onSendKeys(listOf("Tab")) }
            IconKeyChip(Icons.Filled.ArrowUpward, "Up") { onSendKeys(listOf("Up")) }
            IconKeyChip(Icons.Filled.ArrowDownward, "Down") { onSendKeys(listOf("Down")) }
            IconKeyChip(Icons.Filled.KeyboardArrowLeft, "Left") { onSendKeys(listOf("Left")) }
            IconKeyChip(Icons.Filled.KeyboardArrowRight, "Right") { onSendKeys(listOf("Right")) }
            KeyChip("^C") { onSendKeys(listOf("C-c")) }
            KeyChip("^D") { onSendKeys(listOf("C-d")) }
            KeyChip("^L") { onSendKeys(listOf("C-l")) }
            KeyChip("^R") { onSendKeys(listOf("C-r")) }
            KeyChip("PgUp") { onSendKeys(listOf("PPage")) }
            KeyChip("PgDn") { onSendKeys(listOf("NPage")) }
        }
    }
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
    )
}

@Composable
private fun IconKeyChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = { Icon(icon, contentDescription = desc, modifier = Modifier.size(16.dp)) },
    )
}
