package com.silencelen.huginn

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EVERY SURFACE PAYS THE SYSTEM BARS, AND IT PAYS THEM IN ONE VOCABULARY.
 *
 * The review found the same missing line on six screens at once — session
 * Overview, Status, the Apps page, the archived transcript, Host & sign-in and
 * Devices all ended with their last row under the Android navigation bar — plus
 * two landscape failures the same omission causes: the conversation running to
 * x=2483 of 2520, under the back/home/recents strip on the right edge, and the
 * navigation rail cut off at the bottom with "Devices" half-drawn and
 * unreachable.
 *
 * Six screens with six chances to forget is one missing vocabulary, not six
 * bugs, so the fix is one helper (`ui/Insets.kt`) and this is the gate that
 * keeps the call sites using it.
 *
 * WHY A SOURCE GREP: there is no compose-ui-test and no emulator on this host —
 * these are measured overlaps, and the same reason `ListFabClearanceTest` greps.
 * What can be asserted is that no surface is silently paying nothing.
 *
 * NOTE this module is on org.junit, whose three-argument order is (message,
 * expected, actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class SystemInsetsTest {

    private fun mobileRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun read(relative: String): String {
        val f = File(mobileRoot(), "app/src/main/kotlin/com/silencelen/huginn/$relative")
        assertTrue("$relative not found at ${f.absolutePath}", f.isFile)
        val text = f.readText()
        assertTrue("$relative read as ${text.length} chars — wrong file", text.length > 1_000)
        return text
    }

    /** The screen file, and the marker that proves this gate is reading the right one. */
    private val screens = mapOf(
        "ui/StatusScreen.kt" to "fun StatusScreen(",
        "ui/AppsScreen.kt" to "fun AppsScreen(",
        "ui/DevicesScreen.kt" to "fun DevicesScreen(",
        "ui/ProjectsScreen.kt" to "fun ProjectsScreen(",
    )

    @Test
    fun `every pushed screen that owns its scroll pays the system navigation`() {
        val offenders = mutableListOf<String>()
        for ((relative, marker) in screens) {
            val text = read(relative)
            assertTrue("$relative lost $marker — this gate is scanning the wrong file", text.contains(marker))
            if (!text.contains("systemNavPadding()")) offenders += "$relative: nothing pays the nav bar"
        }
        assertTrue(offenders.joinToString("; "), offenders.isEmpty())
    }

    @Test
    fun `Devices no longer ends on a hand-written bottom padding`() {
        // It carried `bottom = 24.dp`, which is not the navigation bar and was
        // never going to be: the last machine's row sat under it.
        val text = read("ui/DevicesScreen.kt")
        assertTrue(
            "a hand-written bottom inset is back on the devices list",
            !Regex("""PaddingValues\([^)]*bottom = 24\.dp""").containsMatchIn(text),
        )
    }

    @Test
    fun `the session faces without a composer pay it at the shell's call site`() {
        // Conversation and Screen both end in a composer that pays; Overview ends
        // in a list and the archived transcript in another, and both are drawn by
        // `:ui`, so the phone pays for them where it builds them.
        val text = read("MainActivity.kt")
        assertTrue(
            "SessionOverviewView is drawn with no system inset",
            Regex("""SessionOverviewView\(\s*(//[^\n]*\n\s*)*modifier = Modifier\.systemNavPadding\(\)""")
                .containsMatchIn(text),
        )
        assertTrue(
            "ArchivedTranscriptView is drawn with no system inset",
            Regex("""ArchivedTranscriptView\(\s*(//[^\n]*\n\s*)*modifier = Modifier\.systemNavPadding\(\)""")
                .containsMatchIn(text),
        )
    }

    @Test
    fun `the settings pages clear the keyboard as well as the bar`() {
        // The add-route form put Name last above the IME and Address and Add
        // behind it, and would not scroll — two taps aimed at Address landed back
        // in Name and concatenated the URL onto the name.
        val text = read("ui/settings/SettingsPhoneScreen.kt")
        assertTrue(
            "the settings scroll container does not clear the soft keyboard",
            text.contains("imeAndSystemNavPadding()"),
        )
    }

    @Test
    fun `the frame pays the side bar once and consumes what the bars already ate`() {
        val text = read("MainActivity.kt")
        assertTrue(
            "nothing pays the landscape edge inset, so panes draw under the system nav",
            text.contains("windowInsetsPadding(sideNavInsets())"),
        )
        assertTrue(
            "the body must consume the bottom inset a NavigationBar has already paid",
            text.contains("Modifier.consumeWindowInsets(bottomNavInsets())"),
        )
        assertTrue(
            "the body must consume the status bar the app bar has already paid",
            text.contains("consumeWindowInsets(WindowInsets.systemBars.only(WindowInsetsSides.Top))"),
        )
    }

    @Test
    fun `the navigation rail scrolls, so its last destination is reachable`() {
        val text = read("MainActivity.kt")
        assertTrue(
            "the rail carries six destinations and a cover screen held sideways is not that tall",
            Regex("""NavigationRail \{\s*Column\(\s*Modifier\s*\.fillMaxHeight\(\)\s*\.verticalScroll""")
                .containsMatchIn(text),
        )
        assertTrue(
            "a weighted spacer inside a scroll is what clipped the rail in the first place",
            !text.contains("Spacer(Modifier.weight(1f))"),
        )
    }

    @Test
    fun `the two-pane session list does not float an extended FAB over its column`() {
        val text = read("ui/SessionsScreen.kt")
        assertTrue("the two-pane flag is gone from the list", text.contains("twoPane: Boolean = false"))
        assertTrue(
            "the extended pill is still drawn in two-pane, 200dp wide over a 292dp column",
            text.contains("if (twoPane) {") && text.contains("FloatingActionButton("),
        )
        assertTrue(
            "the shell no longer tells the list which layout it is in",
            read("MainActivity.kt").contains("twoPane = twoPane,"),
        )
    }
}
