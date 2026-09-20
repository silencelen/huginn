package com.silencelen.huginn.ui

import com.silencelen.huginn.data.App
import com.silencelen.huginn.data.AppAddress
import com.silencelen.huginn.data.AppReachability
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Apps card and row, and the three things they must never get wrong: what
 * they claim about a page nobody has checked, whether the device in your hand
 * can reach one, and that BOTH shells draw the same row.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class AppsViewTest {

    private fun app(
        id: String,
        up: Boolean?,
        deviceOk: Boolean? = true,
        icon: Boolean = false,
    ) = App(
        id = id,
        name = id,
        url = "http://huginn:8088/",
        up = up,
        icon = icon,
        reachable = AppReachability(ok = deviceOk, checkedAt = if (deviceOk == null) 0 else 1_789_459_940),
    )

    /**
     * ⚠ THE UNCHECKED ONES ARE COUNTED SEPARATELY, never folded into "down".
     * "3 of 4 up" with the fourth never probed is a number that sends somebody to
     * restart a healthy service — and after a daemon restart that is EVERY row,
     * because probe state lives in memory.
     */
    @Test
    fun `the card counts up, needs-retrofit and unchecked as three different things`() {
        val rows = listOf(
            app("armap", true, deviceOk = true),
            app("jtyper", true, deviceOk = false),
            app("board", false, deviceOk = null),
            app("btc15m", null, deviceOk = null),
        )
        assertEquals("2 of 4 up · 1 needs retrofit · 1 not checked", appsStatusWords(rows))
        assertEquals(
            "2 of 2 up",
            appsStatusWords(listOf(app("a", true), app("b", true))),
        )
        assertEquals(
            "1 of 2 up · 2 need retrofit",
            appsStatusWords(listOf(app("a", true, deviceOk = false), app("b", false, deviceOk = false))),
        )
        assertEquals("none listed", appsStatusWords(emptyList()))
    }

    @Test
    fun `an empty list explains what an app even is`() {
        assertTrue(APPS_EMPTY.contains("huginn makes and hosts"), APPS_EMPTY)
        assertTrue(
            APPS_EMPTY.contains("reach it"),
            "the reachability prerequisite belongs in the empty state too: $APPS_EMPTY",
        )
    }

    // ------------------------------------------------------ one row, two shells

    private fun root(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun read(rel: String): String {
        val f = File(root(), rel)
        assertTrue(f.isFile, "not scanned: ${f.absolutePath}")
        val text = f.readText()
        // A glob that matches nothing exits 0, and this project has been bitten by
        // exactly that. The floor is asserted before the finding is.
        assertTrue(text.length > 2_000, "$rel read as ${text.length} chars — wrong file")
        return text
    }

    /**
     * ⚠⚠ THE ROW IS SHARED, AND THAT IS THE WHOLE POINT OF `:ui`. The icon, the
     * reachability line, the inline fix and the press-to-open rule all live in
     * one composable; a shell that grew its own copy of any of them would drift
     * the moment one side was fixed. Asserted at the SOURCE, because there is no
     * compose-ui-test in these modules and "both shells call it" is not a runtime
     * fact either of them can be asked about.
     */
    @Test
    fun `both shells host the shared row rather than drawing their own`() {
        val phone = read("app/src/main/kotlin/com/silencelen/huginn/ui/AppsScreen.kt")
        val desktop = read("app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/AppsPane.kt")
        for ((rel, text) in listOf("AppsScreen.kt" to phone, "AppsPane.kt" to desktop)) {
            assertTrue(Regex("""AppsView\s*\(""").containsMatchIn(text), "$rel does not host AppsView")
            assertFalse(
                Regex("""@Composable[\s\S]{0,200}?fun\s+AppRow\s*\(""").containsMatchIn(text),
                "$rel has grown its own row",
            )
        }
    }

    /**
     * ⚠ AND THE APPROVAL CARD IS GONE, not hidden (decision 55). The registry-wide
     * card with the four firewall lines on it left the page when the fix moved
     * onto the row that needs it; a leftover composable is the thing somebody
     * wires back up.
     */
    @Test
    fun `no list-level approval card survives anywhere in the client`() {
        val guarded = listOf(
            "ui/src/commonMain/kotlin/com/silencelen/huginn/ui/AppsView.kt",
            "app/src/main/kotlin/com/silencelen/huginn/ui/AppsScreen.kt",
            "app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/AppsPane.kt",
        )
        for (rel in guarded) {
            val text = read(rel)
            assertFalse("ApprovalCard" in text, "$rel still draws the list-level approval card")
            assertFalse("approvalSteps" in text, "$rel still reads the retired approval shape")
        }
    }

    /**
     * The failing row's disclosure has to actually carry what the daemon sent —
     * which address failed, why, and the lines. A panel that drew the fix without
     * the addresses is a fix with no evidence behind it.
     */
    @Test
    fun `the shared view draws the addresses, the fix and its copy control`() {
        val text = read("ui/src/commonMain/kotlin/com/silencelen/huginn/ui/AppsView.kt")
        assertTrue("AppRules.addressWords" in text, "the failing addresses are not drawn")
        assertTrue("AppRules.fixLines" in text, "the fix lines are not drawn")
        assertTrue("AppRules.fixText" in text, "there is nothing for Copy to hand over")
        assertTrue("AppRules.FIX_NEVER_RUN" in text, "a panel with one control and no sentence")
    }

    /**
     * The tile is drawn from the row, not from a fetch that can only 404 — a
     * scan, because the alternative is a screenshot.
     */
    @Test
    fun `a row with no icon never asks for one`() {
        val text = read("ui/src/commonMain/kotlin/com/silencelen/huginn/ui/AppsView.kt")
        assertTrue("AppRules.hasIcon" in text, "the row fetches an icon it was told does not exist")
        assertTrue("AppRules.iconInitial" in text, "there is no tile to fall back to")
    }

    /**
     * A sanity check that the disclosure rule the view asks about is the shared
     * one — and that it asks the WIDER question. A row with a reachability note
     * and nothing wrong ("no address to probe yet") is not failing and still has
     * the only answer there is going to be.
     */
    @Test
    fun `the view asks the rules whether a row has anything to disclose`() {
        val text = read("ui/src/commonMain/kotlin/com/silencelen/huginn/ui/AppsView.kt")
        assertTrue("AppRules.expandable" in text, "the view has its own opinion about what opens")
        assertTrue("AppRules.expandVerb" in text, "and about what the toggle should say")
        assertTrue("AppRules.reachNote" in text, "the daemon's sentence is never drawn")
    }

    /**
     * ⚠⚠ AMBER IS FOR ATTENTION, AND "UP" IS NOT ATTENTION (P-32).
     *
     * Four apps at `HTTP 200 · reachable` all carried a rune-gold dot, because
     * `primary` is this app's one accent — the colour of a running session, a
     * live lane, a selected settings row. Four healthy rows in the colour of
     * "look at this" is a page that reads as four warnings, and the row that
     * genuinely wants somebody ("up, but not from where you are") had nothing
     * left to say it with.
     */
    @Test
    fun `a healthy app is drawn calmly and the accent is kept for the row that needs somebody`() {
        val dot = read("ui/src/commonMain/kotlin/com/silencelen/huginn/ui/AppsView.kt")
            .substringAfter("private fun ReachDot(")
            .substringBefore("internal fun RowVerb(")
        assertTrue(dot.length in 1..2_000, "ReachDot read as ${dot.length} chars — wrong slice")

        val up = dot.indexOf("Reach.UP ->")
        val retrofit = dot.indexOf("DeviceReach.RETROFIT ->")
        assertTrue(up > 0 && retrofit > 0, "the gate lost its subject: up=$up retrofit=$retrofit")
        assertTrue(
            dot.substring(up).startsWith("Reach.UP -> MaterialTheme.colorScheme.onSurfaceVariant"),
            "an app that simply answers is not asking for attention",
        )
        assertTrue(
            dot.substring(retrofit).startsWith("DeviceReach.RETROFIT -> MaterialTheme.colorScheme.primary"),
            "the accent belongs to the row a person has to do something about",
        )
        // ⚠ AND NOT `tertiary`, which the retrofit dot used to draw: the theme
        // defines neither it nor its container, so it was Material's baseline
        // pink in a warm rune-gold palette. PaletteTest's rule, one role short.
        assertFalse("colorScheme.tertiary" in dot, "a colour nobody in this product picked")
    }

    @Test
    fun `an address with no reason still reads as a failure`() {
        // Belt and braces with AppRulesTest: this is the string the disclosure
        // actually draws, and an empty right-hand side would read as "fine".
        assertEquals(
            "192.168.7.31:8091 — no answer",
            com.silencelen.huginn.ui.AppRules.addressWords(AppAddress("192.168.7.31:8091", ok = false)),
        )
    }
}
