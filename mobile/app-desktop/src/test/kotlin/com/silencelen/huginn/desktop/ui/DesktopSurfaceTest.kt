package com.silencelen.huginn.desktop.ui

import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.Session
import com.silencelen.huginn.desktop.Splitter
import com.silencelen.huginn.desktop.WindowLayout
import com.silencelen.huginn.desktop.ui.common.ChatVerbs
import com.silencelen.huginn.desktop.ui.common.HuginnMenuItem
import com.silencelen.huginn.desktop.ui.common.Selection
import com.silencelen.huginn.desktop.ui.common.SessionVerbs
import com.silencelen.huginn.desktop.ui.common.bgWorkTip
import com.silencelen.huginn.desktop.ui.common.chatMenu
import com.silencelen.huginn.desktop.ui.common.chatStateTip
import com.silencelen.huginn.desktop.ui.common.clickSelection
import com.silencelen.huginn.desktop.ui.common.connectionTip
import com.silencelen.huginn.desktop.ui.common.humanDuration
import com.silencelen.huginn.desktop.ui.common.labelsOf
import com.silencelen.huginn.desktop.ui.common.opensOnClick
import com.silencelen.huginn.desktop.ui.common.railCountTip
import com.silencelen.huginn.desktop.ui.common.sessionMenu
import com.silencelen.huginn.desktop.ui.common.sessionStateTip
import com.silencelen.huginn.desktop.ui.common.timeTip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pure half of the desktop-shaped surfaces: what a menu offers, what a tooltip
 * says, what a modified click selects, and where a restored window lands.
 *
 * All four are decisions rather than drawing, and all four fail SILENTLY when they
 * are wrong — a menu that offers "Delete" and removes four rows, a tooltip that
 * invents a duration for a timestamp the daemon never sent, a Shift-click that
 * selects an arbitrary block, a window restored onto a monitor that is not there.
 * None of those would show up in a screenshot.
 *
 * NOTE the kotlin.test argument order: (expected, actual, message). It is the
 * reverse of JUnit's, three Strings compile clean either way, and that mistake is
 * already on the project's trap list.
 */
class DesktopSurfaceTest {

    private fun chat(
        id: String = "c1",
        title: String? = "A chat",
        running: Boolean = false,
        pending: Int = 0,
        turns: Int = 0,
        updatedAt: Long = 0,
        mode: String = "ask",
    ) = Chat(
        id = id, title = title, mode = mode, running = running,
        pending = pending, turns = turns, updatedAt = updatedAt,
    )

    private fun session(
        name: String = "s1",
        state: String? = "idle",
        stateSince: Long? = null,
        bgShells: Int = 0,
        bgAgents: Int = 0,
        bgTask: String? = null,
    ) = Session(
        name = name, state = state, stateSince = stateSince,
        bgShells = bgShells, bgAgents = bgAgents, bgTask = bgTask,
    )

    // ------------------------------------------------------------- menus

    @Test
    fun `a chat menu offers stop only while something is running`() {
        assertEquals(
            listOf("Open", "Rename…", "Copy chat id", "Delete"),
            labelsOf(chatMenu(chat(), emptySet(), noChatVerbs())),
        )
        assertEquals(
            listOf("Open", "Rename…", "Stop this run", "Copy chat id", "Delete"),
            labelsOf(chatMenu(chat(running = true), emptySet(), noChatVerbs())),
        )
    }

    @Test
    fun `a multi-selection collapses to one destructive verb that says how many`() {
        // The failure this prevents: a menu reading "Delete" that removes four
        // things because the row happened to be inside a selection.
        val items = chatMenu(chat(id = "b"), setOf("a", "b", "c"), noChatVerbs())
        assertEquals(listOf("Delete 3 chats"), labelsOf(items))

        val sessions = sessionMenu(session(name = "b"), setOf("a", "b"), noSessionVerbs())
        assertEquals(listOf("Wind down 2 sessions", "End 2 sessions"), labelsOf(sessions))
    }

    @Test
    fun `a selection the clicked row is not part of does not address the selection`() {
        // Right-clicking OUTSIDE the selection is the standard escape hatch, and
        // getting it wrong is the version of this feature that deletes the wrong
        // rows.
        val items = chatMenu(chat(id = "z"), setOf("a", "b", "c"), noChatVerbs())
        assertEquals(listOf("Open", "Rename…", "Copy chat id", "Delete"), labelsOf(items))
    }

    @Test
    fun `destructive rows are marked so the menu can colour them`() {
        val items = chatMenu(chat(running = true), emptySet(), noChatVerbs())
        val destructive = items.filterIsInstance<HuginnMenuItem>().filter { it.destructive }
        assertEquals(listOf("Delete"), destructive.map { it.label })
    }

    @Test
    fun `a session menu names the key its interrupt sends`() {
        assertEquals(
            listOf("Open", "Rename…", "Interrupt (Esc)", "Copy session name", "Compact context", "Wind down…", "End session"),
            labelsOf(sessionMenu(session(), emptySet(), noSessionVerbs())),
        )
    }

    @Test
    fun `winding down is not marked destructive — only the hard end is`() {
        // Wind down SENDS a message (the session may even stay open, if the
        // wrap-up asks a question); red belongs to the verb that stops things.
        val single = sessionMenu(session(), emptySet(), noSessionVerbs())
            .filterIsInstance<HuginnMenuItem>()
        assertEquals(listOf("End session"), single.filter { it.destructive }.map { it.label })
        val multi = sessionMenu(session(name = "b"), setOf("a", "b"), noSessionVerbs())
            .filterIsInstance<HuginnMenuItem>()
        assertEquals(listOf("End 2 sessions"), multi.filter { it.destructive }.map { it.label })
    }

    @Test
    fun `the destructive verb carries every selected id, not just the clicked one`() {
        var deleted: List<String> = emptyList()
        val verbs = noChatVerbs().copyDelete { deleted = it }
        val items = chatMenu(chat(id = "b"), setOf("a", "b", "c"), verbs)
        items.single().onClick()
        assertEquals(setOf("a", "b", "c"), deleted.toSet())
    }

    // ---------------------------------------------------------- tooltips

    @Test
    fun `durations read the way a person says them`() {
        assertEquals("just now", humanDuration(0))
        assertEquals("just now", humanDuration(44))
        assertEquals("1m", humanDuration(60))
        assertEquals("59m", humanDuration(3599))
        assertEquals("1h", humanDuration(3600))
        assertEquals("2h 10m", humanDuration(7800))
        assertEquals("1d", humanDuration(86_400))
        assertEquals("3d 2h", humanDuration(266_400))
    }

    @Test
    fun `a state tip without a timestamp does not invent a duration`() {
        // The daemon omits stateSince on sessions it has not seen transition, and
        // a tooltip claiming "for 56 years" from a zero is worse than one that
        // says less.
        assertEquals("Waiting on you", sessionStateTip("attention", null, 1_000_000))
        assertEquals("Waiting on you", sessionStateTip("attention", 0, 1_000_000))
        assertEquals("Working · for 5m", sessionStateTip("running", 999_700, 1_000_000))
        assertEquals(
            "No state recorded for this session yet",
            sessionStateTip(null, null, 1_000_000),
        )
    }

    @Test
    fun `a chat tip folds running and queued into one sentence`() {
        assertEquals(
            "Running now · 2 messages queued behind this turn · 7 turns · last activity 1m ago",
            chatStateTip(running = true, pending = 2, turns = 7, updatedAt = 999_940, nowSec = 1_000_000),
        )
        assertEquals("Idle", chatStateTip(false, 0, 0, 0, 1_000_000))
        assertEquals(
            "1 message queued · 1 turn",
            chatStateTip(false, 1, 1, 0, 1_000_000),
        )
    }

    @Test
    fun `background work is empty when there is none`() {
        assertEquals("", bgWorkTip(0, 0, null))
        assertEquals("2 background shells still running", bgWorkTip(2, 0, null))
        assertEquals(
            "1 background shell still running · 3 subagents working\nLongest: pytest -q",
            bgWorkTip(1, 3, "pytest -q"),
        )
        assertEquals("2 background shells still running", bgWorkTip(2, 0, "   "))
    }

    @Test
    fun `the connection tip says what it means for notifications`() {
        val on = connectionTip(true, "http://100.97.198.90:8787", notifyEnabled = true)
        assertTrue(on.startsWith("Watch stream attached to 100.97.198.90:8787"), on)
        assertTrue(on.contains("Notifications arrive here"), on)

        val off = connectionTip(false, "http://100.97.198.90:8787", notifyEnabled = true)
        assertTrue(off.contains("detached"), off)
        assertTrue(off.contains("Telegram"), off)

        val muted = connectionTip(true, "http://localhost:8787", notifyEnabled = false)
        assertTrue(muted.contains("switched off in Settings"), muted)
    }

    @Test
    fun `an absent timestamp produces no tip at all`() {
        assertEquals("", timeTip("Last activity", 0, 1_000_000))
        assertEquals("Last activity 2m ago", timeTip("Last activity", 999_880, 1_000_000))
    }

    @Test
    fun `rail counts describe themselves`() {
        assertEquals("No sessions", railCountTip("sessions", 0, 0, "waiting on you"))
        assertEquals("4 chats", railCountTip("chats", 4, 0, "running"))
        assertEquals("4 chats · 2 running", railCountTip("chats", 4, 2, "running"))
    }

    // --------------------------------------------------------- selection

    private val order = listOf("a", "b", "c", "d", "e")

    @Test
    fun `a plain click selects one row and anchors there`() {
        val s = clickSelection(Selection(), "c", order, ctrl = false, shift = false)
        assertEquals(setOf("c"), s.ids)
        assertEquals("c", s.anchor)
        assertTrue(opensOnClick(ctrl = false, shift = false))
    }

    @Test
    fun `ctrl click adds and removes, and always moves the anchor`() {
        var s = clickSelection(Selection(), "b", order, ctrl = false, shift = false)
        s = clickSelection(s, "d", order, ctrl = true, shift = false)
        assertEquals(setOf("b", "d"), s.ids)
        assertEquals("d", s.anchor)

        // Removing still moves the anchor: "ctrl-click here, shift-click there"
        // has to mean the obvious thing afterwards.
        s = clickSelection(s, "b", order, ctrl = true, shift = false)
        assertEquals(setOf("d"), s.ids)
        assertEquals("b", s.anchor)
    }

    @Test
    fun `shift click takes the range in the list's order, both directions`() {
        var s = clickSelection(Selection(), "b", order, ctrl = false, shift = false)
        s = clickSelection(s, "d", order, ctrl = false, shift = true)
        assertEquals(setOf("b", "c", "d"), s.ids)
        // The anchor stays put so the range can be re-dragged from the same end.
        assertEquals("b", s.anchor)

        s = clickSelection(s, "a", order, ctrl = false, shift = true)
        assertEquals(setOf("a", "b"), s.ids)
    }

    @Test
    fun `shift click with no anchor degrades to selecting the row`() {
        val s = clickSelection(Selection(), "c", order, ctrl = false, shift = true)
        assertEquals(setOf("c"), s.ids)
    }

    @Test
    fun `an anchor for a row that has gone degrades rather than throwing`() {
        // Every 5s poll can remove the anchored row out from under the selection.
        val stale = Selection(setOf("z"), "z")
        assertEquals(setOf("c"), stale.extendTo("c", order).ids)
    }

    @Test
    fun `a poll that removes a row removes it from the selection`() {
        val s = Selection(setOf("a", "b", "c"), "b")
        val after = s.retaining(listOf("a", "c"))
        assertEquals(setOf("a", "c"), after.ids)
        assertEquals(null, after.anchor)
        // Unchanged lists must not allocate a new Selection and retrigger state.
        val same = Selection(setOf("a"), "a")
        assertTrue(same === same.retaining(listOf("a", "b")))
    }

    @Test
    fun `a modified click does not also open the row`() {
        assertFalse(opensOnClick(ctrl = true, shift = false))
        assertFalse(opensOnClick(ctrl = false, shift = true))
    }

    // ------------------------------------------------------------ layout

    @Test
    fun `the list width is clamped from every direction`() {
        assertEquals(Splitter.MIN, Splitter.clamp(10f))
        assertEquals(Splitter.MAX, Splitter.clamp(9_000f))
        assertEquals(320f, Splitter.clamp(320f))
        // A corrupt settings file must not produce a NaN-wide pane, which lays out
        // as a pane of no width at all and cannot be dragged back.
        assertEquals(Splitter.DEFAULT, Splitter.clamp(Float.NaN))
    }

    @Test
    fun `a saved position on a screen that still exists is restored`() {
        val saved = WindowLayout(x = 100, y = 60, w = 1400, h = 900)
        val out = WindowLayout.restore(saved, 1920, 1080)
        assertEquals(WindowLayout(100, 60, 1400, 900), out)
    }

    @Test
    fun `a position off the current screen falls back to centred`() {
        // The monitor was unplugged. Restoring x=2400 on a 1920 desktop is a
        // window that never appears, with the process running the whole time.
        val saved = WindowLayout(x = 2400, y = 60, w = 1400, h = 900)
        val out = WindowLayout.restore(saved, 1920, 1080)
        assertFalse(out.placed)
        assertEquals(1400, out.w)
    }

    @Test
    fun `a window bigger than the screen is fitted to it`() {
        val out = WindowLayout.restore(WindowLayout(0, 0, 3440, 1440), 1920, 1080)
        assertEquals(1920, out.w)
        assertEquals(1080, out.h)
    }

    @Test
    fun `an unmeasurable screen keeps the size and drops the position`() {
        // Headless, or a display the JDK could not measure. A size that is too big
        // is ugly; a position that is off-screen is fatal, so only one is trusted.
        val out = WindowLayout.restore(WindowLayout(300, 300, 1400, 900), 0, 0)
        assertFalse(out.placed)
        assertEquals(1400, out.w)
    }

    @Test
    fun `a nonsense saved size cannot produce an unusable window`() {
        val out = WindowLayout.restore(WindowLayout(0, 0, 40, 20), 1920, 1080)
        assertEquals(WindowLayout.MIN_W, out.w)
        assertEquals(WindowLayout.MIN_H, out.h)
    }

    @Test
    fun `maximized survives the round trip`() {
        val out = WindowLayout.restore(WindowLayout(10, 10, 1280, 840, maximized = true), 1920, 1080)
        assertTrue(out.maximized)
    }

    // ------------------------------------------------------------- fixtures

    private fun noChatVerbs() = ChatVerbs(
        open = {}, rename = {}, stop = {}, copyId = {}, delete = {},
    )

    private fun ChatVerbs.copyDelete(delete: (List<String>) -> Unit) =
        ChatVerbs(open, rename, stop, copyId, delete)

    private fun noSessionVerbs() = SessionVerbs(
        open = {}, rename = {}, interrupt = {}, copyName = {}, compact = {}, softEnd = {}, kill = {},
    )
}
