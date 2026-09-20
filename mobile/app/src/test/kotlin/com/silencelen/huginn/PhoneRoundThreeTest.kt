package com.silencelen.huginn

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE ROUND-THREE PHONE LOWS THAT LIVE IN THE SHELL, gated against the source.
 *
 * Every finding here is a pixel or a lifecycle: a label five pixels low, a field
 * that did not take focus, a session the app forgot over a force-stop. There is
 * no compose-ui-test in this module and no instrumentation in this build, so
 * what can be held is the DECISION — the same arrangement `ListFabClearanceTest`
 * and `SuggestionChipsTest` already use next door, and for the same reason: the
 * mistakes were never in the pixels, they were in what the code chose to draw.
 *
 * NOTE this module is on org.junit, whose argument order is (message, expected,
 * actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class PhoneRoundThreeTest {

    private fun root(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun read(rel: String): String {
        val f = File(root(), rel)
        assertTrue("$rel not found at ${f.absolutePath}", f.isFile)
        val text = f.readText()
        // A grep that matches nothing exits 0. Assert the floor before the finding.
        assertTrue("$rel read as ${text.length} chars — wrong file", text.length > 2_000)
        return text
    }

    private fun sessionsScreen() = read("app/src/main/kotlin/com/silencelen/huginn/ui/SessionsScreen.kt")
    private fun mainActivity() = read("app/src/main/kotlin/com/silencelen/huginn/MainActivity.kt")

    // ------------------------------------------------------ P-24 the hint

    /**
     * ⚠ "Letters, digits and underscore" FORBADE THE DASH THE HOST ALLOWS, and
     * every session on this fleet is named with one. The rule itself is
     * `SessionNameRules` in `:core`, mirrored from the daemon's `nameProblem`
     * and tested there; what this holds is that the dialog ASKS it rather than
     * reciting a sentence of its own that can drift.
     */
    @Test
    fun `the new-session dialog quotes the daemon's rule instead of its own`() {
        val text = sessionsScreen()
        assertFalse(
            "the dialog is reciting its own narrower grammar again",
            text.contains("Letters, digits and underscore"),
        )
        assertTrue("the hint is not the shared one", text.contains("SessionNameRules.HINT"))
        assertTrue(
            "a bad name is still only refused by the daemon, after the dialog has closed",
            text.contains("SessionNameRules.nameProblem("),
        )
    }

    // ----------------------------------------------------- P-25 the focus

    @Test
    fun `the new-session dialog puts the cursor in the name field`() {
        val text = sessionsScreen()
        assertTrue("no FocusRequester on a dialog whose only content is one field", "FocusRequester()" in text)
        assertTrue("the requester is never attached", "focusRequester(nameFocus)" in text)
        assertTrue("nothing ever asks for focus", "nameFocus.requestFocus()" in text)
    }

    // ------------------------------------------------- P-26 the Status tab

    /**
     * ⚠⚠ TWO THINGS MADE THE STATUS TAB THE ODD ONE OUT. The headroom meter was
     * STACKED under the icon, so the item grew by the bar plus its gap and the
     * label sat 5 px below the other three; and the glyph was the solid variant
     * among three that resolve to outlines, which reads as permanently selected.
     */
    @Test
    fun `the Status tab is the same height and the same weight as its neighbours`() {
        val icon = mainActivity().substringAfter("private fun StatusIcon(").substringBefore("\n/**")
        assertTrue("StatusIcon read as ${icon.length} chars — wrong slice", icon.length in 1..2_000)
        assertTrue("the meter is stacked again, which costs the item height", "Box(" in icon)
        assertFalse("a Column here grows the icon slot", "Column(" in icon)
        assertTrue("the meter has to be drawn inside the icon's own box", "BottomCenter" in icon)
        assertTrue("the glyph is filled among outlines again", "Icons.Outlined.MonitorHeart" in icon)
        assertFalse("the filled glyph is back", "Icons.Filled.MonitorHeart" in mainActivity())
    }

    // ----------------------------------------------- P-35 the cold start

    /**
     * ⚠⚠ A COLD START PUT THE READER BACK ON THE LIST. `dest` is
     * `rememberSaveable`, which survives a fold and a rotate and dies with the
     * process — so a force-stop, a low-memory kill or a reboot lost the session
     * that was open. The desktop has restored its landing since it shipped.
     *
     * The three guards are the whole of this: once per process, only from an
     * untouched start, and only if the session is still there.
     */
    @Test
    fun `the open session is remembered and restored, under its three guards`() {
        val text = mainActivity()
        assertTrue("nothing records where the reader is", "vm.rememberOpenSession(" in text)
        val restore = text.substringAfter("var restored by rememberSaveable").substringBefore("LaunchedEffect(target)")
        assertTrue("the restore block read as ${restore.length} chars — wrong slice", restore.length in 1..2_000)
        assertTrue("the restore can run twice in one process", "if (restored) return@LaunchedEffect" in restore)
        assertTrue("a notification tap or a share would be overridden", "target != null" in restore)
        assertTrue("a reader who has navigated away would be dragged back", "dest !is Dest.Sessions" in restore)
        assertTrue(
            "a session the daemon no longer has would be opened anyway",
            "sessionsForRestore.none { it.name == name }" in restore,
        )
    }

    @Test
    fun `leaving a session for the list is remembered as leaving it`() {
        // Otherwise the next cold start overrides a choice the reader made: the
        // list is a real answer, not the absence of one.
        val text = mainActivity()
        assertTrue(
            "the remembered session is not cleared when the reader goes back to the list",
            "vm.rememberOpenSession((dest as? Dest.SessionView)?.name)" in text,
        )
    }

    // ---------------------------------------------- P-37 the FAB clearance

    /**
     * The live list has cleared the FAB since B3 wrote the clearance down. Two
     * places had not caught up: the Chats FAB carried a hand-written `16.dp`
     * (a third copy of the number the clearance is DERIVED from — the shape the
     * original bug had), and the empty-sessions branch drew the archive rows,
     * the only thing on that screen, under "New session".
     */
    @Test
    fun `no FAB on this phone writes its own inset`() {
        for (name in listOf("ChatsScreen.kt", "SessionsScreen.kt", "RoundsScreen.kt", "AppsScreen.kt", "ProjectsScreen.kt")) {
            val text = read("app/src/main/kotlin/com/silencelen/huginn/ui/$name")
            val fab = text.substringAfter("ExtendedFloatingActionButton(").substringBefore("\n    }")
            assertTrue("$name: the FAB read as ${fab.length} chars — wrong slice", fab.length in 1..1_500)
            assertTrue("$name: the FAB is not placed with FAB_INSET", "padding(FAB_INSET)" in fab)
        }
    }

    @Test
    fun `an all-archived host still clears the button`() {
        val empty = sessionsScreen().substringAfter("if (sessions.isEmpty()) {").substringBefore("} else {")
        assertTrue("the empty branch read as ${empty.length} chars — wrong slice", empty.length in 1..2_000)
        assertTrue(
            "the archive rows sit under the New session button on a host with no live sessions",
            "bottom = LIST_FAB_CLEARANCE" in empty,
        )
    }

    // ---------------------------------------------- P-30 find-live feedback

    /**
     * ⚠ "Find live route" PRODUCED NO SPINNER, NO MESSAGE AND NO CHANGE. Its
     * only answer was a toast raised by the screen UNDERNEATH Settings, on a
     * control whose whole job is to tell you whether a network still works. The
     * note lands on the route list itself, which is what the desktop already did.
     */
    @Test
    fun `the route sweep reports onto the list it was pressed from`() {
        val vm = read("app/src/main/kotlin/com/silencelen/huginn/ui/HuginnViewModel.kt")
        val resolve = vm.substringAfter("fun resolveRoute(").substringBefore("fun useRouteCandidate(")
        assertTrue("resolveRoute read as ${resolve.length} chars — wrong slice", resolve.length in 1..3_000)
        assertTrue("the sweep still only raises a toast", "_routeNote.value = words" in resolve)
        assertEquals(
            "the outcome is worded once, for both places it is said",
            1,
            Regex("""val words = when""").findAll(resolve).count(),
        )
    }
}
