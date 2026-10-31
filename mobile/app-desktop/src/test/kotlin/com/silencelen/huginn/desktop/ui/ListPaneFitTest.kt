package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.ArchivedSession
import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.ProjectLead
import com.silencelen.huginn.data.ProjectLive
import com.silencelen.huginn.data.ProjectRow
import com.silencelen.huginn.data.Scratchpad
import com.silencelen.huginn.data.Session
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.desktop.DesktopSettings
import com.silencelen.huginn.desktop.Presence
import com.silencelen.huginn.desktop.Splitter
import com.silencelen.huginn.desktop.ui.common.ChatVerbs
import com.silencelen.huginn.desktop.ui.common.Selection
import com.silencelen.huginn.desktop.ui.common.SessionVerbs
import com.silencelen.huginn.ui.ScratchpadListView
import com.silencelen.huginn.ui.UpdateNoticeCard
import com.silencelen.huginn.ui.theme.HuginnTheme
import com.silencelen.huginn.ui.theme.MonoStyleDesktop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every view the left-hand list pane holds, rendered in the real app theme at the
 * widths the splitter allows, with long, realistic content — and no text in any
 * of them squeezed to a column of letters.
 *
 * ⚠ THE BUG (owner, 2026-10-30): the "Huginn 2.8.0 is ready" card put Install and
 * restart, Later and Don't remind me in one Row. At the pane's default 320 dp the
 * first two took their full width and the last was measured into what was left,
 * so "Don't remind me" wrapped one or two letters to a line, down the card.
 *
 * The signature checked here is that one: a text laid out over several lines with
 * a non-final line of three characters or fewer. A label that is ellipsized or
 * wraps between words is a design choice; a word stood on end never is. Nor is
 * a one-line label cut off mid-glyph with no ellipsis ("ctx 91%" drawn as "c(").
 * Set
 * `-DlistPaneShots=<dir>` (or the env var LIST_PANE_SHOTS) to also write a PNG of
 * every view at every width, for looking at.
 */
@OptIn(ExperimentalTestApi::class)
class ListPaneFitTest {

    private val widths = listOf(Splitter.MIN.toInt(), 260, Splitter.DEFAULT.toInt())
    private val shots: File? = (System.getProperty("listPaneShots") ?: System.getenv("LIST_PANE_SHOTS"))
        ?.let { File(it).apply { mkdirs() } }

    private class Squeezed(val view: String, val width: Int, val text: String, val lines: List<String>) {
        override fun toString() = "$view @ ${width}dp: \"$text\" drawn as ${lines.joinToString(" | ") { "\"$it\"" }}"
    }

    // ------------------------------------------------------------- fixtures

    private val sessions = listOf(
        Session(name = "huginnfeature", state = "running", title = "Ship the compact question card and the follow-ups", contextPercent = 64, model = "claude-opus-5-5", pendingSends = 2),
        Session(name = "statuspage-escalate-incident-194", state = "attention", title = "Investigate the rack power event and write the incident chronology"),
        Session(name = "pwer", state = "idle", title = "Power outage deep dive", contextPercent = 91),
        Session(name = "a", state = "idle"),
        Session(name = "clearance-w4-tooling-and-release-evidence", state = "running", title = "DoD evidence for rc2 throttled rows"),
    )
    private val archives = listOf(
        ArchivedSession(id = "ar1", tmuxName = "andvari-0-29-1-cut", title = "Cut andvari 0.29.1 and run the publish ceremony on the release box", archivedAt = 1_793_000_000_000),
        ArchivedSession(id = "ar2", tmuxName = "x", title = null, archivedAt = 1_793_100_000_000),
    )
    private val chats = listOf(
        Chat(id = "c1", title = "What changed in the Proxmox 9 networking stack, and does it affect the bridge VLAN-aware setting?", lastSnippet = "The short answer is no, but there is one case…", running = true, pending = 1),
        Chat(id = "c2", title = "UPS sizing", hostName = "a-very-long-device-name-for-the-local-model", mode = "act"),
        Chat(id = "c3", title = null, lastSnippet = "untitled chat"),
    ) + (4..40).map { Chat(id = "c$it", title = "Chat number $it about something") }
    private val pads = listOf(
        Scratchpad(id = "p1", name = "main", main = true, size = 12_000, updatedAt = 1_793_000_000_000),
        Scratchpad(id = "p2", name = "A very long scratchpad name that a reader typed without thinking about width", size = 900),
        Scratchpad(id = "p3", name = "x", size = 3),
    )
    private val projects = listOf(
        ProjectRow(id = "j1", name = "Clearance — keycard game release candidate two", slug = "clearance", kind = "team", status = "active",
            memberCount = 4, alive = 4, busy = 2, waiting = 1, lead = ProjectLead(name = "clearance-lead", claudeName = "clearance/lead"),
            manifestSummary = "4 members: server, client, tooling, docs — evidence gathering for DoD rc2"),
        ProjectRow(id = "j2", name = "OSID", slug = "osid", kind = "team", status = "paused", memberCount = 2, alive = 0),
    )
    private val members = mapOf(
        "j1" to listOf(
            ProjectLive(role = "lead", name = "clearance-lead", claudeName = "clearance/lead", alive = true, present = true, lead = true, state = "running"),
            ProjectLive(role = "w4-tooling-and-release-evidence", name = "clearance-w4-tooling", alive = true, present = true, needsYou = true, waitingFor = "permission"),
            ProjectLive(role = "w2", name = "clearance-w2-server", alive = false, present = false, title = "Server lane"),
        ),
    )

    private val chatVerbs = ChatVerbs({}, {}, {}, {}, {})
    private val sessionVerbs = SessionVerbs(
        open = {}, rename = {}, interrupt = {}, copyName = {}, compact = {}, softEnd = {}, archive = {}, kill = {},
    )

    // ------------------------------------------------------------- harness

    private fun views(store: AppStore): List<Pair<String, @Composable () -> Unit>> = listOf(
        "update-install" to { UpdateNoticeCard("2.8.0", "A session's Conversation tab shows a compact question card again for a request the server has bound", true, "", null, {}, {}, {}, {}) },
        "update-copy" to {
            UpdateNoticeCard("2.8.0", "Fixes", false, "Downloaded to ~/Downloads/huginn-desktop_2.8.0_amd64.deb — install it with: sudo apt install ./huginn-desktop_2.8.0_amd64.deb",
                "sudo apt install ./huginn-desktop_2.8.0_amd64.deb", {}, {}, {}, {})
        },
        "sessions" to {
            SessionsList(sessions, true, "huginnfeature", Selection(), {}, {}, {}, sessionVerbs,
                archives = archives, archiveAvailable = true, onViewArchive = {}, inProjects = 7, projectCount = 2, onOpenProjects = {})
        },
        "sessions-selected" to {
            SessionsList(sessions, true, "huginnfeature", Selection(sessions.take(3).map { it.name }.toSet()), {}, {}, {}, sessionVerbs,
                archives = archives, archiveAvailable = true, inProjects = 7, projectCount = 2, onOpenProjects = {})
        },
        "sessions-empty" to { SessionsList(emptyList(), true, null, Selection(), {}, {}, {}, sessionVerbs, archiveAvailable = true, inProjects = 3, projectCount = 1, onOpenProjects = {}) },
        "chats" to { ChatsList(chats, true, "c1", Selection(), {}, {}, {}, onNewLocal = {}, verbs = chatVerbs) },
        "chats-selected" to { ChatsList(chats, true, "c1", Selection(chats.take(12).map { it.id }.toSet()), {}, {}, {}, onNewLocal = {}, verbs = chatVerbs) },
        "chats-empty" to { ChatsList(emptyList(), true, null, Selection(), {}, {}, {}, onNewLocal = {}, verbs = chatVerbs) },
        "scratchpads" to { ScratchpadListView(pads, "p2", 1_793_400_000_000, {}, {}, {}) },
        "projects" to { ProjectsList(store) },
        "settings-nav" to { SettingsNavPane(store, store.settingsPane) },
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> AppStore.seed(field: String, value: T) {
        val f = AppStore::class.java.getDeclaredField(field).apply { isAccessible = true }
        (f.get(this) as MutableStateFlow<T>).value = value
    }

    private fun squeezedIn(view: String, width: Int, root: SemanticsNode): List<Squeezed> {
        val found = mutableListOf<Squeezed>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { t ->
                val layouts = mutableListOf<TextLayoutResult>()
                n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                layouts.forEach { l ->
                    val text = l.layoutInput.text.text
                    val lines = (0 until l.lineCount).map { text.substring(l.getLineStart(it), l.getLineEnd(it, visibleEnd = true)) }
                    if (lines.size >= 2 && lines.dropLast(1).any { it.trim().length in 1..3 } && text.trim().length > 4) {
                        found += Squeezed(view, width, t.text, lines)
                    }
                    // Cut off with no ellipsis: drawn narrower than its one-line width
                    // while the paragraph is a single line ("ctx 91%" as "c(").
                    val clipped = l.lineCount == 1 && l.size.width + 1 < l.multiParagraph.intrinsics.maxIntrinsicWidth &&
                        // Declared, not measured: Skia's isLineEllipsized says false for
                        // a line it visibly ended with "…".
                        l.layoutInput.overflow != TextOverflow.Ellipsis
                    if (clipped) found += Squeezed(view, width, t.text, listOf("clipped at ${l.size.width}px of ${l.multiParagraph.intrinsics.maxIntrinsicWidth.toInt()}px"))
                }
            }
            n.children.forEach(::walk)
        }
        walk(root)
        return found
    }

    @Test
    fun `no view in the list pane stands a word on end at any width it can be`() {
        val dir = Files.createTempDirectory("listpane").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = AppStore(DesktopSettings(File(dir, "settings.json")), Presence(), scope)
        store.seed("_projects", projects)
        store.seed("_projectMembers", members)
        store.seed("_projectsExpanded", setOf("j1"))
        store.seed("_pads", pads)
        val all = mutableListOf<Squeezed>()
        try {
            for ((name, content) in views(store)) for (w in widths) {
                runComposeUiTest {
                    setContent {
                        HuginnTheme(darkTheme = true, monoStyle = MonoStyleDesktop, rootSurface = true) {
                            Box(Modifier.fillMaxSize()) {
                                Column(Modifier.width(w.dp).height(740.dp).testTag("pane")) { content() }
                            }
                        }
                    }
                    waitForIdle()
                    onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
                        .forEach { all += squeezedIn(name, w, it) }
                    shots?.let { ImageIO.write(onNodeWithTag("pane").captureToImage().toAwtImage(), "png", File(it, "$name-$w.png")) }
                }
            }
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
        all.forEach { println("SQUEEZED $it") }
        assertTrue(all.isEmpty(), "words stood on end:\n" + all.joinToString("\n"))
    }
}
