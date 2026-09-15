package com.silencelen.huginn.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CAP BEFORE FILL — asserted against the source tree, because nothing else can.
 *
 * A `Modifier.fillMaxWidth()` followed by a `widthIn(max = 760.dp)` reads exactly
 * like a capped column and is not one. `fillMaxWidth` hands DOWN fixed constraints (min == max
 * == whatever arrived), and a `widthIn` inside fixed constraints can only coerce
 * INTO them — so the cap is silently swallowed and the "capped" content spans the
 * whole window. The audit measured the result: threshold sliders 1105px wide,
 * account rows 1338px, a Headroom intro setting ~135 characters on one line.
 *
 * The correct order was already written down in this tree, at the session
 * composer (`SessionView.kt`, "Cap before fill"), and the trap came back anyway —
 * twice, in two modules, one of them shared with the phone. A comment is not a
 * gate. This is the gate.
 *
 * WHY A SOURCE GREP AND NOT A COMPOSE TEST. There is no compose-ui-test in any of
 * these modules, and the failure is a measured width rather than a thrown
 * anything — a test that asserted "widthIn was called" would pass against exactly
 * the broken code. The source text IS the bug: the two modifiers in that order,
 * in that direction, are always wrong. So this reads the files the way a reviewer
 * would, and names the file and line the way a reviewer would.
 */
class CapBeforeFillTest {

    /**
     * A `fill…()` immediately followed by a cap ON THE SAME AXIS, allowing for a
     * line break and indentation between the two — which is how it will be written
     * the next time, since that is how the wrapped call sites already look.
     *
     * Same axis matters: `fillMaxWidth().heightIn(max = 160.dp)` is the composer's
     * own field and is perfectly correct, because `fillMaxWidth` fixes nothing
     * about height. Flagging it would make this gate noise, and a noisy gate gets
     * a `@Ignore` within the month.
     */
    private val wrongOrder = listOf(
        Regex("""\.(fillMaxWidth|fillMaxSize)\(\s*\)\s*\r?\n?\s*\.(widthIn|sizeIn)\("""),
        Regex("""\.(fillMaxHeight|fillMaxSize)\(\s*\)\s*\r?\n?\s*\.(heightIn|sizeIn)\("""),
    )

    /** The Kotlin the four modules are actually made of, build output excluded. */
    private fun sources(): List<File> {
        // Gradle runs a test with the MODULE directory as its working dir, so the
        // repository's `mobile/` is one level up. Resolved rather than assumed: a
        // wrong root would make this suite pass by scanning nothing, which is the
        // one way a gate like this fails silently.
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")
        return listOf("core", "ui", "app", "app-desktop")
            .map { File(root, "$it/src") }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }
    }

    @Test
    fun `the source tree is scanned at all`() {
        val files = sources()
        // A glob that matches nothing exits 0. This project has been bitten by
        // exactly that (a gate that ran 58 of 179 tests and read like coverage),
        // so the floor is asserted before the finding is.
        assertTrue(files.size > 100, "only ${files.size} Kotlin files found — the root is wrong")
    }

    @Test
    fun `no fill before a cap anywhere in the tree`() {
        val offences = sources().flatMap { file ->
            val text = file.readText()
            wrongOrder.flatMap { rx ->
                rx.findAll(text).map { m ->
                    val line = text.take(m.range.first).count { it == '\n' } + 1
                    "${file.path}:$line  ${m.value.replace(Regex("\\s+"), " ")}"
                }
            }
        }
        assertTrue(
            offences.isEmpty(),
            "fill-before-cap swallows the cap — put the widthIn FIRST:\n" +
                offences.joinToString("\n"),
        )
    }
}
