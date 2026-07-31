package com.silencelen.huginn.desktop.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.AgentsInfo
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.Screen
import com.silencelen.huginn.data.TranscriptPage
import com.silencelen.huginn.ui.WorkSummary
import com.silencelen.huginn.ui.work.WorkDetail
import com.silencelen.huginn.ui.work.WorkStrip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The individual agents behind a fan-out, polled ONLY while somebody is looking.
 *
 * Each agent is a file on huginn and answering this reads a tail out of every one
 * of them; two dozen of those every three seconds is a cost worth paying while the
 * detail panel is open and absurd otherwise. Start and stop are driven by the
 * PANEL'S OWN existence rather than by a click handler, because a panel can also
 * close by the session ending, the view changing or the window going away — and on
 * the phone, before that lesson, each of those left a 3-second poll running forever
 * against a session nobody was looking at.
 */
class AgentsPoll(
    private val client: HuginnClient,
    private val name: String,
    private val scope: CoroutineScope,
) {
    private val _agents = MutableStateFlow<AgentsInfo?>(null)
    val agents: StateFlow<AgentsInfo?> = _agents.asStateFlow()

    private var job: Job? = null

    fun open() {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                runCatching { client.sessionAgents(name) }.onSuccess { _agents.value = it }
                delay(POLL_MS)
            }
        }
    }

    fun close() {
        job?.cancel()
        job = null
        // Cleared, so re-opening shows "Agents…" rather than a stale fan-out from
        // a run that finished ten minutes ago.
        _agents.value = null
    }

    private companion object {
        const val POLL_MS = 3_000L
    }
}

/**
 * The work strip and, when opened, its detail — pinned above the composer.
 *
 * Drawn only on the Conversation tab, and not only because that is where the
 * phone puts it: this strip sits OUTSIDE the box the Screen tab measures itself
 * from, so on that tab its appearing and disappearing would change the measured
 * row count and walk the owner's real tmux window through two shapes every turn.
 * The Screen tab already shows the pane's own status lines anyway.
 *
 * @param working the session's hook state, not a guess from the screen.
 * @param draw false emits NOTHING but keeps every piece of state alive. Being
 *   composed out instead would reset how long ago work was last seen, so glancing
 *   at the pane mid-run and coming back would lose the strip.
 */
@Composable
fun WorkPanel(
    name: String,
    page: TranscriptPage?,
    screen: Screen?,
    working: Boolean,
    client: HuginnClient,
    scope: CoroutineScope,
    draw: Boolean = true,
    now: () -> Long = System::currentTimeMillis,
) {
    // The transient per-tool row turns over constantly and vanishes between tools.
    // Held from memory while the turn runs so the strip changes text in place
    // instead of growing a line and losing it again on the next tool.
    var lastTransient by remember(name) { mutableStateOf<String?>(null) }
    LaunchedEffect(screen?.transientLine, working) {
        screen?.transientLine?.let { lastTransient = it }
        if (!working) lastTransient = null
    }

    val bgWork = page?.tasks?.isNotEmpty() == true || (page?.bgAgents ?: 0) > 0

    // WHEN WORK WAS LAST SEEN, which is what the strip lingers from. An agent's
    // conclusion becomes readable at almost exactly the moment the fan-out settles
    // — a strip keyed strictly on "is it working" vanishes on that same frame and
    // takes the conclusion with it.
    var lastWorkAt by remember(name) { mutableStateOf<Long?>(null) }
    var clock by remember(name) { mutableStateOf(now()) }

    // One ticker does both jobs, and it has to TICK rather than fire on an edge.
    // Stamping only when `working` flips to true measures the linger from when the
    // work STARTED, so a fan-out that ran for twenty minutes would lose its strip
    // the instant it finished — the opposite of the point. It also has to keep
    // running after the work stops, because nothing else moves once a session goes
    // quiet and the linger would never expire. It returns as soon as there is
    // nothing left to expire, so an idle session pays for none of this.
    LaunchedEffect(name, working, bgWork) {
        while (true) {
            if (working || bgWork) lastWorkAt = now()
            clock = now()
            if (!working && !bgWork && !WorkSummary.visible(false, false, lastWorkAt, clock)) {
                return@LaunchedEffect
            }
            delay(LINGER_TICK_MS)
        }
    }

    val visible = WorkSummary.visible(working, bgWork, lastWorkAt, clock)
    var open by remember(name) { mutableStateOf(false) }
    // Closing itself when it goes away: an expander left open would spring back
    // the next time the session starts working, showing a panel nobody asked for.
    LaunchedEffect(visible) { if (!visible) open = false }
    // Everything above is STATE and runs on both tabs. Only the drawing is gated,
    // and the agents poll below is gated with it — it hangs off the open panel's
    // own existence, so a panel that is not drawn cannot be polling for one.
    if (!visible || !draw) return

    val live = working || bgWork
    val paneRows = WorkSummary.paneRows(screen?.statusLines ?: emptyList(), working)
    val strip = WorkSummary.strip(
        spinner = if (working) screen?.spinner else null,
        statusLines = paneRows,
        transient = if (working) lastTransient else null,
        activity = if (working) page?.activity else null,
        tasks = page?.tasks ?: emptyList(),
        bgAgents = page?.bgAgents ?: 0,
        live = live,
    )

    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        WorkStrip(
            strip = strip,
            // A lingering strip must not go on breathing at work that has stopped.
            live = live,
            onClick = { open = !open },
            trailing = {
                Text(
                    // The mark IS the control, in the vernacular of the thing it
                    // opens — not a second button saying the same verb.
                    if (open) "hide" else "detail",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        if (open) {
            val poll = remember(name) { AgentsPoll(client, name, scope) }
            DisposableEffect(name) {
                poll.open()
                onDispose { poll.close() }
            }
            val agents by poll.agents.collectAsState()
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    // Capped: the panel shares the window with the conversation it
                    // is reporting on, and a twenty-agent fan-out must not push the
                    // composer off the bottom.
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                WorkDetail(
                    title = (if (working) screen?.spinner else null) ?: "Work in $name",
                    statusLines = paneRows,
                    tasks = page?.tasks ?: emptyList(),
                    agents = agents,
                )
            }
        }
    }
}

/** Coarse: the only question it answers is whether three minutes have passed. */
private const val LINGER_TICK_MS = 10_000L
