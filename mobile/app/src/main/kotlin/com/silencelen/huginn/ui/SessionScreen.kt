package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.ModelChoice
import com.silencelen.huginn.data.Screen
import kotlinx.coroutines.launch
import com.silencelen.huginn.data.TranscriptPage

/**
 * One tmux session, two ways of looking at it.
 *
 * **Conversation** is the default and is read from the session's Claude Code
 * transcript: real structured events (thinking, tool calls with their results,
 * subagent output, workflow runs) rather than anything scraped off the screen.
 * **Screen** is the live pane, for the things only the pane can do: answering a
 * prompt, watching a spinner, typing.
 */
@Composable
fun SessionScreen(
    name: String,
    transcript: TranscriptPage?,
    transcriptError: String?,
    screen: Screen?,
    scrollback: List<String>,
    loadingScrollback: Boolean,
    onLoadScrollback: () -> Unit,
    tab: Int,
    onTab: (Int) -> Unit,
    fontScale: Float,
    onFontScale: (Float) -> Unit,
    onGeometry: (Int, Int) -> Unit,
    models: List<ModelChoice>,
    draft: String,
    onDraft: (String) -> Unit,
    onSendText: (String, Boolean) -> Unit,
    onSendKeys: (List<String>) -> Unit,
    onLive: (LiveInput.Op) -> Unit,
    agents: com.silencelen.huginn.data.AgentsInfo?,
    onAgentsOpen: () -> Unit,
    onAgentsClose: () -> Unit,
    suggestions: List<String>,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    onAnswerPrompt: (Int) -> Unit,
    onAnswerMulti: (List<Int>) -> Unit,
    onForceResize: () -> Unit,
    onInterrupt: () -> Unit,
    onCopy: (String) -> Unit,
    hasEarlier: Boolean = false,
    loadingHistory: Boolean = false,
    onLoadEarlier: () -> Unit = {},
    working: Boolean,
    attachment: HuginnViewModel.Attachment? = null,
    onAttach: (android.net.Uri) -> Unit = {},
    onAttachFile: (android.net.Uri) -> Unit = {},
    onClearAttachment: () -> Unit = {},
    /** Empty against a daemon with no scratchpads, which hides the control. */
    pads: List<com.silencelen.huginn.data.Scratchpad> = emptyList(),
    padRefId: String? = null,
    onPadRef: (String?) -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { onTab(0) }, text = { Text("Conversation") })
            Tab(selected = tab == 1, onClick = { onTab(1) }, text = { Text("Screen") })
        }
        SessionControls(
            // The pane reports the CURRENT model and mode; the transcript only
            // knows what the last completed turn used, so a just-issued /model
            // change would otherwise leave the control showing the old value.
            // Both of these carry a version; neither is a bare family name.
            model = screen?.liveModel ?: transcript?.modelDisplay,
            effort = transcript?.effort,
            permissionMode = screen?.liveMode ?: transcript?.permissionMode,
            models = models,
            // Slash commands go in as a submitted line, exactly as typed by hand.
            onCommand = { onSendText(it, true) },
            onCycleMode = { onSendKeys(listOf("BTab")) },
            contextPercent = screen?.contextPercent,
            compacting = screen?.compacting ?: false,
        )
        Box(Modifier.weight(1f)) {
            if (tab == 0) {
                SessionConversation(
                    name = name,
                    page = transcript,
                    error = transcriptError,
                    prompt = screen?.prompt,
                    spinner = screen?.spinner,
                    statusLines = screen?.statusLines ?: emptyList(),
                    transientLine = screen?.transientLine,
                    agents = agents,
                    onAgentsOpen = onAgentsOpen,
                    onAgentsClose = onAgentsClose,
                    suggestions = suggestions,
                    micGranted = micGranted,
                    onRequestMic = onRequestMic,
                    draft = draft,
                    onDraft = onDraft,
                    onSendText = onSendText,
                    onAnswerPrompt = onAnswerPrompt,
                    onAnswerMulti = onAnswerMulti,
                    onInterrupt = onInterrupt,
                    working = working,
                    onCopy = onCopy,
                    hasEarlier = hasEarlier,
                    loadingHistory = loadingHistory,
                    onLoadEarlier = onLoadEarlier,
                attachment = attachment,
                onAttach = onAttach,
                onAttachFile = onAttachFile,
                onClearAttachment = onClearAttachment,
                pads = pads,
                padRefId = padRefId,
                onPadRef = onPadRef,
            )
            } else {
                TerminalScreen(
                    session = name,
                    screen = screen,
                    scrollback = scrollback,
                    loadingScrollback = loadingScrollback,
                    onLoadScrollback = onLoadScrollback,
                    draft = draft,
                    onDraft = onDraft,
                    fontScale = fontScale,
                    onFontScale = onFontScale,
                    onGeometry = onGeometry,
                    onSendText = onSendText,
                    onSendKeys = onSendKeys,
                    onLive = onLive,
                    micGranted = micGranted,
                    onRequestMic = onRequestMic,
                    onAnswerPrompt = onAnswerPrompt,
                    onAnswerMulti = onAnswerMulti,
                    onForceResize = onForceResize,
                )
            }
        }
    }
}

@Composable
private fun SessionConversation(
    name: String,
    page: TranscriptPage?,
    error: String?,
    prompt: com.silencelen.huginn.data.PanePrompt?,
    spinner: String?,
    statusLines: List<String>,
    transientLine: String?,
    agents: com.silencelen.huginn.data.AgentsInfo?,
    onAgentsOpen: () -> Unit,
    onAgentsClose: () -> Unit,
    suggestions: List<String>,
    draft: String,
    onDraft: (String) -> Unit,
    onSendText: (String, Boolean) -> Unit,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    onAnswerPrompt: (Int) -> Unit,
    onAnswerMulti: (List<Int>) -> Unit,
    onInterrupt: () -> Unit,
    working: Boolean,
    onCopy: (String) -> Unit,
    hasEarlier: Boolean = false,
    loadingHistory: Boolean = false,
    onLoadEarlier: () -> Unit = {},
    attachment: HuginnViewModel.Attachment? = null,
    onAttach: (android.net.Uri) -> Unit = {},
    onAttachFile: (android.net.Uri) -> Unit = {},
    onClearAttachment: () -> Unit = {},
    pads: List<com.silencelen.huginn.data.Scratchpad> = emptyList(),
    padRefId: String? = null,
    onPadRef: (String?) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val events = page?.events ?: emptyList()
    // Grouped so a fan-out reads as delegated units instead of drowning the
    // main thread; recomputed only when the events actually change.
    val rows = remember(events) { TranscriptGroups.group(events) }
    val rowKeys = remember(rows) { TranscriptGroups.keys(rows) }
    val headerItems = if (page?.truncated == true) 1 else 0

    // Revision keys on nextOffset, which strictly increases as transcript bytes
    // arrive. Event COUNT is useless here: the retained window is capped, so on a
    // long session it stops changing and following would silently stop with it.
    val hasUnseen = AutoScrollToNewest(
        listState = listState,
        itemCount = rows.size + headerItems,
        revision = tailRevision(page?.nextOffset, events.size, events.lastOrNull()?.text?.length),
        key = name,
    )

    Column(Modifier.fillMaxSize()) {
        when {
            error != null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(
                    "No conversation yet",
                    // The mapping is written by a hook that fires on the first
                    // prompt, so a brand-new session genuinely has nothing here.
                    error,
                )
            }
            page == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 14.dp, end = 14.dp, top = 8.dp, bottom = 10.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                // The conversation IS the history, so the top of the list is a
                // way into it rather than an apology for its absence. It used to
                // read "Showing the most recent part of this session." and stop
                // there — on a long session a sliver, measured at 51 events out
                // of 3452, with no way to ask for the rest. Only the Screen tab
                // has a real excuse: a Claude pane runs on the alternate screen
                // and has no scrollback at all.
                if (hasEarlier) {
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            if (loadingHistory) {
                                Text(
                                    "Loading earlier messages…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                TextButton(onClick = onLoadEarlier) { Text("Load earlier messages") }
                            }
                        }
                    }
                }
                items(rows.size, key = { rowKeys[it] }) { i -> TranscriptRowItem(rows[i], onCopy) }
            }
        }

        if (hasUnseen) {
            JumpToNewest { scope.launch { listState.jumpToTail(rows.size + headerItems, animate = true) } }
        }

        // What the session is doing RIGHT NOW, pinned above the composer. The
        // transcript stays silent until whole blocks complete, so without this the
        // conversation looked dead exactly when the most was happening — right
        // after sending a message. The pane's own status line is the best source;
        // the transcript's unresolved tool is the fallback; "working" is the floor.
        // Shown while the turn runs, and ALSO while background work continues after
        // it — a session blocked on a twenty-minute build used to read as stalled
        // from here, with the truth visible only on the tmux screen.
        // The transient per-tool row turns over constantly and vanishes between
        // tools; shown from memory while the turn runs so the strip changes text
        // in place instead of growing a line and losing it again on repeat.
        var lastTransient by remember(name) { mutableStateOf<String?>(null) }
        LaunchedEffect(transientLine, working) {
            if (transientLine != null) lastTransient = transientLine
            if (!working) lastTransient = null
        }
        var showWork by rememberSaveable(name) { mutableStateOf(false) }

        // Pane-derived rows are trusted only while the session is actually
        // working. The TUI keeps workflow and board rows in its persistent
        // footer after the run ends — truthful on a terminal where they read as
        // history, but mirrored into this strip they claimed a workflow was
        // still running when it was long finished. The hook state knows better;
        // background shells and agents are exempt because their liveness is
        // already measured, not read off a screen.
        val paneRows = if (working) statusLines else emptyList()
        val paneSpinner = if (working) spinner else null
        val bgWork = page?.tasks?.isNotEmpty() == true || (page?.bgAgents ?: 0) > 0

        if (working || bgWork) {
            WorkStrip(
                spinner = paneSpinner,
                statusLines = paneRows,
                transient = if (working) lastTransient else null,
                activity = if (working) page?.activity else null,
                tasks = page?.tasks ?: emptyList(),
                bgAgents = page?.bgAgents ?: 0,
                onClick = { showWork = true },
            )
        }

        if (showWork) {
            WorkSheet(
                name = name,
                spinner = paneSpinner,
                statusLines = paneRows,
                tasks = page?.tasks ?: emptyList(),
                agents = agents,
                onOpen = onAgentsOpen,
                onClose = onAgentsClose,
                // Closing the sheet is a navigation decision; stopping the poll is
                // bookkeeping and belongs to the sheet's lifetime, above.
                onDismiss = { showWork = false },
            )
        }

        // Suggested next messages, at the turn boundary only. A live prompt's
        // buttons outrank them, typing dismisses them, and tapping one FILLS the
        // composer rather than sending — a suggestion is a draft, not a decision.
        if (suggestions.isNotEmpty() && prompt == null && !working && draft.isBlank()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                suggestions.forEach { sug ->
                    SuggestionChip(
                        onClick = { onDraft(sug) },
                        label = {
                            Text(
                                sug,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }

        prompt?.let {
            PromptCard(it, onAnswerPrompt, onAnswerMulti)
        }

        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)) {
          Column {
            // The page a session's Claude will be pointed at. It reaches the pane
            // as a PATH — a page holds more than a pane accepts in one paste — so
            // the run reads it rather than being handed it.
            if (pads.isNotEmpty()) {
                ScratchpadChip(
                    pads = pads,
                    selectedId = padRefId,
                    onSelect = onPadRef,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                )
            }
            AttachmentBar(attachment, onClearAttachment)
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
                    placeholder = { Text("Reply in $name") },
                    maxLines = 6,
                    shape = RoundedCornerShape(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                AttachButton(onPickImage = onAttach, onPickFile = onAttachFile)
                DictationMicButton(
                    micGranted = micGranted,
                    onRequestMic = onRequestMic,
                    onText = { heard -> onDraft(appendDictation(draft, heard)) },
                )
                // Esc is how you stop Claude at the keyboard; with nothing typed
                // that is the action this composer should offer.
                if (working && draft.isBlank()) {
                    IconButton(onClick = onInterrupt, modifier = Modifier.size(46.dp)) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = "Interrupt with Esc",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                // A photo alone is a complete message here too; the send path
                // builds the marker text when the draft is blank.
                val canSend = draft.isNotBlank() || attachment is HuginnViewModel.Attachment.Ready
                IconButton(
                    onClick = { if (canSend) onSendText(draft, true) },
                    enabled = canSend,
                    modifier = Modifier.size(46.dp),
                ) {
                    Icon(
                        Icons.Filled.KeyboardReturn,
                        contentDescription = "Send",
                        tint = if (canSend) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
          }
        }
    }
}

/** Header line shown in the app bar subtitle for a session. */
@Composable
fun SessionSubtitle(page: TranscriptPage?, screen: Screen?) {
    val bits = buildList {
        // The readable form, which keeps the version: "Opus 4.8", not "opus".
        (page?.modelDisplay ?: page?.model)?.let { add(it) }
        page?.gitBranch?.let { add(it) }
        page?.permissionMode?.let { add("$it mode") }
        screen?.let { add("${it.width}x${it.height}") }
    }
    if (bits.isEmpty()) return
    Text(
        bits.joinToString("  ·  "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Normal,
    )
}


/**
 * The "what is it doing right now" strip. Priority of sources: the pane's own
 * status line (verbatim-ish, the way Claude Code shows it), then the transcript's
 * unresolved tool, then the bare fact of running. Tapping it jumps to the newest
 * content, since "what is happening" and "show me" are the same impulse.
 */
@Composable
private fun WorkStrip(
    spinner: String?,
    statusLines: List<String>,
    transient: String?,
    activity: com.silencelen.huginn.data.Activity?,
    tasks: List<com.silencelen.huginn.data.BgTask>,
    bgAgents: Int,
    onClick: () -> Unit,
) {
    val headline = spinner ?: activity?.let { a ->
        buildString {
            append(a.tool ?: "working")
            a.detail?.takeIf { it.isNotBlank() }?.let { append("  ").append(it) }
            if (a.subagents > 0) append("  ·  ${a.subagents} subagent${if (a.subagents == 1) "" else "s"}")
        }
    } ?: if (tasks.isNotEmpty() || bgAgents > 0) "background work" else "working"

    // Durable rows first (workflow phases, agent counts), then background shells,
    // then the in-place transient slot — which changes text but never appears and
    // disappears mid-turn, because that made the strip pump up and down.
    val detailRows = buildList {
        addAll(statusLines)
        tasks.take(2).forEach { t ->
            add("⚙ ${t.command}" + if (t.forSeconds > 0) " · ${agoShort(t.forSeconds)}" else "")
        }
        if (bgAgents > 0 && statusLines.none { it.contains("agent") }) {
            add("$bgAgents background agent${if (bgAgents == 1) "" else "s"}")
        }
        transient?.let { add(it) }
    }.take(3)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDot(MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(9.dp))
                Text(
                    headline,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.ExpandLess,
                    contentDescription = "Open work details",
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            detailRows.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 17.dp, top = 2.dp),
                )
            }
        }
    }
}

/** "12s", "4m", "1h 12m" — elapsed for a background task, kept short. */
private fun agoShort(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}


/**
 * How many agents the fan-out PLANNED, read off the TUI's own progress row
 * ("0/4 agents done"), or null when no row says so.
 *
 * It has to come from here rather than from the agent files: a planned agent has
 * no file until it starts, so counting files undercounts the total for exactly
 * as long as the fan-out is interesting to look at. Takes the largest total on
 * screen, since several workflow rows can be present and the widest is the job.
 */
internal fun plannedAgents(statusLines: List<String>): Int? {
    val re = Regex("(\\d+)\\s*/\\s*(\\d+)\\s+agents?\\s+done")
    return statusLines
        .mapNotNull { re.find(it)?.groupValues?.get(2)?.toIntOrNull() }
        .maxOrNull()
}

/**
 * The work strip, opened. Everything the strip compresses: the TUI's progress
 * rows, background shells, and — the reason it exists — the individual agents
 * behind "0/4 agents done", each with its task and what it is doing right now.
 * Polled only while open; agents are files on huginn, and reading two dozen
 * transcript tails every three seconds is a cost worth paying only while
 * somebody is actually looking.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun WorkSheet(
    name: String,
    spinner: String?,
    statusLines: List<String>,
    tasks: List<com.silencelen.huginn.data.BgTask>,
    agents: com.silencelen.huginn.data.AgentsInfo?,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Started and stopped by the SHEET'S OWN existence. Stopping only in
    // onDismissRequest meant every other way the sheet can go — back navigation, the
    // session being killed, a rail switch, the destination changing while
    // backgrounded — left a 3-second agents poll running in viewModelScope forever,
    // against a session no longer on screen.
    DisposableEffect(name) {
        onOpen()
        onDispose { onClose() }
    }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 18.dp, end = 18.dp, bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    spinner ?: "Work in $name",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(statusLines.size) { i ->
                Text(
                    statusLines[i],
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (tasks.isNotEmpty()) {
                item {
                    Text(
                        "Background shells",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                items(tasks.size) { i ->
                    val t = tasks[i]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulsingDot(MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                t.command,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            agoShort(t.forSeconds),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val list = agents?.agents ?: emptyList()
            // The TUI's own denominator, which counts agents that have not started
            // yet. Ours came from the agent FILES on huginn, and a planned agent has
            // no file until it runs — so "1 of 2 agents" sat beside the pane's
            // "1/6 agents done" and read as a different fan-out. Same number, same
            // phrasing, taken from the row Claude Code already prints.
            val planned = plannedAgents(statusLines)
            val done = list.count { !it.active }
            item {
                Text(
                    when {
                        agents == null -> "Agents…"
                        list.isEmpty() && planned == null -> "No agents in this session recently"
                        else -> "$done of ${maxOf(planned ?: list.size, list.size)} agents done"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            items(list.size) { i -> AgentRow(list[i]) }
        }
    }
}

@Composable
private fun AgentRow(a: com.silencelen.huginn.data.AgentRun) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (a.active) 0.65f else 0.3f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (a.active) PulsingDot(MaterialTheme.colorScheme.primary)
                else Box(
                    Modifier.size(8.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.outline)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (a.active) "working" else "settled",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (a.active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                a.workflow?.let { wf ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        // wf_b3db6247-567 → b3db6247: enough to tell runs apart.
                        wf.removePrefix("wf_").substringBefore("-"),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    relTime(a.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            a.task?.let { task ->
                Spacer(Modifier.size(3.dp))
                Text(
                    // The boilerplate context header buries the actual ask.
                    task.removePrefix("CONTEXT:").trim(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            // A settled agent's own conclusion beats its last tool call as an
            // epitaph; the live line stays for agents still working.
            val closing = if (!a.active && !a.summary.isNullOrBlank()) a.summary else a.lastLine
            closing?.let { last ->
                Spacer(Modifier.size(3.dp))
                Text(
                    last,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = if (last == a.summary) androidx.compose.ui.text.font.FontFamily.Default
                    else androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}
