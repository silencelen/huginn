package com.silencelen.huginn.desktop.ui

import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.Session
import com.silencelen.huginn.desktop.Splitter
import com.silencelen.huginn.desktop.View
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

    // ------------------------------------------------------ the shut seam
    //
    // A collapsed seam keeps its 8dp and its notch, so the way back is exactly
    // where the way out was. What changes is what a DRAG means there: with no pane
    // to resize, an outward pull is the second way to reopen — and it has to be
    // told apart from the twitch that lands on a 1px line while reaching past it.

    @Test
    fun `a pull is measured in distance, not in pointer speed`() {
        // ⚠ THE OBVIOUS IMPLEMENTATION IS WRONG. Thresholding a single drag delta
        // thresholds SPEED: a slow deliberate pull delivers 2px per frame and would
        // never reopen anything, while a flick delivers 40 in one and always would.
        // Small deltas have to add up.
        var pull = 0f
        repeat(5) { pull = Splitter.pull(pull, 3f) }
        assertEquals(15f, pull)
        assertTrue(Splitter.reopens(pull), "five slow frames are still a deliberate pull")

        // …and one decisive frame is enough on its own.
        assertTrue(Splitter.reopens(Splitter.pull(0f, 40f)))
    }

    @Test
    fun `a twitch on the line is not a request for the pane back`() {
        assertFalse(Splitter.reopens(Splitter.pull(0f, 1f)))
        assertFalse(
            Splitter.reopens(Splitter.pull(0f, Splitter.REOPEN_PULL - 1f)),
            "the pull has to leave the seam it started on",
        )
        assertTrue(Splitter.reopens(Splitter.pull(0f, Splitter.REOPEN_PULL)))
    }

    @Test
    fun `going back the other way spends the pull rather than banking it`() {
        // A leftward drag on a seam with nothing to its left is not half of a
        // rightward one. Banking it would mean a wobble adds up to a reopen.
        var pull = 0f
        pull = Splitter.pull(pull, 9f)
        pull = Splitter.pull(pull, -9f)
        assertEquals(0f, pull)
        assertFalse(Splitter.reopens(pull))
        // And the sum starts again from there rather than from where it was.
        pull = Splitter.pull(pull, 5f)
        assertFalse(Splitter.reopens(pull))
    }

    @Test
    fun `the seam exists exactly where a list does`() {
        // ONE definition, asked by the frame that draws the notch and by the window
        // that binds Ctrl+B. Two copies would drift, and the one that drifts is
        // always the one that decides whether a key press does anything.
        assertTrue(Splitter.showsList(View.CHATS))
        assertTrue(Splitter.showsList(View.SESSIONS))
        assertTrue(Splitter.showsList(View.SCRATCHPADS), "pages are a list and a detail too")
        assertFalse(Splitter.showsList(View.ROUNDS))
        assertFalse(Splitter.showsList(View.DEVICES))
        assertFalse(Splitter.showsList(View.STATUS))
        assertFalse(Splitter.showsList(View.SETTINGS))
    }

    @Test
    fun `a shut list gives its width to the detail pane`() {
        // ⚠ WHAT THIS PREVENTS is invisible: the page panel needs 900dp of DETAIL
        // pane, and subtracting a collapsed list's stored width anyway is how a
        // 1280dp window with the list hidden gets told it has 908dp when it has
        // 1228 — or, one size down, refuses to draw a panel with the room for it
        // plainly on screen.
        assertEquals(
            908f,
            Splitter.detailWidth(windowWidth = 1280f, railWidth = 52f, listWidth = 320f, collapsed = false),
        )
        assertEquals(
            1228f,
            Splitter.detailWidth(windowWidth = 1280f, railWidth = 52f, listWidth = 320f, collapsed = true),
        )
        // The rail is always there; only the list can go.
        assertTrue(
            Splitter.detailWidth(1280f, 52f, Splitter.MAX, collapsed = true) >
                Splitter.detailWidth(1280f, 52f, Splitter.MIN, collapsed = false),
        )
    }

    // ------------------------------------------------------- the page panel
    //
    // ⚠ ESC USED TO CONSULT THE FLAG ALONE. `store.padPanel` says the reader
    // would LIKE the panel; it can be true in Settings, true against a daemon
    // with no pages, and true in a window too narrow to hold one. In all three
    // Escape silently "closed" a panel that was not there instead of leaving the
    // conversation — a key that does nothing visible, so the reader presses it
    // again and nothing happens twice.

    @Test
    fun `the panel is only on screen where all three conditions hold`() {
        assertTrue(
            padPanelShowing(
                open = true, view = View.CHATS, chatOpen = true, sessionOpen = false,
                padsAvailable = true, detailWidthDp = 1200f,
            ),
        )
        assertFalse(
            padPanelShowing(
                open = false, view = View.CHATS, chatOpen = true, sessionOpen = false,
                padsAvailable = true, detailWidthDp = 1200f,
            ),
            "nobody asked for it",
        )
        assertFalse(
            padPanelShowing(
                open = true, view = View.SETTINGS, chatOpen = true, sessionOpen = true,
                padsAvailable = true, detailWidthDp = 1200f,
            ),
            "there is no conversation in Settings for it to sit beside",
        )
        assertFalse(
            padPanelShowing(
                open = true, view = View.CHATS, chatOpen = true, sessionOpen = false,
                padsAvailable = false, detailWidthDp = 1200f,
            ),
            "a daemon with no pages has nothing to draw",
        )
        assertFalse(
            padPanelShowing(
                open = true, view = View.CHATS, chatOpen = true, sessionOpen = false,
                padsAvailable = null, detailWidthDp = 1200f,
            ),
            "null is the probe not having answered, which is not a yes",
        )
        assertFalse(
            padPanelShowing(
                open = true, view = View.CHATS, chatOpen = true, sessionOpen = false,
                padsAvailable = true, detailWidthDp = 700f,
            ),
            "360dp out of this pane would leave no conversation worth reading",
        )
    }

    @Test
    fun `a conversation view with nothing open is not a home for it`() {
        // The detail pane is an empty state, not a chat: a panel beside nothing is
        // two columns of nothing.
        assertFalse(padPanelHasHome(View.CHATS, chatOpen = false, sessionOpen = true, padsAvailable = true))
        assertFalse(padPanelHasHome(View.SESSIONS, chatOpen = true, sessionOpen = false, padsAvailable = true))
        assertTrue(padPanelHasHome(View.SESSIONS, chatOpen = false, sessionOpen = true, padsAvailable = true))
    }

    @Test
    fun `the width rule is the render site's own number`() {
        assertTrue(padPanelFits(PANEL_MIN_WINDOW_DP.toFloat()))
        assertFalse(padPanelFits(PANEL_MIN_WINDOW_DP - 1f))
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
