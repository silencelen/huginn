package com.silencelen.huginn.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * HEIGHT ONLY — asserted against the source tree, because nothing else here can.
 *
 * ⚠⚠ THE TRAP. The desktop's list pane draws its rows inside an inner
 * `Box(wrapContentWidth(Start, unbounded = true).requiredWidth(listWidth))`. A
 * child that animates its own WIDTH inside that box re-measures the pane on every
 * frame of the animation: open a project and the splitter visibly breathes in and
 * out. It is the same family as the fill-before-cap trap next door — a layout bug
 * that throws nothing, logs nothing, and is only ever seen in a screenshot.
 *
 * The safe shape is `Modifier.fillMaxWidth().animateContentSize()`: the width
 * arrives fixed from the parent, so the only axis left to animate is the height.
 *
 * WHY A SOURCE GREP AND NOT A COMPOSE TEST, and the answer is [CapBeforeFillTest]'s:
 * there is no compose-ui-test in any of these modules, and the failure is a
 * measured width rather than a thrown anything. The source text IS the bug.
 */
class DisclosureHeightOnlyTest {

    /** The Wave 3 shared views. Named, not globbed: this gate guards these files. */
    private val guarded = listOf(
        "ProjectsListView.kt",
        "ProjectDashboardView.kt",
        "ManifestCard.kt",
        "CreateProjectSheet.kt",
        "AppsView.kt",
    )

    private fun uiSources(): List<File> {
        // Gradle runs a test with the MODULE directory as its working dir, so the
        // repository's `mobile/` is one level up. Resolved rather than assumed: a
        // wrong root would make this suite pass by scanning nothing, which is the
        // one way a gate like this fails silently.
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")
        val dir = File(root, "ui/src/commonMain/kotlin/com/silencelen/huginn/ui")
        return guarded.map { File(dir, it) }
    }

    @Test
    fun `the guarded files are all there to be scanned`() {
        // A glob that matches nothing exits 0, and this project has been bitten by
        // exactly that. The floor is asserted before the finding is.
        val missing = uiSources().filterNot { it.isFile }
        assertTrue(missing.isEmpty(), "not scanned: ${missing.map { it.path }}")
    }

    @Test
    fun `a disclosure exists at all, so this gate is guarding something`() {
        val text = uiSources().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertTrue(
            text.contains("animateContentSize("),
            "no animated disclosure found — either it was removed or this gate lost its subject",
        )
    }

    /**
     * Every `animateContentSize` must sit on a chain whose width is already
     * fixed by a `fillMaxWidth()` earlier in the SAME chain.
     *
     * Chains are read from the modifier expression rather than the whole file: a
     * `fillMaxWidth` three functions away proves nothing about this one.
     */
    @Test
    fun `every animated disclosure has its width already fixed`() {
        val offences = mutableListOf<String>()
        uiSources().filter { it.isFile }.forEach { file ->
            val text = file.readText()
            ANIMATE.findAll(text).forEach { m ->
                val chainStart = text.lastIndexOf("Modifier", m.range.first)
                val chain = if (chainStart < 0) "" else text.substring(chainStart, m.range.last + 1)
                if (!chain.contains("fillMaxWidth(")) {
                    val line = text.take(m.range.first).count { it == '\n' } + 1
                    offences += "${file.path}:$line  ${chain.replace(Regex("\\s+"), " ")}"
                }
            }
        }
        assertTrue(
            offences.isEmpty(),
            "animateContentSize on a chain whose width is not already fixed animates BOTH axes:\n" +
                offences.joinToString("\n"),
        )
    }

    /**
     * And nothing in these files may drive a width from an animation at all.
     *
     * The other half of the trap: `animateDpAsState` feeding a `.width(...)` moves
     * the pane just as surely as an unfixed `animateContentSize`, and reads far
     * more innocently.
     */
    @Test
    fun `no width is driven by an animated value`() {
        val offences = mutableListOf<String>()
        uiSources().filter { it.isFile }.forEach { file ->
            val text = file.readText()
            ANIMATED_WIDTH.findAll(text).forEach { m ->
                val line = text.take(m.range.first).count { it == '\n' } + 1
                offences += "${file.path}:$line  ${m.value.replace(Regex("\\s+"), " ")}"
            }
        }
        assertTrue(
            offences.isEmpty(),
            "a width driven by an animation re-measures the desktop's list pane every frame:\n" +
                offences.joinToString("\n"),
        )
    }

    private val ANIMATE = Regex("""\.animateContentSize\(""")

    /** `animate*AsState` anywhere, and any width taking an animated-looking value. */
    private val ANIMATED_WIDTH = Regex(
        """animate(Dp|Float|Int|Value)AsState|\.(requiredWidth|width|widthIn)\(\s*anim""",
    )
}
