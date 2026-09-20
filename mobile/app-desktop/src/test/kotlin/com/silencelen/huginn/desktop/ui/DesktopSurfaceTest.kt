package com.silencelen.huginn.desktop.ui

import com.silencelen.huginn.ui.VerbTone
import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.QuickActions
import com.silencelen.huginn.data.Session
import com.silencelen.huginn.ui.QuickActionRules
import com.silencelen.huginn.ui.SelectionAction
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.silencelen.huginn.desktop.Screen
import com.silencelen.huginn.desktop.Splitter
import com.silencelen.huginn.desktop.View
import com.silencelen.huginn.desktop.WindowLayout
import com.silencelen.huginn.desktop.ui.common.ChatVerbs
import com.silencelen.huginn.desktop.ui.common.HuginnMenuItem
import com.silencelen.huginn.desktop.ui.common.Selection
import com.silencelen.huginn.desktop.ui.common.SelectionVerbs
import com.silencelen.huginn.desktop.ui.common.SessionVerbs
import com.silencelen.huginn.desktop.ui.common.bgWorkTip
import com.silencelen.huginn.desktop.ui.common.chatMenu
import com.silencelen.huginn.desktop.ui.common.chatStateTip
import com.silencelen.huginn.desktop.ui.common.clickSelection
import com.silencelen.huginn.desktop.ui.common.connectionTip
import com.silencelen.huginn.desktop.ui.common.humanDuration
import com.silencelen.huginn.desktop.ui.common.rowTimeReveal
import com.silencelen.huginn.desktop.ui.common.labelsOf
import com.silencelen.huginn.desktop.ui.common.noChatOpenCopy
import com.silencelen.huginn.desktop.ui.common.noSessionOpenCopy
import com.silencelen.huginn.desktop.ui.common.opensOnClick
import com.silencelen.huginn.desktop.ui.common.railCountTip
import com.silencelen.huginn.desktop.ui.common.selectionMenu
import com.silencelen.huginn.desktop.ui.common.sessionMenu
import com.silencelen.huginn.desktop.ui.common.sessionStateTip
import com.silencelen.huginn.desktop.ui.common.timeTip
import kotlin.test.Test
import kotlin.test.assertEquals
import com.silencelen.huginn.ui.TimeWords
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
        assertEquals(listOf("Wrap up 2 sessions", "Kill 2 sessions"), labelsOf(sessions))
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
        val destructive = items.filterIsInstance<HuginnMenuItem>().filter { it.tone == VerbTone.DESTRUCTIVE }
        assertEquals(listOf("Delete"), destructive.map { it.label })
        // And nothing in a chat menu claims the soft red — that tone belongs to
        // one verb in the whole product.
        assertEquals(
            emptyList(),
            items.filterIsInstance<HuginnMenuItem>().filter { it.tone == VerbTone.SOFT }.map { it.label },
        )
    }

    @Test
    fun `a session menu names the key its interrupt sends`() {
        assertEquals(
            listOf("Open", "Rename…", "Interrupt (Esc)", "Copy session name", "Compact context", "Wrap up", "Kill session"),
            labelsOf(sessionMenu(session(), emptySet(), noSessionVerbs())),
        )
    }

    @Test
    fun `archive sits between the wrap-up and the kill, and only on a daemon that has it`() {
        // ⚠ THE FEATURE PROBE IS THE MENU. A daemon without archive answers the
        // route 404, the store's flag goes false, and the verb arrives null —
        // which must remove the ITEM, not offer one whose only outcome is an
        // error. This is the assertion that says the null actually does that.
        assertEquals(
            listOf("Open", "Rename…", "Interrupt (Esc)", "Copy session name", "Compact context", "Wrap up", "Kill session"),
            labelsOf(sessionMenu(session(), emptySet(), noSessionVerbs())),
            "an older daemon must show no Archive at all",
        )
        // With it: between the two verbs it sits between in meaning — a wrap-up
        // that leaves something behind.
        assertEquals(
            listOf("Open", "Rename…", "Interrupt (Esc)", "Copy session name", "Compact context", "Wrap up", "Archive…", "Kill session"),
            labelsOf(sessionMenu(session(), emptySet(), noSessionVerbs(archive = {}))),
        )
        val multi = sessionMenu(session(name = "b"), setOf("a", "b"), noSessionVerbs(archive = {}))
        assertEquals(
            listOf("Wrap up 2 sessions", "Archive 2 sessions…", "Kill 2 sessions"),
            labelsOf(multi),
            "a multi-selection says how many, like every other verb here",
        )
    }

    @Test
    fun `archiving claims NEITHER red — it keeps everything it ends`() {
        // Red is for the verbs that lose something. An archive ends a session and
        // keeps its directory, its conversation and the command that brings it
        // back; colouring it like a kill — or even like the wrap-up — would teach
        // people to avoid the safest way to finish with a session.
        val items = sessionMenu(session(), emptySet(), noSessionVerbs(archive = {}))
            .filterIsInstance<HuginnMenuItem>()
        assertEquals(listOf("Kill session"), items.filter { it.tone == VerbTone.DESTRUCTIVE }.map { it.label })
        assertEquals(VerbTone.PLAIN, items.first { it.label == "Archive…" }.tone)
    }

    @Test
    fun `the archive verb carries every selected name, not just the clicked one`() {
        var archived: List<String> = emptyList()
        val items = sessionMenu(session(name = "b"), setOf("a", "b"), noSessionVerbs(archive = { archived = it }))
            .filterIsInstance<HuginnMenuItem>()
        items.first { it.label.startsWith("Archive") }.onClick()
        assertEquals(setOf("a", "b"), archived.toSet())
    }

    @Test
    fun `the two ending verbs take the two reds, and nothing else takes either`() {
        // THE PAIR, AND THE DIFFERENCE BETWEEN THEM. Wrap up SENDS a message (the
        // session may even stay open, if the wrap-up asks a question), so it gets
        // the lighter red; the full red belongs to the verb that stops things and
        // can lose work. Both are in the destructive palette because they are the
        // two ways this menu ends a session, and drawing one of them as an
        // ordinary row is what let people pick the wrong one.
        val single = sessionMenu(session(), emptySet(), noSessionVerbs(archive = {}))
            .filterIsInstance<HuginnMenuItem>()
        assertEquals(listOf("Wrap up"), single.filter { it.tone == VerbTone.SOFT }.map { it.label })
        assertEquals(listOf("Kill session"), single.filter { it.tone == VerbTone.DESTRUCTIVE }.map { it.label })
        // Everything above them stays plain — a menu where half the rows are red
        // says nothing at all.
        assertEquals(
            listOf("Open", "Rename…", "Interrupt (Esc)", "Copy session name", "Compact context", "Archive…"),
            single.filter { it.tone == VerbTone.PLAIN }.map { it.label },
        )
        val multi = sessionMenu(session(name = "b"), setOf("a", "b"), noSessionVerbs())
            .filterIsInstance<HuginnMenuItem>()
        assertEquals(listOf("Wrap up 2 sessions"), multi.filter { it.tone == VerbTone.SOFT }.map { it.label })
        assertEquals(listOf("Kill 2 sessions"), multi.filter { it.tone == VerbTone.DESTRUCTIVE }.map { it.label })
    }

    @Test
    fun `the destructive verb carries every selected id, not just the clicked one`() {
        var deleted: List<String> = emptyList()
        val verbs = noChatVerbs().copyDelete { deleted = it }
        val items = chatMenu(chat(id = "b"), setOf("a", "b", "c"), verbs)
        items.single().onClick()
        assertEquals(setOf("a", "b", "c"), deleted.toSet())
    }

    // ------------------------------------------------- selected text

    private val hostActions = QuickActions(
        rev = 3,
        explain = "Explain this, briefly:\n\n{selection}",
        execute = "Run this and show me the output:\n\n{selection}",
        askInNewChat = "{selection}\n\nWhat is going on here?",
        quote = "",
    )

    private fun noSelectionVerbs(
        onExplain: (String) -> Unit = {},
        onExecute: (String) -> Unit = {},
        onQuote: (String) -> Unit = {},
        onAsk: (String) -> Unit = {},
    ) = SelectionVerbs(explain = onExplain, execute = onExecute, quote = onQuote, askInNewChat = onAsk)

    @Test
    fun `selected text offers the four verbs, in the order the phone shows them`() {
        assertEquals(
            listOf("Explain", "Execute", "Quote", "Ask in new chat"),
            labelsOf(selectionMenu("ls -la", hostActions, noSelectionVerbs())),
        )
    }

    @Test
    fun `a daemon with no quick actions offers Quote alone`() {
        // The feature probe, in the only form this menu ever sees it. Quote is the
        // one verb whose text this client writes itself; the other three would be
        // offering to compose a message out of wording nobody has.
        assertEquals(
            listOf("Quote"),
            labelsOf(selectionMenu("ls -la", null, noSelectionVerbs())),
        )
    }

    @Test
    fun `a blank selection offers nothing at all`() {
        // An EMPTY list rather than a disabled row: the toolkit's own Copy is
        // drawn from the same menu, and anything returned here is prepended to it.
        assertTrue(selectionMenu("", hostActions, noSelectionVerbs()).isEmpty())
        assertTrue(selectionMenu("   \n ", hostActions, noSelectionVerbs()).isEmpty())
    }

    @Test
    fun `a selection past the cap offers nothing`() {
        // A drag that ran away down a long transcript. The composer it would land
        // in is the one the reader then has to clear by hand.
        val huge = "z".repeat(QuickActionRules.SELECTION_MAX + 1)
        assertTrue(selectionMenu(huge, hostActions, noSelectionVerbs()).isEmpty())
        assertEquals(1, labelsOf(selectionMenu("z".repeat(QuickActionRules.SELECTION_MAX), null, noSelectionVerbs())).size)
    }

    @Test
    fun `no selection verb is marked destructive`() {
        // Every one of them stages text in a composer. Red in this menu would be
        // claiming one of them does something that cannot be undone.
        val items = selectionMenu("ls -la", hostActions, noSelectionVerbs())
            .filterIsInstance<HuginnMenuItem>()
        assertEquals(emptyList(), items.filter { it.tone == VerbTone.DESTRUCTIVE }.map { it.label })
    }

    @Test
    fun `each verb is handed the text that was selected`() {
        val got = mutableMapOf<String, String>()
        val items = selectionMenu(
            "ls -la",
            hostActions,
            noSelectionVerbs(
                onExplain = { got["explain"] = it },
                onExecute = { got["execute"] = it },
                onQuote = { got["quote"] = it },
                onAsk = { got["ask"] = it },
            ),
        )
        items.forEach { it.onClick() }
        assertEquals(
            mapOf("explain" to "ls -la", "execute" to "ls -la", "quote" to "ls -la", "ask" to "ls -la"),
            got,
        )
    }

    @Test
    fun `each verb reads the selection when it is clicked, not when the menu was built`() {
        // THE 1.1.0 BUG, at the level it actually lived. The toolkit caches a
        // context menu's item list — `ContextMenuData.allItems` is `by lazy` and
        // is only rebuilt when the area recomposes, which a new drag-selection
        // does not do — so the rows built over the FIRST selection of a session
        // were the rows every later right-click reused. With the text captured
        // into the row, Quote staged what had been highlighted minutes earlier
        // (and a right-click over nothing still staged it). Reading through the
        // function inside the click is what makes a stale list harmless.
        var live = "golf hotel india juliett."
        val got = mutableMapOf<String, String>()
        val items = selectionMenu(
            { live },
            hostActions,
            noSelectionVerbs(
                onExplain = { got["explain"] = it },
                onExecute = { got["execute"] = it },
                onQuote = { got["quote"] = it },
                onAsk = { got["ask"] = it },
            ),
        )
        live = "Kilo lima mike november oscar"
        items.forEach { it.onClick() }
        assertEquals(
            mapOf(
                "explain" to "Kilo lima mike november oscar",
                "execute" to "Kilo lima mike november oscar",
                "quote" to "Kilo lima mike november oscar",
                "ask" to "Kilo lima mike november oscar",
            ),
            got,
        )
    }

    @Test
    fun `a verb whose selection is gone by click time stages nothing, not the old text`() {
        // The other face of the same stale list: the reader cleared the selection
        // and right-clicked anyway. The honest answer is an empty string, which
        // the staging rule already refuses — never the last thing they highlighted.
        var live = "o charlie delta ec"
        var quoted: String? = null
        val items = selectionMenu({ live }, null, noSelectionVerbs(onQuote = { quoted = it }))
        live = ""
        items.single().onClick()
        assertEquals("", quoted)
        assertEquals("", QuickActionRules.textFor(SelectionAction.QUOTE, null, quoted.orEmpty()))
    }

    @Test
    fun `the quote frame carries the highlighted text verbatim, across rows and inside code`() {
        // What the owner reads back in the composer, for the three drags the fix
        // is proved against: inside one row, across two, and inside a code block.
        assertEquals("> o charlie delta ec", QuickActionRules.quote("", "o charlie delta ec"))
        assertEquals(
            "> golf hotel india juliett.\n> Kilo lima mike november oscar pa",
            QuickActionRules.quote("", "golf hotel india juliett.\nKilo lima mike november oscar pa"),
        )
        // Indentation inside a code block is the only part that matters, so it
        // survives the frame untouched.
        assertEquals("> echo one\n>   echo two", QuickActionRules.quote("", "echo one\n  echo two"))
    }

    // ---------------------------------------------------------- tooltips

    @Test
    fun `durations read the way a person says them`() {
        // ⚠ NO "just now" BAND. This measures a SPAN and its one caller reads
        // "Working · for <this>" — "for just now" is not a sentence, and the
        // band's other edge was worse: 45..59s fell straight through to
        // "${sec / 60}m" and drew "Working · for 0m", 15 seconds of every hour.
        assertEquals("1m", humanDuration(0))
        assertEquals("1m", humanDuration(44))
        assertEquals("1m", humanDuration(45), "45s was the start of the 0m band")
        assertEquals("1m", humanDuration(59), "59s was the end of it")
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
        // The tooltip that started this: a state entered 50 seconds ago.
        assertEquals("Working · for 1m", sessionStateTip("running", 999_950, 1_000_000))
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

    /**
     * ⚠ THE NAME FIRST, THEN THE ADDRESS. This tip is the one liveness mark in
     * the frame and it printed a stripped URL and nothing else — so the reader
     * was told the stream had detached from an octet, rather than from the path
     * they know by name. An install that has pinned nothing still gets the
     * address alone rather than a dangling separator.
     */
    @Test
    fun `the connection tip leads with the route's name when it has one`() {
        val named = connectionTip(true, "http://192.168.2.117:8787", notifyEnabled = true, routeName = "the mesh")
        assertTrue(named.startsWith("Watch stream attached to the mesh · 192.168.2.117:8787"), named)

        val unnamed = connectionTip(true, "http://192.168.2.117:8787", notifyEnabled = true, routeName = "")
        assertTrue(unnamed.startsWith("Watch stream attached to 192.168.2.117:8787"), unnamed)
        assertTrue(!unnamed.contains(" · "), "no dangling separator when there is no name: $unnamed")
    }

    @Test
    fun `an absent timestamp produces no tip at all`() {
        assertEquals("", timeTip("Last activity", 0, 1_000_000))
        assertEquals("Last activity 2m ago", timeTip("Last activity", 999_880, 1_000_000))
        // The two shapes the daemon actually sends for "never": a null and a
        // zero. Neither may reach a formatter that would date them to 1970.
        assertEquals("", timeTip("Last activity", -1, 1_000_000))
    }

    /**
     * ⚠ THE TOOLTIPS NO LONGER HOLD A VOCABULARY. `timeTip` and the last-activity
     * clause of `chatStateTip` are wall-clock stamps, so they read from
     * [TimeWords] like every other row on both clients; what they say here must
     * be exactly what `TimeWordsTest` pins, or the desktop has quietly grown a
     * sixth dialect again.
     */
    @Test
    fun `timestamp tips speak the shared vocabulary`() {
        val now = 1_000_000L
        assertEquals("Last activity ${TimeWords.ago(999_880, now * 1000)}", timeTip("Last activity", 999_880, now))
        assertEquals("Last activity 3 days ago", timeTip("Last activity", now - 3 * 86_400, now))
        assertEquals("Last activity yesterday", timeTip("Last activity", now - 30 * 3600, now))
        // The duration register is NOT what a stamp uses: "2h 10m ago" was the
        // old wording and is exactly the drift being removed.
        assertEquals("Last activity 2h ago", timeTip("Last activity", now - 7_800, now))
    }

    @Test
    fun `a chat tip dates its last activity like every other row`() {
        val now = 1_000_000L
        assertEquals(
            "Idle · 2 turns · last activity 3 days ago",
            chatStateTip(false, 0, 2, now - 3 * 86_400, now),
        )
    }

    /**
     * The transcript's hover reveal. EXACT rather than relative: a reader who
     * hovers a message is asking when it was sent, and "2h ago" is the answer
     * they already had from the row. Empty for a missing stamp, which is what
     * makes [Tip] draw no popup at all.
     */
    @Test
    fun `the row reveal is an exact date, and nothing at all when there is no stamp`() {
        assertEquals("", rowTimeReveal(null))
        assertEquals("", rowTimeReveal(0))
        assertEquals("", rowTimeReveal(-1))
        val reveal = rowTimeReveal(1_789_655_520)
        assertFalse(reveal.contains("1970"), reveal)
        assertTrue(reveal.contains("2026"), reveal)
        assertTrue(reveal.contains("September"), reveal)
        // It carries a weekday and a clock time, which is what "exact" means here.
        assertTrue(reveal.substringBefore(" ") in DAYS, reveal)
        assertTrue(Regex("\\d{1,2}:\\d{2}").containsMatchIn(reveal), reveal)
    }

    private val DAYS = listOf(
        "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
    )

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
    fun `a window left on a second monitor comes back to it`() {
        // The shipped rule judged x against ONE rectangle — on Windows,
        // Toolkit.screenSize is the PRIMARY monitor, not the virtual desktop —
        // so every position on a secondary read as "the display it remembers is
        // gone", and the debounced writer then persisted the centred primary
        // coordinates. The owner's placement was destroyed on the first launch.
        val desk = listOf(Screen(0, 0, 1920, 1080), Screen(1920, 0, 2560, 1440))
        val out = WindowLayout.restore(WindowLayout(x = 2400, y = 60, w = 1400, h = 900), desk)
        assertTrue(out.placed, "a position on the second monitor must survive")
        assertEquals(2400, out.x)
        assertEquals(60, out.y)
    }

    @Test
    fun `a monitor to the LEFT has negative coordinates and is still a monitor`() {
        val desk = listOf(Screen(0, 0, 1920, 1080), Screen(-1920, 0, 1920, 1080))
        val out = WindowLayout.restore(WindowLayout(x = -1200, y = 100, w = 1280, h = 840), desk)
        assertTrue(out.placed, "x is signed on a virtual desktop")
        assertEquals(-1200, out.x)
    }

    @Test
    fun `a window is sized against the desk it is on, not the smallest screen`() {
        // A 2560x1400 window lived on the big secondary; clamping it to the
        // primary's 1920x1080 shrinks it every launch.
        val desk = listOf(Screen(0, 0, 1920, 1080), Screen(1920, 0, 2560, 1440))
        val out = WindowLayout.restore(WindowLayout(x = 2000, y = 20, w = 2560, h = 1400), desk)
        assertEquals(2560, out.w)
        assertEquals(1400, out.h)
    }

    @Test
    fun `a position on a monitor that has since been unplugged is still dropped`() {
        // The union is of the screens that are THERE. Undock the 2560 secondary
        // and the window saved at x=2400 has nowhere to be.
        val out = WindowLayout.restore(
            WindowLayout(x = 2400, y = 60, w = 1400, h = 900),
            listOf(Screen(0, 0, 1920, 1080)),
        )
        assertFalse(out.placed)
        assertEquals(1400, out.w)
    }

    @Test
    fun `no enumerable screen keeps the size and drops the position`() {
        val out = WindowLayout.restore(WindowLayout(300, 300, 1400, 900), emptyList())
        assertFalse(out.placed)
        assertEquals(1400, out.w)
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

    /** Runs one whole gesture through the fold and reports how often it fired. */
    private fun gesture(vararg deltas: Float): Pair<Splitter.Pull, Int> {
        var pull = Splitter.NO_PULL
        var fires = 0
        deltas.forEach { d ->
            val next = Splitter.pull(pull, d)
            if (Splitter.fired(pull, next)) fires++
            pull = next
        }
        return pull to fires
    }

    @Test
    fun `a pull is measured in distance, not in pointer speed`() {
        // ⚠ THE OBVIOUS IMPLEMENTATION IS WRONG. Thresholding a single drag delta
        // thresholds SPEED: a slow deliberate pull delivers 2px per frame and would
        // never reopen anything, while a flick delivers 40 in one and always would.
        // Small deltas have to add up.
        val (slow, slowFires) = gesture(3f, 3f, 3f, 3f, 3f)
        assertTrue(slow.spent, "five slow frames are still a deliberate pull")
        assertEquals(1, slowFires)

        // …and one decisive frame is enough on its own.
        assertTrue(gesture(40f).first.spent)
    }

    @Test
    fun `a twitch on the line is not a request for the pane back`() {
        assertFalse(gesture(1f).first.spent)
        assertFalse(
            gesture(Splitter.REOPEN_PULL - 1f).first.spent,
            "the pull has to leave the seam it started on",
        )
        assertTrue(gesture(Splitter.REOPEN_PULL).first.spent)
    }

    @Test
    fun `going back the other way spends the pull rather than banking it`() {
        // A leftward drag on a seam with nothing to its left is not half of a
        // rightward one. Banking it would mean a wobble adds up to a reopen.
        val (wobble, fires) = gesture(9f, -9f, 5f)
        assertEquals(Splitter.NO_PULL.spent, wobble.spent)
        assertEquals(5f, wobble.travel, "the sum starts again rather than resuming")
        assertEquals(0, fires)
    }

    @Test
    fun `the pull that reopens does not go on to resize`() {
        // ⚠ THE HALF THAT SHIPPED MISSING. A pull is a person moving a pointer, not
        // somebody stopping dead on the 12th pixel: the reopen fired and the rest of
        // the SAME gesture fell through to the resize branch, so a 30px pull brought
        // the pane back AND left the remembered width at 332. Momentum arriving as
        // intent, silently rewriting a number the reader had chosen by dragging.
        val (after, fires) = gesture(6f, 6f, 6f, 6f, 6f)
        assertTrue(after.spent)
        assertEquals(1, fires, "a gesture reopens once, not once per frame past the threshold")
        assertEquals(0f, after.travel, "and banks nothing for the drag after it")
    }

    @Test
    fun `a spent gesture stays spent, whichever way it goes`() {
        val spent = Splitter.pull(Splitter.NO_PULL, 40f)
        assertTrue(spent.spent)
        assertEquals(spent, Splitter.pull(spent, 20f), "more of the same pull changes nothing")
        assertEquals(spent, Splitter.pull(spent, -20f), "and neither does coming back")
        assertFalse(Splitter.fired(spent, Splitter.pull(spent, 20f)), "and it never fires twice")
        // The next GESTURE is what resumes normal service, which is why the render
        // site reinstalls this on every onDragStarted rather than keying it on state.
        assertFalse(Splitter.NO_PULL.spent)
        assertEquals(0f, Splitter.NO_PULL.travel)
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
        // Settings is a list and a detail too, since the redesign: nine categories
        // on the left, one page on the right, through the SAME seam, notch and
        // Ctrl+B the other three use. A second list-pane idiom for one view is how
        // the desktop ended up with a flat 1200-line scroll in the first place.
        assertTrue(Splitter.showsList(View.SETTINGS), "Settings is two panes now")
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

    // ------------------------------------------- what an empty pane may say
    //
    // ⚠ THE STALE SENTENCE IS INVISIBLE. With the list shut, both empty states
    // went on giving directions to it — "pick one on the left", "every tmux
    // session on the host is on the left", "walk the list", "right-click to
    // rename". Nothing crashes and nothing draws wrong; a screenshot looks
    // entirely correct unless you already know the pane is not there. The reader
    // looks left, finds a nav rail, and concludes the client is broken rather
    // than that they closed something.

    @Test
    fun `an empty pane never points at a list that is not there`() {
        listOf(noChatOpenCopy(true), noSessionOpenCopy(true)).forEach { copy ->
            val said = (copy.sentence + " " + copy.routes.joinToString(" ") { "${it.first} ${it.second}" })
                .lowercase()
            assertFalse(said.contains("on the left"), said)
            assertFalse(said.contains("walk the list"), said)
            // The row menu hangs off a LIST ROW; with the list shut there is
            // nothing on screen to open it on.
            assertFalse(said.contains("right-click"), said)
            // And it has to say how to get the pane back, or the copy has simply
            // gone quiet about the one thing the reader needs.
            assertTrue(said.contains("ctrl b"), said)
            assertTrue(said.contains("notch"), said)
        }
    }

    @Test
    fun `the way back it names is a chord the app really has`() {
        // A route row is a promise about a key. Naming one the table does not
        // carry is the same class of dead end as a binding with no keyName.
        val named = (noChatOpenCopy(true).routes + noSessionOpenCopy(true).routes).map { it.first }.toSet()
        val claimed = SHORTCUT_HELP.map { it.first }.toSet()
        named.filter { it.startsWith("Ctrl") }.forEach {
            assertTrue(it in claimed, "$it is offered by an empty pane but is not in the cheat sheet")
        }
    }

    @Test
    fun `with the list on screen the copy is the copy it always was`() {
        // The collapsed variant is an EXCEPTION, not a rewrite. Directions to the
        // pane are the right directions while the pane is there.
        assertTrue(noChatOpenCopy(false).sentence.contains("on the left"))
        assertTrue(noSessionOpenCopy(false).sentence.contains("on the left"))
        assertEquals(3, noChatOpenCopy(false).routes.size)
        assertEquals(3, noSessionOpenCopy(false).routes.size)
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

    // ------------------------------------------------- rows that cannot open

    @Test
    fun `a session name the daemon cannot route to is not openable`() {
        // GET /v1/sessions deliberately publishes every tmux session, including
        // the ones whose names fall outside the daemon's NAME_RE — while every
        // per-session route 404s on them. No client filtered, badged or
        // explained the row, so on the desktop the pane opened and shut
        // instantly with no message, repeatably (and this module has no toast
        // surface to have said anything in).
        assertFalse(sessionAddressable("my project"), "a space is not routable")
        assertFalse(sessionAddressable("sess!"), "nor is punctuation")
        assertFalse(sessionAddressable("-lead"))
        assertFalse(sessionAddressable(""))
        assertFalse(sessionAddressable("a".repeat(51)))
        // tmux rewrites a dot, so a listed name never has one — but if the
        // daemon ever hands one over, it is not addressable either.
        assertFalse(sessionAddressable("api.v2"))
    }

    @Test
    fun `an ordinary name, in any case, opens`() {
        assertTrue(sessionAddressable("jtyper"))
        assertTrue(sessionAddressable("api-v2"))
        assertTrue(sessionAddressable("_scratch_1"))
        // The route regexes are case-permissive, so an uppercase name routes
        // fine and must not be marked broken.
        assertTrue(sessionAddressable("JTyper"))
    }

    @Test
    fun `the row says what to do about it, in tmux's own terms`() {
        assertTrue(UNADDRESSABLE_NOTE.isNotBlank(), "a row that will not open must say why")
        assertTrue("tmux" in UNADDRESSABLE_NOTE, "the fix is in tmux: $UNADDRESSABLE_NOTE")
    }

    // ------------------------------------------------------- the pane clock

    @Test
    fun `a pane's relative-time clock does not start at the epoch`() {
        // RoundsPane seeded its ticking clock at 0 and only wrote a real time
        // from a LaunchedEffect — and compose flushes effects BEFORE the
        // composition that reads them, so the FIRST painted frame of the Rounds
        // list, on every entry to the destination and every return from the
        // editor, drew every relative time against 1970: a round due tomorrow
        // read "in 20715 days", a run from days ago read "just now" (agoMs
        // clamps a negative delta rather than branching on it).
        assertTrue(
            paneClockSeed() > 1_600_000_000_000L,
            "a clock a frame is drawn against must be a real time, not 0",
        )
    }

    // ------------------------------------------------------------ composers

    @Test
    fun `shift-enter replaces the selection whichever way it was made`() {
        // Compose's legacy TextFieldValue path genuinely emits start > end for
        // Shift+Left, Shift+Home, Shift+Up and a right-to-left drag, and hands
        // it to onValueChange UNNORMALISED. Splicing on .start/.end then
        // overlaps rather than replaces: "hello world" with "world" selected
        // backwards became "hello world\nworld", and Shift+Home from the end
        // doubled the whole draft — persisted straight to the drafts book.
        val forward = TextFieldValue("hello world", TextRange(6, 11))
        val backward = TextFieldValue("hello world", TextRange(11, 6))
        assertEquals("hello \n", newlineIn(forward).text)
        assertEquals("hello \n", newlineIn(backward).text, "a reversed selection is the same selection")
        assertEquals(TextRange(7), newlineIn(backward).selection)
    }

    @Test
    fun `shift-enter with no selection splits at the cursor`() {
        val out = newlineIn(TextFieldValue("hello world", TextRange(5)))
        assertEquals("hello\n world", out.text)
        assertEquals(TextRange(6), out.selection)
    }

    @Test
    fun `shift-home from the end does not double the draft`() {
        val out = newlineIn(TextFieldValue("rebuild the index", TextRange(17, 0)))
        assertEquals("\n", out.text)
    }

    // ------------------------------------------------------- session names

    @Test
    fun `a name tmux would silently rewrite is refused while it is typed`() {
        // tmux rewrites '.' to '_' and exits 0, so a session created or renamed
        // with a dot answers to a name nobody typed: the pane opens on the
        // requested name, every route 404s, and the draft that was just moved
        // there is deleted as the pane closes. '-' is untouched and stays legal.
        assertFalse(SESSION_NAME.matches("api.v2"), "a dotted name must not reach tmux")
        assertFalse(SESSION_NAME.matches("my.session"))
        assertFalse(SESSION_NAME.matches("notes.old"))
        assertTrue(SESSION_NAME.matches("api-v2"), "'-' survives tmux untouched")
        assertTrue(SESSION_NAME.matches("jtyper"))
        assertTrue(SESSION_NAME.matches("_scratch_1"))
        assertFalse(SESSION_NAME.matches("-lead"), "must start with a letter or digit")
        assertFalse(SESSION_NAME.matches(""))
        assertFalse(SESSION_NAME.matches("a".repeat(51)))
    }

    @Test
    fun `the name help does not advertise the one character tmux eats`() {
        // ⚠ IT IS THE SHARED SENTENCE NOW (`SessionNameRules.HINT`, the phone's
        // too), which names the characters as WORDS rather than as symbols — so
        // the dot is looked for both ways and the dash is looked for the way the
        // sentence actually says it. The rule itself has not moved: a dotted
        // name is rewritten by tmux and must never be offered.
        assertFalse(" . " in SESSION_NAME_HELP, "help still offers a dot: $SESSION_NAME_HELP")
        assertFalse("dot" in SESSION_NAME_HELP.lowercase(), "help still offers a dot: $SESSION_NAME_HELP")
        assertTrue(
            "dash" in SESSION_NAME_HELP.lowercase(),
            "a dash is legal and the help should say so: $SESSION_NAME_HELP",
        )
    }

    // ------------------------------------------------------------- fixtures

    private fun noChatVerbs() = ChatVerbs(
        open = {}, rename = {}, stop = {}, copyId = {}, delete = {},
    )

    private fun ChatVerbs.copyDelete(delete: (List<String>) -> Unit) =
        ChatVerbs(open, rename, stop, copyId, delete)

    private fun noSessionVerbs(archive: ((List<String>) -> Unit)? = null) = SessionVerbs(
        open = {}, rename = {}, interrupt = {}, copyName = {}, compact = {},
        softEnd = {}, archive = archive, kill = {},
    )
}
