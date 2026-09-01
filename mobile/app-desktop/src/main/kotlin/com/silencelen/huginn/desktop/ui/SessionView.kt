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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import com.silencelen.huginn.data.DraftBook
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.desktop.SessionController
import com.silencelen.huginn.desktop.SessionTab
import com.silencelen.huginn.desktop.face
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
import com.silencelen.huginn.desktop.ui.common.DeskType
import com.silencelen.huginn.desktop.ui.common.Space
import com.silencelen.huginn.desktop.ui.common.Tip
import com.silencelen.huginn.desktop.ui.session.ControlAction
import com.silencelen.huginn.desktop.ui.session.ControlPicker
import com.silencelen.huginn.desktop.ui.session.WorkPanel
import com.silencelen.huginn.desktop.ui.session.rememberModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import com.silencelen.huginn.data.ModelChoice
import com.silencelen.huginn.ui.CompactingChip
import com.silencelen.huginn.ui.OverviewDensity
import com.silencelen.huginn.ui.SessionOverviewView
import com.silencelen.huginn.ui.ContextMeter
import com.silencelen.huginn.ui.DegradedAskCard
import com.silencelen.huginn.ui.HistoryWalk
import com.silencelen.huginn.ui.exitRecallIfDiverged
import com.silencelen.huginn.ui.handleHistoryKey
import com.silencelen.huginn.ui.LocalTranscriptMetrics
import com.silencelen.huginn.ui.FollowNewest
import com.silencelen.huginn.ui.ModelLabels
import com.silencelen.huginn.ui.NewestPill
import com.silencelen.huginn.ui.onScrollInput
import com.silencelen.huginn.ui.PlanApprovalCard
import com.silencelen.huginn.ui.PromptCard
import com.silencelen.huginn.ui.PromptGate
import com.silencelen.huginn.ui.asMultiPartSteer
import com.silencelen.huginn.ui.ScratchpadRefBadge
import com.silencelen.huginn.ui.ScratchpadRules
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
        SessionController(store.client, name, store.presence, store.paneLease, store.metaSaver, store.scope)
    }
    val draftKey = DraftBook.sessionKey(name)
    DisposableEffect(name) {
        controller.start()
        // Disposal is one of the four ways the lease ends. The release itself is
        // launched on the APP scope inside close(), not on this composition's —
        // a coroutine started here would be cancelled before it reached the wire.
        onDispose {
            controller.close()
            // Leaving is exactly when a debounced draft write is still in the air
            // and this composition's scope is being cancelled. The book belongs to
            // the app for that reason; this is the call that lands it.
            store.drafts.flush()
        }
    }

    val tab by controller.tab.collectAsState()
    val page by controller.page.collectAsState()
    val screen by controller.screen.collectAsState()
    val gone by controller.gone.collectAsState()
    val sessions by store.sessions.collectAsState()

    // The session ended under the viewer. Nothing here can be true any more, so
    // leave rather than showing a pane that no longer exists. Its draft goes with
    // it: the map is rewritten whole on every save, so an orphan is paid for on
    // every keystroke in every other target, forever.
    LaunchedEffect(gone) {
        if (gone) {
            store.drafts.clear(draftKey)
            store.openSession(null)
        }
    }

    val row = sessions.firstOrNull { it.name == name }

    // WORKING, from the hook state rather than from anything on the screen. The
    // TRANSCRIPT first and the sessions list only as a fallback: the list is polled
    // by the shell and the transcript by this view, so the transcript is the live
    // source in exactly the case the list has gone stale. On the phone a frozen
    // answer here drove the wrong composer control — a Stop button on a finished
    // session — and mis-timed the suggestions.
    val working = (page?.state ?: row?.state) == "running"

    // THE COMPOSER'S TEXT COMES FROM THE APP'S DRAFT BOOK, not from a `remember`.
    //
    // It used to be `remember(name) { mutableStateOf("") }`, and that was the
    // owner's "drafts get deleted between session or chat navigations": a
    // composition-local value is discarded the moment this view leaves the
    // composition — which is every switch to another session, and every click on
    // Chats, Status or Settings. It never reached disk either, so the phone's
    // session drafts and this client's were not the same drafts at all despite
    // [DraftBook.sessionKey] existing in :core precisely to make them one.
    //
    // Three surfaces still share the value, which is why it is read here rather
    // than inside the composer: the composer types it, a suggestion chip fills it,
    // and the interrupt control appears only while it is empty.
    val draftMap by store.drafts.drafts.collectAsState()
    val draft = draftMap[draftKey].orEmpty()
    val setDraft: (String) -> Unit = { store.drafts.set(draftKey, it) }
    val historyMap by store.sentHistory.entries.collectAsState()
    val history = historyMap[draftKey].orEmpty()
    val viewScope = rememberCoroutineScope()

    val pads by store.pads.collectAsState()
    val padsAvailable by store.padsAvailable.collectAsState()
    val padPanel by store.padPanel.collectAsState()
    val padRefs by store.padRefs.collectAsState()
    val padRefKey = ScratchpadRules.sessionRefKey(name)

    BoxWithConstraints(Modifier.fillMaxSize()) {
    // A panel that leaves no pane worth reading beside it is not a panel. The
    // Screen tab measures the column it sits in into real tmux rows and columns,
    // which is the other half of the reason this has a floor at all.
    val panelFits = maxWidth >= PANEL_MIN_WINDOW_DP.dp
    val showPanel = padPanel && panelFits && padsAvailable == true
    Row(Modifier.fillMaxSize()) {
    Column(Modifier.weight(1f).fillMaxHeight()) {
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
            // Live pane first (it moves every poll); the list row is the fallback
            // for the instant before the first screen arrives.
            contextPercent = screen?.contextPercent ?: row?.contextPercent,
            compacting = screen?.compacting ?: row?.compacting ?: false,
            leased = controller.leasedHere && screen?.sizeLeased == true,
            cols = screen?.width,
            rows = screen?.height,
            models = rememberModels(store.client),
            // Slash commands go in as a submitted line, exactly as typed by hand.
            onCommand = { controller.sendLine(it) },
            onCycleMode = { controller.sendKeys(listOf("BTab")) },
        )
        val paneClipboard = LocalClipboardManager.current
        val paneCopy: (String) -> Unit = remember(paneClipboard) {
            { t -> paneClipboard.setText(AnnotatedString(t)) }
        }
        TabStrip(tab, { controller.openTab(it) }) {
            // Only on the Screen tab, and only when the pane holds something. The
            // conversation has its own selection and needs none of this.
            if (tab == SessionTab.SCREEN && com.silencelen.huginn.ui.hasCopyableText(screen)) {
                val paneLinks = com.silencelen.huginn.ui.linksOn(screen)
                when {
                    paneLinks.size == 1 ->
                        TextButton(onClick = { paneCopy(paneLinks[0]) }) { Text("Copy link") }
                    paneLinks.size > 1 ->
                        TextButton(onClick = { paneCopy(paneLinks.joinToString("\n")) }) {
                            Text("Copy ${paneLinks.size} links")
                        }
                }
                TextButton(onClick = { paneCopy(com.silencelen.huginn.ui.screenText(screen)) }) { Text("Copy screen") }
            }
            // The panel toggle lives HERE for the same reason the copy buttons do:
            // this row is above the weighted box the Screen tab measures into tmux
            // rows, and anything drawn inside that box resizes somebody's terminal.
            if (padsAvailable == true && panelFits) {
                TextButton(onClick = { store.togglePadPanel() }) {
                    Text(if (padPanel) "Hide pages" else "Pages")
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Weight on a plain Box, never on the scrolling container itself: handing
        // it straight to a SelectionContainer let the scroll area take the whole
        // remaining height and the composer was laid out past the bottom edge and
        // clipped away — a session with no way to type into it, and nothing logged.
        val answering by controller.answering.collectAsState()
        val answerNote by controller.answerNote.collectAsState()

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                SessionTab.CONVERSATION -> ConversationTab(controller)
                SessionTab.SCREEN -> ScreenTab(controller)
                SessionTab.OVERVIEW -> OverviewTab(controller, store)
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
            SuggestionChips(suggestions, onPick = setDraft, modifier = rememberEdgeFade())
        }

        // THE PROMPT LIVES OUTSIDE THE TABS — and on every face but one.
        //
        // OUTSIDE THE TABS, because a question is the one moment a reader must
        // act, and making them find the Screen tab to click "1" while reading that
        // very question in the transcript is a tab switch charged for nothing. One
        // card below the tab body is that promise with one copy of the code.
        //
        // NOT ON THE SCREEN FACE, which is not in tension with the above: there the
        // tab switch has already happened, and the terminal below IS the dialog —
        // drawn by Claude Code itself, with every part of a multi-part question
        // steppable in a way a row of buttons cannot drive. The steering card
        // ("Answer on the Screen tab") is what made the old behaviour indefensible:
        // it sends the reader to the pane, and the card then FOLLOWED them there
        // and covered the very terminal it had just sent them to use. Which faces
        // draw it is [PromptGate]'s to say, shared with the phone so the two
        // clients cannot drift.
        //
        // BELOW THE TAB BODY, NOT OVER IT, on the faces that do draw it. Overlaying
        // stopped the resize but hid what was underneath — one problem traded for
        // another. It costs real height, and that is now free of consequence: the
        // two faces that draw it are the transcript and the overview, and neither
        // measures itself into tmux rows. Only the Screen face did, which was the
        // other half of why it was the wrong place for a card.
        //
        // The card itself is the SHARED one (:ui PromptCards.kt) — one
        // implementation for both shells; only the answer plumbing stays here. When
        // the pane scrape cannot read the dialog but the hook knows a question is
        // waiting, the degraded card renders instead of nothing; its answers verify
        // against the live pane and steer to the Screen tab when that verification
        // cannot see a run (reason=undetected).
        // A pane-only MULTI-question prompt (no fused sidecar) is re-presented as
        // the read-only steer card — a single digit there over-answers the next
        // question. A genuine degraded ask and a pending plan approval draw here too.
        val rawPrompt = screen?.prompt
        val prompt = rawPrompt?.takeUnless { PromptGate.paneOnlyMultiQuestion(it) }
        val ask = screen?.ask ?: rawPrompt?.takeIf { PromptGate.paneOnlyMultiQuestion(it) }?.asMultiPartSteer()
        val planPending = screen?.planPending
        if (PromptGate.visible(
                hasQuestion = prompt != null || ask != null || planPending != null,
                face = tab.face,
            )
        ) {
            if (prompt != null) {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    PromptCard(
                        prompt = prompt,
                        answering = answering,
                        note = answerNote,
                        onAnswer = controller::answer,
                        onAnswerMulti = controller::answerMulti,
                    )
                }
            } else if (ask != null) {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    DegradedAskCard(
                        ask = ask,
                        answering = answering,
                        note = answerNote,
                        onAnswer = controller::answerDegraded,
                        // A multi-part question can't be tapped from here — jump to
                        // the Screen tab, where its parts are stepped through. This
                        // card stops being drawn the moment that lands, which is the
                        // point: it exists to hand the reader over, not to follow.
                        onOpenScreen = { controller.openTab(SessionTab.SCREEN) },
                    )
                }
            }
            // The plan the owner is approving — shipped on every poll, previously
            // rendered by nobody. When a readable prompt carries the approve/reject
            // buttons this is context; when the pane was unreadable it is the only
            // surface and steers to the Screen tab, where the dialog can be answered.
            planPending?.let {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    PlanApprovalCard(
                        plan = it,
                        hasButtons = prompt != null,
                        onOpenScreen = { controller.openTab(SessionTab.SCREEN) },
                    )
                }
            }
        }

        Composer(
            controller = controller,
            client = store.client,
            draft = draft,
            onDraft = setDraft,
            // Separate from `onDraft("")` on purpose: emptying the box is a
            // keystroke and is DEBOUNCED, so a send that only did that would leave
            // a timer in the air carrying the text just sent — and it would land
            // afterwards and put the message back in the composer as a draft. That
            // is a bug the Electron client actually shipped.
            onSent = { store.drafts.clear(draftKey) },
            onRestore = { setDraft(it) },
            history = history,
            onRecord = { store.sentHistory.record(draftKey, it) },
            working = working,
            scope = viewScope,
            pads = if (padsAvailable == true) pads else emptyList(),
            padRefId = padRefs[padRefKey],
            onPadRef = { store.setPadRef(padRefKey, it) },
            onSent2 = { store.setPadRef(padRefKey, null) },
            onPadRefRestore = { id ->
                if (store.padRefs.value[padRefKey] == null) store.setPadRef(padRefKey, id)
            },
        )
    }
    if (showPanel) ScratchpadSidePanel(store, PadTarget.Session(name))
    }
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
 *
 * IT SURVIVES THE SQUEEZE, which is the whole reason it is measured. Opening the
 * pages panel takes 360dp out of this column with no warning, and the row this
 * replaces answered that by WRAPPING: the model picker folded onto a second line
 * and the session name ran into the context meter, because a `Text` with no bound
 * is measured before its siblings and simply takes its whole intrinsic width.
 * Nothing here is allowed to wrap. The title ellipses, and under real pressure the
 * facts that are also written down somewhere else give way — in that order.
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
    contextPercent: Int?,
    compacting: Boolean,
    leased: Boolean,
    cols: Int?,
    rows: Int?,
    models: List<ModelChoice>,
    onCommand: (String) -> Unit,
    onCycleMode: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val tight = maxWidth < HEADER_TIGHT
        val cramped = maxWidth < HEADER_CRAMPED
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The identity half takes what the controls do not: one weighted Row
            // rather than a trailing Spacer, so the controls stay flush right even
            // when the title is short. (A weighted title with `fill = false` leaves
            // its unused share as slack, and the slack lands at the right edge.)
            // The end inset is the gap that a Spacer used to provide for free: at
            // full width there is slack here anyway, but once the title is long
            // enough to be ellipsised it ends flush against the context meter.
            Row(
                Modifier.weight(1f).padding(end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (state) {
                    "running" -> StateDot(MaterialTheme.colorScheme.primary)
                    "attention" -> StateDot(MaterialTheme.colorScheme.error)
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).widthIn(min = TITLE_MIN),
                )
                // The tmux name, and only when the title is something else — the
                // same rule the list row uses, because two identical strings side
                // by side is the header spending width to say one thing twice.
                if (!tight && name != title) {
                    Muted(name, Modifier.padding(start = 10.dp).widthIn(max = NAME_MAX))
                }
            }
            if (compacting) CompactingChip(Modifier.padding(end = 10.dp))
            // "Context window used" — the readout Claude auto-compacts against; sits
            // with the other status marks, only when the host reported a percentage.
            if (contextPercent != null) {
                Tip("Context window used") {
                    ContextMeter(contextPercent, Modifier.padding(end = 10.dp))
                }
            }
            // Geometry only matters while WE are holding the window to our shape —
            // saying so is the honest way to show that this window is doing something
            // to somebody else's terminal.
            if (!tight && leased && cols != null && rows != null) {
                Muted("fitted ${cols}×${rows}", Modifier.padding(end = 10.dp))
            }
            if (!cramped) branch?.let { Muted(it, Modifier.padding(end = 4.dp)) }
            // Permission mode has no slash command that sets it; Shift+Tab cycles it,
            // which is exactly what the key bar sends.
            ControlAction(mode?.replaceFirstChar { it.uppercase() } ?: "Mode", onClick = onCycleMode)
            // Effort is the first CONTROL to go, and the last thing to go at all:
            // it is the one of the three that is usually left where the host put it.
            // Its value is still on the Overview, and /effort still works by hand.
            if (!cramped) {
                ControlPicker(ModelLabels.effort(effort), ModelLabels.effortOptions()) { onCommand("/effort $it") }
            }
            // SESSION site: Claude rows only — this control types /model into a live
            // pane, where a local row could never work.
            ControlPicker(ModelLabels.model(model), ModelLabels.options(models, ModelLabels.PickerSite.SESSION)) { onCommand("/model $it") }
        }
    }
}

/**
 * The wash over the trailing edge of a strip that scrolls sideways.
 *
 * The suggestion chips have always scrolled; what they did not do is END
 * anywhere. With the pages panel open the row is cut by the column's edge
 * mid-word, and a sliced glyph reads as a rendering fault rather than as "there
 * is more to the right" — this strip is optional chrome, so it must never be the
 * thing on the screen that looks broken. A short gradient into the background
 * says what the hard clip was trying to, costs no height, and takes nothing out
 * of the scroll: it is drawn over the content, not laid out beside it.
 */
@Composable
fun rememberEdgeFade(width: Dp = 30.dp): Modifier {
    val bg = MaterialTheme.colorScheme.background
    return remember(bg, width) {
        Modifier.drawWithContent {
            drawContent()
            val w = width.toPx()
            drawRect(
                // WEIGHTED TOWARDS THE EDGE rather than linear. A linear wash over
                // 30dp dims a chip that legitimately ENDS near the edge as much as
                // it dissolves one that is being cut, and the first of those is the
                // common case. This leaves the first half almost untouched and does
                // its work in the last few pixels, where the sliced glyph is.
                brush = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.55f to bg.copy(alpha = 0.18f),
                        1f to bg,
                    ),
                    startX = size.width - w,
                    endX = size.width,
                ),
                topLeft = Offset(size.width - w, 0f),
                size = Size(w, size.height),
            )
        }
    }
}

/**
 * The widths the header gives things up at.
 *
 * [HEADER_TIGHT] is where the pages panel lands a maximised window: the tmux name
 * and the fitted geometry go, both of which are written down elsewhere (the list
 * row, and the pane itself). [HEADER_CRAMPED] is a genuinely narrow column, where
 * the git branch and the effort mark follow. What is left at every width is the
 * title, the state dot, the context meter, the mode and the model — the five
 * things this header is FOR.
 */
private val HEADER_TIGHT = 900.dp
private val HEADER_CRAMPED = 620.dp

/**
 * A floor for the title box, so it is never squeezed to a bare ellipsis while
 * there is room for a word of it. Coerced into whatever space the row actually
 * has, which is what stops a floor from becoming an overflow.
 */
private val TITLE_MIN = 80.dp

/** A tmux name is at most 50 characters, and none of them earn a third of the row. */
private val NAME_MAX = 150.dp

/**
 * The resting place: what this session has spent, where the pace lands, the
 * person's own notes, and the map of what it did.
 *
 * Thin by construction — the controller polls (only while this tab is selected),
 * [AppStore] owns the autosave, and the whole surface is the shared composable
 * the phone draws too.
 */
@Composable
private fun OverviewTab(controller: SessionController, store: AppStore) {
    val overview by controller.overview.collectAsState()
    val graph by controller.graph.collectAsState()
    val note by controller.overviewNote.collectAsState()
    val plan by store.plan.collectAsState()
    val goals by store.metaSaver.goals.collectAsState()
    val notes by store.metaSaver.notes.collectAsState()
    val saveState by store.metaSaver.state.collectAsState()
    val saveNote by store.metaSaver.note.collectAsState()
    var density by remember { mutableStateOf(OverviewDensity.COMPACT) }
    // The countdowns are live, so the clock has to move on its own; the map's own
    // numbers are refreshed by the poll.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(30_000)
        }
    }
    LaunchedEffect(Unit) { store.refreshStatus() }
    SessionOverviewView(
        overview = overview,
        graph = graph,
        plan = plan,
        nowMs = nowMs,
        goals = goals,
        notes = notes,
        saveState = saveState,
        density = density,
        onGoals = { store.metaSaver.setGoals(it) },
        onNotes = { store.metaSaver.setNotes(it) },
        onDensity = { density = it },
        unavailable = if (overview == null && graph == null) note else null,
        note = saveNote,
        onDismissNote = { store.metaSaver.clearNote() },
    )
}

@Composable
private fun TabStrip(
    current: SessionTab,
    onSelect: (SessionTab) -> Unit,
    /**
     * Actions for the tab in view. HERE and not in the tab's own content, because
     * this row sits ABOVE the weighted box that the Screen tab measures into tmux
     * rows — anything drawn inside that box's column resizes the owner's terminal.
     */
    trailing: @Composable () -> Unit = {},
) {
    // HEIGHT PINNED. A TextButton is 40.dp and a TabItem is ~36.dp, so a trailing
    // action appearing would grow this row by 4.dp — and everything below it is the
    // box that measures itself into tmux rows, so even that much is a real resize
    // of somebody's terminal. Pinned, the trailing slot is free.
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabItem("Conversation", current == SessionTab.CONVERSATION) { onSelect(SessionTab.CONVERSATION) }
        TabItem("Screen", current == SessionTab.SCREEN) { onSelect(SessionTab.SCREEN) }
        TabItem("Overview", current == SessionTab.OVERVIEW) { onSelect(SessionTab.OVERVIEW) }
        Box(Modifier.weight(1f))
        // Out of the focus order: the Screen tab holds keyboard focus so live keys
        // reach the pane, and a button that took focus on click would silently stop
        // typing from working until the reader clicked the pane again.
        Box(Modifier.focusProperties { canFocus = false }) { trailing() }
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
    val hasEarlier by controller.hasEarlier.collectAsState()
    val loadingHistory by controller.loadingHistory.collectAsState()
    val itemCount = rows.size + if (hasEarlier) 1 else 0
    val revision = tailRevision(current.nextOffset, rows.size, current.events.lastOrNull()?.text?.length)
    // A wheel tick is the reader taking the list, exactly as a drag is — and it
    // emits no DragInteraction, so without this the latch could never be broken
    // on a desktop: scrolling up to read something in a live session snapped back
    // to the tail on the next token.
    val scrolls = remember(controller.name) { mutableStateOf(0) }
    val unseen = FollowNewest(listState, itemCount, revision, key = controller.name, scrolls = scrolls)

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
                    Modifier.fillMaxSize().padding(horizontal = 16.dp).onScrollInput { scrolls.value++ },
                    state = listState,
                    contentPadding = PaddingValues(vertical = metrics.rowPadding),
                    verticalArrangement = Arrangement.spacedBy(metrics.rowSpacing),
                ) {
                    // The conversation IS the history, so the top of the list is
                    // a way into it rather than an apology for its absence. It
                    // used to read "Showing the most recent part of this session."
                    // and stop there — on a long session that was a sliver (51
                    // events out of 3452, measured) with no way to ask for the
                    // rest. Only the Screen tab has a genuine excuse: a Claude
                    // pane runs on the alternate screen and has no scrollback at
                    // all.
                    if (hasEarlier) {
                        item("earlier") {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = Space.unit),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                if (loadingHistory) {
                                    Muted("Loading earlier messages…")
                                } else {
                                    TextButton(onClick = { controller.loadEarlier() }) {
                                        Text("Load earlier messages", style = DeskType.rail)
                                    }
                                }
                            }
                        }
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
    // Hoisted to the controller (was a local var) so the composer — outside the
    // tabs — can suppress its Up/Down history recall while live mode owns the
    // keys. Per-session reset is preserved: the controller instance is per-name.
    val live by controller.live.collectAsState()

    val density = LocalDensity.current
    val fg = MaterialTheme.colorScheme.onSurface
    val bg = MaterialTheme.colorScheme.background
    val monoPx = with(density) { LocalMonoStyle.current.fontSize.toPx() }
    val painter = remember(monoPx) { SkiaCellPainter(monoPx) }
    val focus = remember { FocusRequester() }

    // Take focus AFTER the click that turned live on has settled, not during it.
    // Asking inside the button's own onClick raced the button acquiring focus from
    // that very same click, and what lost the race was the first keystroke:
    // enabling live and typing "ls" put "s" into the pane.
    LaunchedEffect(live) { if (live) focus.requestFocus() }

    // ⚠ NOTHING MAY BE ADDED TO THIS COLUMN ABOVE THE PANE. The BoxWithConstraints
    // below takes weight(1f) from it and MEASURES ITS OWN HEIGHT INTO TMUX ROWS,
    // so a strip here shrinks the owner's real terminal — and a strip that comes
    // and goes with the content walks it between two shapes, resizing every client
    // attached to that session, phone included. The copy actions shipped here in
    // 0.8.6 and did exactly that; they now live in the TabStrip, which is above
    // this whole column and outside the measured box.
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
            // ⚠⚠ A PROMPT MAY TAKE LAYOUT SPACE. IT MAY NOT CHANGE THE REPORTED
            // GEOMETRY. Two different things, and conflating them cost two
            // releases: as a plain sibling the prompt card resized the owner's real
            // terminal twice per question, and as an overlay it stopped resizing
            // anything but covered the terminal and the controls instead.
            //
            // It is now drawn in neither form on this face — [PromptGate] withholds
            // it here entirely, so nothing question-shaped takes height any more
            // and the viewport no longer shrinks when a question arrives. THE HOLD
            // BELOW STAYS ANYWAY, on the reason that outlived the card: this is the
            // face a reader is sent to in order to ANSWER, and reporting a new
            // geometry re-wraps the live dialog under them mid-answer. It is only
            // ever a pause — a window resized while a question is up is reported
            // the moment the question is gone.
            val cols = with(density) { (maxWidth.toPx() / painter.cellWidth).toInt() }
            val rows = with(density) { (maxHeight.toPx() / painter.cellHeight).toInt() }
            val promptUp = screen?.prompt != null || screen?.ask != null
            LaunchedEffect(cols, rows, promptUp) { if (!promptUp) controller.setGeometry(cols, rows) }

            // Overlaid rather than stacked above, for the same reason as the prompt
            // card: the controller clears this on every SUCCESSFUL poll, so as a
            // sibling one failed keystroke resized the terminal down and the next
            // poll a moment later resized it back — two real tmux resizes out of a
            // transient hiccup.
            error?.let {
                Muted(
                    it,
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 5.dp),
                )
            }

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

        KeyBar(controller, live) { controller.setLive(!live) }
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
 * The draft is HOISTED into the app's [com.silencelen.huginn.data.DraftBook]: a
 * suggestion chip fills it, the interrupt control appears only while it is empty,
 * and — the reason it is not a `remember` — it has to survive this view leaving
 * the composition, which is what every navigation away from the session does.
 */
@Composable
private fun Composer(
    controller: SessionController,
    client: HuginnClient,
    draft: String,
    onDraft: (String) -> Unit,
    onSent: () -> Unit,
    /** Puts the text back when a send could not be delivered. See [submit]. */
    onRestore: (String) -> Unit,
    history: List<String>,
    onRecord: (String) -> Unit,
    working: Boolean,
    scope: CoroutineScope,
    pads: List<com.silencelen.huginn.data.Scratchpad> = emptyList(),
    padRefId: String? = null,
    onPadRef: (String?) -> Unit = {},
    /** The reference rides ONE message, like a staged photo. */
    onSent2: () -> Unit = {},
    /** A refused send hands the reference back — unless a newer one was set. */
    onPadRefRestore: (String) -> Unit = {},
) {
    val attachments = rememberAttachmentController(client, scope, controller.name)
    val attachment by attachments.current.collectAsState()
    val failure by attachments.failure.collectAsState()
    var picking by remember { mutableStateOf(false) }
    var dragOver by remember { mutableStateOf(false) }

    val pending = attachment
    val canSend = draft.isNotBlank() || (pending != null && pending.status != AttachStatus.FAILED)

    // Sent-history recall. Suppressed while the Screen tab has live keyboard on:
    // there every keystroke, arrows included, belongs to the pane. The composer
    // is click-refocusable even then, so the guard is still required.
    val recall = remember { mutableStateOf<HistoryWalk.Cursor?>(null) }
    val tab by controller.tab.collectAsState()
    val live by controller.live.collectAsState()
    val historySuppressed = tab == SessionTab.SCREEN && live

    val submit: () -> Unit = {
        if (canSend) {
            val body = draft
            if (body.isNotBlank()) onRecord(body)
            recall.value = null
            // Captured before the composer is emptied: the reference belongs to
            // the message being sent, not to whatever is typed next.
            val padId = padRefId
            onSent()
            onSent2()
            var posted = false
            scope.launch {
                try {
                    val marker = attachments.take()
                    val full = composeMessage(body, marker, PANE_SEPARATOR)
                    // Cancellation is cooperative, so a scope killed while take()
                    // was returning would otherwise reach a sendLine that never
                    // runs and still count as sent.
                    ensureActive()
                    // Awaited, not fired-and-forgotten: the box was emptied on
                    // press, so a refusal with no answer left the text (and the
                    // attached page) existing nowhere.
                    posted = if (full.isNotEmpty()) controller.sendLineNow(full, scratchpadId = padId) else true
                } finally {
                    // take() parks for the whole upload on a scope that dies with
                    // this view, so leaving the session mid-upload cancels the
                    // send — and the box was emptied the moment it was pressed.
                    // Without this the message is simply gone, with no error and
                    // nothing left to retry from.
                    //
                    // Putting the text back is the only option here even in
                    // principle: leaving also closes the controller, so a send
                    // that tried to carry on regardless would be launching on a
                    // scope that is already cancelled.
                    if (!posted) {
                        onRestore(body)
                        if (padId != null) onPadRefRestore(padId)
                    }
                }
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
        // ONLY when one is set. The empty-state invitation moved into the attach
        // button's chooser; what stays is the mark that a whole page is riding
        // out with this message.
        pads.firstOrNull { it.id == padRefId }?.let { chosen ->
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                ScratchpadRefBadge(pad = chosen, pads = pads, onSelect = onPadRef)
            }
        }
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
            AttachButton(
                pads = pads,
                padRefId = padRefId,
                onPadRef = onPadRef,
                onPickFile = { picking = true },
            )
    // Same TextFieldValue as the chat composer, for the same reason: Shift+Enter
    // has to insert the newline itself, and appending to the String is silently
    // discarded by the field's own editing buffer. See ChatView for the detail.
    var field by remember { mutableStateOf(TextFieldValue(draft)) }
    if (field.text != draft) {
        field = TextFieldValue(draft, TextRange(draft.length))
    }
            OutlinedTextField(
                value = field,
                onValueChange = { field = it; onDraft(it.text); exitRecallIfDiverged(recall, it.text) },
                // Cap before fill. `fillMaxWidth` hands DOWN fixed constraints and a
                // `widthIn` inside those can only coerce into them, so the cap would be
                // swallowed and a composer meant to stop at a reading measure would
                // span the whole window.
                modifier = Modifier.widthIn(max = 900.dp).weight(1f)
                    .heightIn(min = 56.dp, max = 160.dp)
                    // ENTER SENDS, Shift+Enter is the newline — the same binding as
                    // the chat composer, because two boxes in one app where Enter
                    // means opposite things is worse than either choice. Ctrl+Enter
                    // still sends, so the older habit keeps working. The newline is
                    // inserted here because Compose maps nothing to Shift+Enter —
                    // see the same block in ChatView for why it appends.
                    //
                    // This one does send into a LIVE agent session, which is the
                    // argument the previous binding was making; it is answered by
                    // the pane showing what arrived, and by Enter being what every
                    // other composer in reach already does.
                    .onPreviewKeyEvent { e ->
                        val bareArrowOrEsc = !e.isCtrlPressed && !e.isAltPressed &&
                            !e.isMetaPressed && !e.isShiftPressed &&
                            (e.key == Key.DirectionUp || e.key == Key.DirectionDown || e.key == Key.Escape)
                        when {
                            e.type != KeyEventType.KeyDown -> false
                            e.key == Key.Enter && e.isShiftPressed -> {
                                val at = field.selection.start
                                val next = field.text.substring(0, at) + "\n" + field.text.substring(field.selection.end)
                                field = TextFieldValue(next, TextRange(at + 1))
                                onDraft(next)
                                true
                            }
                            e.key == Key.Enter -> { submit(); true }
                            e.isCtrlPressed && e.key == Key.V -> AwtTransfer.consumeClipboard(attachments)
                            bareArrowOrEsc -> handleHistoryKey(
                                e.key, field, recall, history, suppressed = historySuppressed,
                                setField = { field = it }, onDraft = onDraft,
                            )
                            else -> false
                        }
                    },
                placeholder = { Text("Send to the pane…  (Enter to send · Shift+Enter for a new line)") },
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
