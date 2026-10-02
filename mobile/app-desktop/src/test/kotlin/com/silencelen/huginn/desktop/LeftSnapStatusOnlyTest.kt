package com.silencelen.huginn.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The left-snap belongs to the Status page and nothing else.
 *
 * THE OWNER, 2026-10-01: the 09-15 Status fix "extended to all pages and body
 * elements, no bueno. also we should extend the allowance of width before it
 * stops and stays left." It had travelled through the shared `ReadingPane` to
 * Devices, Rounds and Apps, and been copied onto Settings, the transcript and
 * three phone pages. This reads the source, because the drift was a one-word
 * copy each time and a screenshot test would not have said which word.
 */
class LeftSnapStatusOnlyTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("cannot find the gradle root")

    private fun src(rel: String) = File(root, rel).readText()

    @Test
    fun `the shared reading pane is centred unless a page says otherwise, and only Status does`() {
        val scrolling = src("app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/common/Scrolling.kt")
        assertTrue("snap: Alignment.Horizontal = Alignment.CenterHorizontally" in scrolling)
        val desk = File(root, "app-desktop/src/main/kotlin").walkTopDown().filter { it.extension == "kt" }
        val snappers = desk.filter { "snap = Alignment.Start" in it.readText() }.map { it.name }.toList()
        assertEquals(listOf("StatusView.kt"), snappers, "only Status hangs from the left")
        assertTrue("maxWidth = Frame.statusReading" in src("app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/StatusView.kt"))
    }

    @Test
    fun `Status gets more room before it stops than the pages that are read`() {
        val density = src("app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/common/Density.kt")
        val reading = Regex("""val reading = (\d+)\.dp""").find(density)!!.groupValues[1].toInt()
        val status = Regex("""val statusReading = (\d+)\.dp""").find(density)!!.groupValues[1].toInt()
        assertTrue(status > reading, "status $status vs reading $reading")
    }

    @Test
    fun `settings, the transcript and the phone's other pages are centred`() {
        val settings = src("ui/src/commonMain/kotlin/com/silencelen/huginn/ui/settings/SettingsScaffold.kt")
        assertTrue("horizontalAlignment = Alignment.Start" !in settings, "Settings is centred")
        val scrolling = src("app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/common/Scrolling.kt")
        assertTrue("contentAlignment = Alignment.TopCenter" in scrolling, "the transcript column is centred")
        val phone = src("app/src/main/kotlin/com/silencelen/huginn/MainActivity.kt")
        val topStart = Regex("""is Dest\.(\w+) -> Box\(Modifier\.fillMaxSize\(\), contentAlignment = androidx\.compose\.ui\.Alignment\.TopStart\)""")
            .findAll(phone).map { it.groupValues[1] }.toList()
        assertEquals(listOf("Status"), topStart, "only the phone's Status page hangs from the left")
    }
}
