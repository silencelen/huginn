package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.PanePrompt
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.desktop.SessionController
import com.silencelen.huginn.desktop.SessionTab
import com.silencelen.huginn.desktop.attach.AttachButton
import com.silencelen.huginn.desktop.attach.AttachChip
import com.silencelen.huginn.desktop.attach.AttachFilePicker
import com.silencelen.huginn.desktop.attach.AttachStatus
import com.silencelen.huginn.desktop.attach.AwtTransfer
import com.silencelen.huginn.desktop.attach.PANE_SEPARATOR
import com.silencelen.huginn.desktop.attach.appendDropped
import com.silencelen.huginn.desktop.attach.attachmentDropTarget
import com.silencelen.huginn.desktop.attach.composeMessage
import com.silencelen.huginn.desktop.attach.rememberAttachmentController
import com.silencelen.huginn.desktop.ui.session.ControlAction
import com.silencelen.huginn.desktop.ui.session.ControlPicker
import com.silencelen.huginn.desktop.ui.session.WorkPanel
import com.silencelen.huginn.desktop.ui.session.rememberModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.silencelen.huginn.data.ModelChoice
import com.silencelen.huginn.ui.LocalTranscriptMetrics
import com.silencelen.huginn.ui.FollowNewest
import com.silencelen.huginn.ui.ModelLabels
import com.silencelen.huginn.ui.NewestPill
import com.silencelen.huginn.ui.SkiaCellPainter
import com.silencelen.huginn.ui.Suggest
import com.silencelen.huginn.ui.SuggestionChips
import com.silencelen.huginn.ui.SuggestionCue
import com.silencelen.huginn.ui.TerminalCanvas
import com.silencelen.huginn.ui.TerminalGrid
import com.silencelen.huginn.ui.TranscriptGroups
import com.silencelen.huginn.ui.TranscriptRowItem
import com.silencelen.huginn.ui.scrollToNewest
import com.silencelen.huginn.ui.tailRevision
import com.silencelen.huginn.ui.theme.LocalMonoStyle

/**
 * One open session: the conversation Claude is having, and the pane it is having
 * it in.
 *
 * Two tabs over ONE controller rather than two screens, because the expensive
 * state is shared — the transcript tail, the pane poll and the size lease all
 * belong to the session, not to whichever face of it is showing. Flipping tabs
 * therefore costs nothing and, critically, does not churn the tmux size lease.
 */
@Composable
fun SessionView(store: AppStore, name: String) {
    val controller = remember(name) {
        SessionController(store.client, name, store.presence, store.paneLease, store.scope)
    }
    DisposableEffect(name) {
        controller.start()
        // Disposal is one of the four ways the lease ends. The release itself is
        // launched on the APP scope inside close(), not on this composition's —
        // a coroutine started here would be cancelled before it reached the wire.
        onDispose { controller.close() }
    }

    val tab by controller.tab.collectAsState()
    val page by controller.page.collectAsState()
    val screen by controller.screen.collectAsState()
    val gone by controller.gone.collectAsState()
    val sessions by store.sessions.collectAsState()

    // The session ended under the viewer. Nothing here can be true any more, so
    // leave rather than showing a pane that no longer exists.
    LaunchedEffect(gone) { if (gone) store.openSession(null) }

    val row = sessions.firstOrNull { it.name == name }

    // WORKING, from the hook state rather than from anything on the screen. The
    // TRANSCRIPT first and the sessions list only as a fallback: the list is polled
    // by the shell and the transcript by this view, so the transcript is the live
    // source in exactly the case the list has gone stale. On the phone a frozen
    // answer here drove the wrong composer control — a Stop button on a finished
    // session — and mis-timed the suggestions.
    val working = (page?.state ?: row?.state) == "running"

    // The composer's text lives HERE because three surfaces share it: the composer
    // types it, a suggestion chip fills it, and the interrupt control appears only
    // when it is empty.
    var draft by remember(name) { mutableStateOf("") }
    val viewScope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        SessionHeader(
            title = page?.title ?: row?.title ?: name,
            name = name,
            // The pane reports the CURRENT model and mode; the transcript only
            // knows what the last completed turn used, so a just-issued /model
            // change would otherwise leave the mark showing the old value. Both of
            // these carry a version; neither is a bare family name.
            model = screen?.liveModel ?: page?.modelDisplay ?: page?.model ?: row?.liveModel,
            effort = page?.effort,
            mode = screen?.liveMode ?: page?.permissionMode ?: row?.permissionMode,
            state = row?.state,
            branch = page?.gitBranch,
            leased = controller.leasedHere && screen?.sizeLeased == true,
            cols = screen?.width,
            rows = screen?.height,
            models = rememberModels(store.client),
            // Slash commands go in as a submitted line, exactly as typed by hand.
            onCommand = { controller.sendLine(it) },
            onCycleMode = { controller.sendKeys(listOf("BTab")) },
        )
        TabStrip(tab) { controller.openTab(it) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Weight on a plain Box, never on the scrolling container itself: handing
        // it straight to a SelectionContainer let the scroll area take the whole
        // remaining height and the composer was laid out past the bottom edge and
        // clipped away — a session with no way to type into it, and nothing logged.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                SessionTab.CONVERSATION -> ConversationTab(controller)
                SessionTab.SCREEN -> ScreenTab(controller)
            }
        }

        // THE CONVERSATION TAB'S OWN SURFACES, and deliberately not drawn on the
        // Screen tab even though the phone's equivalents are one-tab only for
        // reasons of thumb reach. Here it is arithmetic: everything below the
        // weighted Box takes height from it, the Screen tab MEASURES that box into
        // rows and columns, and reporting a new geometry resizes the owner's real
        // tmux window. A work strip that appeared and vanished every turn would walk
        // their pane through two shapes a turn. The pane also already shows its own
        // status lines, which is most of what the strip says.
        //
        // Both are CALLED on either tab and draw nothing on the Screen tab, rather
        // than being composed conditionally: their state is what makes them work —
        // how long ago work was last seen, which turn was already asked about — and
        // taking them out of the composition throws it away, so glancing at the
        // pane mid-run and coming back would lose the strip that was the reason to
        // look.
        val onConversation = tab == SessionTab.CONVERSATION
        WorkPanel(
            name = name,
            page = page,
            screen = screen,
            working = working,
            client = store.client,
            scope = viewScope,
            draw = onConversation,
        )

        // Suggested next messages, at the turn boundary only. Asked for when the
        // transcript grows while nothing is running; a live prompt outranks them,
        // typing dismisses them, and picking one FILLS the composer.
        val cue = remember(name) {
            SuggestionCue(viewScope) { store.client.sessionSuggestions(name).suggestions }
        }
        DisposableEffect(name) { onDispose { cue.clear() } }
        val suggestions by cue.suggestions.collectAsState()
        LaunchedEffect(page?.nextOffset, working) { cue.onTurnBoundary(page?.nextOffset, working) }
        if (onConversation && screen?.prompt == null && Suggest.visible(suggestions, working, draft)) {
            SuggestionChips(suggestions, onPick = { draft = it })
        }

        // THE PROMPT LIVES OUTSIDE THE TABS, which is the point: a question is the
        // one moment a reader must act, and making them find the Screen tab to
        // click "1" while reading that very question in the transcript is a tab
        // switch charged for nothing. The phone puts it in both tabs deliberately;
        // one card below both is the same promise with one copy of the code.
        screen?.prompt?.let { prompt -> PromptCard(controller, prompt) }

        Composer(
            controller = controller,
            client = store.client,
            draft = draft,
            onDraft = { draft = it },
            working = working,
            scope = viewScope,
        )
    }
}

// ------------------------------------------------------------------- chrome

/**
 * What this session IS, and — for the three facts that can be changed — the way
 * to change them.
 *
 * The model, effort and mode marks are the controls. Displaying a value beside a
 * separate control that sets it is the same verb twice, and a second bar of chips
 * under the header would also cost height, which on the Screen tab is measured
 * into rows and pushed to a real tmux window.
 */
@Composable
private fun SessionHeader(
    title: String,
    name: String,
    model: String?,
    effort: String?,
    mode: String?,
    state: String?,
    branch: String?,
    leased: Boolean,
    cols: Int?,
    rows: Int?,
    models: List<ModelChoice>,
    onCommand: (String) -> Unit,
    onCycleMode: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state) {
            "running" -> StateDot(MaterialTheme.colorScheme.primary)
            "attention" -> StateDot(MaterialTheme.colorScheme.error)
        }
        Text(title, style = MaterialTheme.typography.titleSmall)
        Muted(name, Modifier.padding(start = 10.dp))
        Spacer(Modifier.weight(1f))
        // Geometry only matters while WE are holding the window to our shape —
        // saying so is the honest way to show that this window is doing something
        // to somebody else's terminal.
        if (leased && cols != null && rows != null) {
            Muted("fitted ${cols}×${rows}", Modifier.padding(end = 10.dp))
        }
        branch?.let { Muted(it, Modifier.padding(end = 4.dp)) }
        // Permission mode has no slash command that sets it; Shift+Tab cycles it,
        // which is exactly what the key bar sends.
        ControlAction(mode?.replaceFirstChar { it.uppercase() } ?: "Mode", onClick = onCycleMode)
        ControlPicker(ModelLabels.effort(effort), ModelLabels.effortOptions()) { onCommand("/effort $it") }
        ControlPicker(ModelLabels.model(model), ModelLabels.options(models)) { onCommand("/model $it") }
    }
}

@Composable
private fun TabStrip(current: SessionTab, onSelect: (SessionTab) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        TabItem("Conversation", current == SessionTab.CONVERSATION) { onSelect(SessionTab.CONVERSATION) }
        TabItem("Screen", current == SessionTab.SCREEN) { onSelect(SessionTab.SCREEN) }
    }
}

/** Selection is weight and a surface tint. No accent bar — house rule. */
@Composable
private fun TabItem(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (active) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------- conversation

/**
 * The structured conversation, rendered by `:ui`'s transcript rows — the same code
 * that draws the phone's session view and this client's chat view. Thinking, tool
 * cards, question cards and folded subagent runs all arrive for free; this file
 * has no opinion about how any of them look.
 */
@Composable
private fun ConversationTab(controller: SessionController) {
    val page by controller.page.collectAsState()
    val error by controller.transcriptError.collectAsState()
    val neverRan by controller.neverRan.collectAsState()
    val clipboard = LocalClipboardManager.current
    val onCopy: (String) -> Unit = remember(clipboard) { { t -> clipboard.setText(AnnotatedString(t)) } }

    val current = page
    // Hoisted, because a `by`-delegated value is not smart-cast: the idiom
    // `if (error != null) { use error }` reads a property twice across a module
    // boundary and does not compile, and `!!` in code the owner runs daily is not
    // the fix.
    val note = error
    if (current == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            when {
                // A session that has never prompted Claude has no transcript. That
                // is a fact about the session, not a failure of this client, and
                // rendering it as an error made every new session look broken.
                neverRan -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No conversation yet", style = MaterialTheme.typography.titleSmall)
                    Muted(note ?: "This session has not prompted Claude.", Modifier.padding(top = 6.dp), maxLines = 3)
                }
                note != null -> Muted(note, maxLines = 4)
                else -> CircularProgressIndicator(strokeWidth = 2.dp)
            }
        }
        return
    }

    val rows = remember(current.events) { TranscriptGroups.group(current.events) }
    val keys = remember(rows) { TranscriptGroups.keys(rows) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Follow the tail — as a LATCH, not as "scroll on every change". Keyed on
    // nextOffset (bytes read, which strictly increases) rather than on the event
    // count, because the retained window is capped and the count freezes on a long
    // session. What this replaces scrolled to the last item on every revision, so a
    // reader who scrolled up to read something older was dragged back to the bottom
    // on the next poll tick — a conversation that cannot be read while it is live.
    val itemCount = rows.size + if (current.truncated) 1 else 0
    val revision = tailRevision(current.nextOffset, rows.size, current.events.lastOrNull()?.text?.length)
    val unseen = FollowNewest(listState, itemCount, revision, key = controller.name)

    // A refresh that started failing after a page landed is a banner, not a
    // replacement: the transcript already on screen is still the best thing known.
    Column(Modifier.fillMaxSize()) {
        if (note != null) {
            Muted(
                "transcript refresh failing: $note",
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 5.dp),
            )
        }
        Box(Modifier.weight(1f)) {
            // The gap between rows is a density decision the shell owns, not a
            // property of a transcript row — same seam the chat view reads.
            val metrics = LocalTranscriptMetrics.current
            SelectionContainer {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    state = listState,
                    contentPadding = PaddingValues(vertical = metrics.rowPadding),
                    verticalArrangement = Arrangement.spacedBy(metrics.rowSpacing),
                ) {
                    if (current.truncated) {
                        item("truncated") { Muted("Showing the most recent part of this session.") }
                    }
                    // KEYED on the group's own identity, not on position. The
                    // retained window drops events off the front, which shifts every
                    // index — and a LazyColumn anchors scroll by position, so the
                    // content slid under a reader who was scrolled up looking at
                    // something. `TranscriptGroups.keys` also guarantees the keys
                    // are distinct, because a duplicate key THROWS and takes the
                    // whole conversation view with it.
                    items(count = rows.size, key = { keys[it] }) { i ->
                        TranscriptRowItem(rows[i], onCopy)
                    }
                }
            }
        }
        // Scrolling back to read something older must not look like the app has
        // stopped following: without this the reader cannot tell "nothing new" from
        // "not following". The pill lands with the SAME scroll the follower uses,
        // or the latch never re-arms and the pill sticks.
        if (unseen) {
            NewestPill { scope.launch { listState.scrollToNewest(itemCount, animate = true) } }
        }
    }
}

// -------------------------------------------------------------------- screen

/**
 * The live pane as a true character grid.
 *
 * The grid WALK and the glyph blit both come from `:ui` — `TerminalCanvas` plus
 * `SkiaCellPainter`, the same run coalescing, wide-glyph centring, cursor and echo
 * clipping the phone draws with. What is left here is the desktop frame: measuring
 * the box into cells, reporting that geometry (which is what takes the lease), and
 * a keyboard.
 */
@Composable
private fun ScreenTab(controller: SessionController) {
    val screen by controller.screen.collectAsState()
    val error by controller.screenError.collectAsState()
    val scrollback by controller.scrollback.collectAsState()
    val loadingScrollback by controller.loadingScrollback.collectAsState()
    val echo by controller.echo.collectAsState()
    var live by remember(controller.name) { mutableStateOf(false) }

    val density = LocalDensity.current
    val fg = MaterialTheme.colorScheme.onSurface
    val bg = MaterialTheme.colorScheme.background
    val monoPx = with(density) { LocalMonoStyle.current.fontSize.toPx() }
    val painter = remember(monoPx) { SkiaCellPainter(monoPx) }
    val focus = remember { FocusRequester() }

    Column(Modifier.fillMaxSize()) {
        // "Blocked" means a resize is NEEDED and refused, not merely that somebody
        // is attached — so this banner cannot come back after it has been dealt
        // with. Forcing is offered, never taken: the resize would shrink a terminal
        // somebody is really looking at, and deciding that silently is not ours.
        if (screen?.resizeBlocked == true) {
            Row(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Muted(
                    "Another client is attached, so the pane is still its size. " +
                        "Resizing would shrink their window.",
                    Modifier.weight(1f),
                )
                TextButton(onClick = { controller.fitAnyway() }) { Text("Fit anyway") }
            }
        }
        error?.let {
            Muted(
                it,
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 5.dp),
            )
        }

        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().background(bg)
                .focusRequester(focus)
                .focusable()
                // Raw keys go straight to the pane, in order, through the same
                // ordered queue and the same optimistic echo the phone uses. Only
                // while LIVE is on: a stray keystroke into a running Claude session
                // is not a typo, it is an instruction.
                .onPreviewKeyEvent { e ->
                    if (!live || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (val press = TermKeys.of(e)) {
                        is TermKeys.Press.Text -> { controller.typeText(press.text); true }
                        is TermKeys.Press.Named -> { controller.sendKeys(listOf(press.name)); true }
                        null -> false
                    }
                },
        ) {
            // Measured from the box, not from the window, and BEFORE the first
            // frame arrives: the geometry rides the very first request, so the pane
            // is drawn at this window's shape from the first paint instead of
            // showing one frame of a laptop-shaped layout and then reflowing.
            //
            // Reporting this is what takes the lease. Debounced in the controller,
            // because a desktop window changes size on every frame of a drag and
            // each distinct size is a real tmux resize on someone's terminal.
            val cols = with(density) { (maxWidth.toPx() / painter.cellWidth).toInt() }
            val rows = with(density) { (maxHeight.toPx() / painter.cellHeight).toInt() }
            LaunchedEffect(cols, rows) { controller.setGeometry(cols, rows) }

            val s = screen
            if (s == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            } else {
                val grid = remember(s.lines, s.width, fg, bg) {
                    TerminalGrid.parse(s.lines, s.width, fg, bg)
                }
                val history = scrollback
                val historyGrid = remember(history, s.width, fg, bg) {
                    if (history.isNullOrEmpty()) null else TerminalGrid.parse(history, s.width, fg, bg)
                }
                val vScroll = rememberScrollState()
                val hScroll = rememberScrollState()
                // Loading history grows the content ABOVE the viewport, which would
                // leave the reader staring at old output. Land them back on the
                // live screen and let them scroll up into it.
                LaunchedEffect(historyGrid) { if (historyGrid != null) vScroll.scrollTo(vScroll.maxValue) }

                Column(Modifier.fillMaxSize().verticalScroll(vScroll).horizontalScroll(hScroll)) {
                    if (historyGrid == null) {
                        // Claude Code runs on the terminal's ALTERNATE screen, which
                        // keeps no scrollback at all — every Claude pane reports
                        // historySize 0 while a shell pane reports hundreds. So this
                        // offers nothing rather than a button that does nothing, and
                        // says where the history actually is.
                        if (s.historySize > 0) {
                            TextButton(
                                onClick = { controller.loadScrollback() },
                                enabled = !loadingScrollback,
                            ) {
                                Text(
                                    if (loadingScrollback) "Loading…"
                                    else "Load earlier output (${s.historySize} lines)",
                                )
                            }
                        } else {
                            Muted(
                                if (s.altScreen)
                                    "This pane keeps no scrollback: a full-screen program only has the " +
                                        "screen you see. The Conversation tab has the whole session."
                                else "Nothing scrolled off this pane yet.",
                                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                maxLines = 3,
                            )
                        }
                    } else {
                        TerminalCanvas(
                            grid = historyGrid,
                            painter = painter,
                            cursor = null,
                            cursorColor = MaterialTheme.colorScheme.primary,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    TerminalCanvas(
                        grid = grid,
                        painter = painter,
                        cursor = s.cursorX to s.cursorY,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        echo = if (live && echo.visible) echo.text else "",
                    )
                }
            }
        }

        KeyBar(controller, live) {
            live = !live
            if (live) focus.requestFocus()
        }
    }
}

/**
 * The keys a TUI needs that a composer cannot express, plus the live toggle.
 *
 * The toggle is FIRST because it changes what the whole keyboard means, and it is
 * per-visit rather than persisted: it is a way of leaning in, not a configuration.
 */
@Composable
private fun KeyBar(controller: SessionController, live: Boolean, onToggleLive: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (live) {
            Button(onClick = onToggleLive, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Text("Live", style = MaterialTheme.typography.labelMedium)
            }
        } else {
            OutlinedButton(onClick = onToggleLive, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Text("Live", style = MaterialTheme.typography.labelMedium)
            }
        }
        // The phone's set, less the ones a desktop keyboard already sends itself
        // while live. Shift+Tab is BTab and it is what cycles Claude Code's
        // permission mode — leaving it out means the owner can only cycle forwards.
        listOf(
            "Esc" to "Escape", "Tab" to "Tab", "⇧Tab" to "BTab", "↑" to "Up", "↓" to "Down",
            "←" to "Left", "→" to "Right", "⏎" to "Enter",
            "^C" to "C-c", "^D" to "C-d", "^L" to "C-l", "^R" to "C-r",
            "PgUp" to "PPage", "PgDn" to "NPage",
        ).forEach { (label, keyName) ->
            OutlinedButton(
                onClick = { controller.sendKeys(listOf(keyName)) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            ) { Text(label, style = MaterialTheme.typography.labelMedium) }
        }
        if (live) Muted("typing goes straight to the pane", Modifier.padding(start = 6.dp))
    }
}

// -------------------------------------------------------------------- prompt

/**
 * A detected choice prompt, as buttons.
 *
 * Every answer carries the fingerprint the host published with the question — the
 * host refuses an answer whose pane has moved on, and it has to, because this card
 * renders a polled screen that may be seconds old. A refusal comes back as a 409
 * with the daemon's own sentence: an ORDINARY outcome, since the click was right
 * when it was offered. It is reported and never retried.
 */
@Composable
private fun PromptCard(controller: SessionController, prompt: PanePrompt) {
    val answering by controller.answering.collectAsState()
    val note by controller.answerNote.collectAsState()

    // Seeded from what the dialog already shows, so a question half-answered in
    // tmux is not silently discarded. Keyed on the question and the option count
    // rather than on the whole prompt: re-seeding when only the checkbox states
    // move under the poll would stomp a selection being made right now.
    val chosen = remember(prompt.question, prompt.options.size) {
        mutableStateListOf<Int>().apply {
            prompt.options.filter { it.checked == true }.forEach { add(it.number) }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(prompt.question, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            prompt.options.forEach { option ->
                val checkable = prompt.multiSelect && option.checked != null
                if (checkable) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = !answering) {
                                if (chosen.contains(option.number)) chosen.remove(option.number)
                                else chosen.add(option.number)
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = chosen.contains(option.number), onCheckedChange = null)
                        Text(
                            "${option.number}.  ${option.label}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                } else {
                    // Single answer: one click IS the answer, no confirm step.
                    val highlighted = option.selected && !prompt.multiSelect
                    val label = "${option.number}.  ${option.label}"
                    val mod = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    if (highlighted) {
                        Button(onClick = { controller.answer(option.number) }, enabled = !answering, modifier = mod) {
                            Text(label)
                        }
                    } else {
                        OutlinedButton(onClick = { controller.answer(option.number) }, enabled = !answering, modifier = mod) {
                            Text(label)
                        }
                    }
                }
            }
            if (prompt.multiSelect) {
                Button(
                    onClick = { controller.answerMulti(chosen.toList()) },
                    enabled = !answering,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                ) {
                    Text(
                        if (chosen.isEmpty()) "Answer with none selected"
                        else "Answer with ${chosen.size} selected",
                    )
                }
            }
            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ composer

/**
 * A whole line at a time. Text and Enter travel in ONE request so nothing can
 * interleave between them, which is the difference between sending a message and
 * sending half a message and then a newline into whatever the pane became.
 *
 * Attachments work here too, and they are arguably more useful than in a chat: a
 * session is where the long-running work happens. The marker is joined with a
 * SPACE rather than a paragraph break — this text is TYPED into a pane, where a
 * newline is the submit key, so a blank line would send the message and leave the
 * marker on the next prompt.
 *
 * The draft is HOISTED: a suggestion chip fills it, and the interrupt control
 * appears only while it is empty.
 */
@Composable
private fun Composer(
    controller: SessionController,
    client: HuginnClient,
    draft: String,
    onDraft: (String) -> Unit,
    working: Boolean,
    scope: CoroutineScope,
) {
    val attachments = rememberAttachmentController(client, scope, controller.name)
    val attachment by attachments.current.collectAsState()
    val failure by attachments.failure.collectAsState()
    var picking by remember { mutableStateOf(false) }
    var dragOver by remember { mutableStateOf(false) }

    val pending = attachment
    val canSend = draft.isNotBlank() || (pending != null && pending.status != AttachStatus.FAILED)

    val submit: () -> Unit = {
        if (canSend) {
            val body = draft
            onDraft("")
            scope.launch {
                val marker = attachments.take()
                val full = composeMessage(body, marker, PANE_SEPARATOR)
                if (full.isNotEmpty()) controller.sendLine(full)
            }
        }
    }

    AttachFilePicker(picking) { file ->
        picking = false
        if (file != null) attachments.attachFile(file)
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(
        Modifier.fillMaxWidth()
            .attachmentDropTarget(
                controller = attachments,
                onText = { onDraft(appendDropped(draft, it)) },
                onDragOver = { dragOver = it },
            )
            .background(
                if (dragOver) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.background
            )
            .padding(12.dp),
    ) {
        pending?.let {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                AttachChip(it) { attachments.clear() }
            }
        }
        failure?.let {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { attachments.dismissFailure() }) { Text("dismiss") }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AttachButton { picking = true }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                // Cap before fill. `fillMaxWidth` hands DOWN fixed constraints and a
                // `widthIn` inside those can only coerce into them, so the cap would be
                // swallowed and a composer meant to stop at a reading measure would
                // span the whole window.
                modifier = Modifier.widthIn(max = 900.dp).weight(1f)
                    .heightIn(min = 56.dp, max = 160.dp)
                    // Ctrl+Enter sends; plain Enter is a newline. The opposite binding
                    // sends half-written instructions into a live agent session.
                    .onPreviewKeyEvent { e ->
                        when {
                            e.type != KeyEventType.KeyDown -> false
                            e.isCtrlPressed && e.key == Key.Enter -> { submit(); true }
                            e.isCtrlPressed && e.key == Key.V -> AwtTransfer.consumeClipboard(attachments)
                            else -> false
                        }
                    },
                placeholder = { Text("Send to the pane…  (Ctrl+Enter · paste, drop or clip a file)") },
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            // Esc is how you stop Claude at the keyboard, so with nothing typed
            // that is the action this composer should offer — and only then, since
            // a Stop sitting beside half a written instruction is a keystroke away
            // from throwing the instruction away.
            if (working && draft.isBlank()) {
                OutlinedButton(onClick = { controller.sendKeys(listOf("Escape")) }) {
                    Text("Interrupt", color = MaterialTheme.colorScheme.error)
                }
            }
            Button(onClick = submit, enabled = canSend) { Text("Send") }
        }
    }
}
