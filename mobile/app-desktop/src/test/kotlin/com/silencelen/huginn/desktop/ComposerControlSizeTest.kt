package com.silencelen.huginn.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ONE SIZE FOR EVERY CONTROL ON THE COMPACT COMPOSER LINE — asserted against the
 * source tree, because nothing else in this project can.
 *
 * THE OWNER REPORTED THE VERSION WITHOUT THIS GATE: *"the send button in desktop,
 * when shrunk to its icon due to scaling, is not aligned properly with the
 * attachment icon to its left, it should be centered vertically on the same
 * horizontal plane as the attachment button."*
 *
 * The mechanism is worth keeping, because it is invisible in the source. Attach
 * stayed a `TextButton` in BOTH composer shapes while Send and Interrupt became
 * 32dp icon buttons under the breakpoint. A clickable M3 `Surface` — which is what
 * a `TextButton` is — carries `minimumInteractiveComponentSize()`, so it reported a
 * 48dp box with its glyph centred at 24dp from the top of the control line, while
 * the 32dp icon buttons sat at the TOP of that line with their glyphs at 16dp.
 * Measured on a 560px window: 8px apart. Nothing about either call site looks
 * wrong; they simply were not the same control.
 *
 * So the rule is the one thing a reviewer CAN check: every control drawn on that
 * line is sized from [Composer.CONTROL_DP] and [Composer.CONTROL_GLYPH_DP], and
 * none of them carries a size of its own. Two controls built from one number
 * cannot disagree about their centre, whatever the row's alignment happens to be.
 *
 * WHY A SOURCE GREP AND NOT A COMPOSE TEST. There is no compose-ui-test dependency
 * in ANY of the four modules (checked, not assumed), and the failure is a measured
 * offset rather than a thrown anything — a test that asserted "size() was called"
 * would pass against exactly the broken code. This reads the files the way a
 * reviewer would, like the `CapBeforeFillTest` beside it.
 */
class ComposerControlSizeTest {

    private fun root(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun source(path: String): String {
        val f = File(root(), path)
        // A file this gate cannot find would make it pass by scanning nothing,
        // which is the one way a source-level gate fails silently.
        assertTrue(f.isFile, "expected to scan $path, and it is not there")
        return f.readText()
    }

    private val frame =
        "app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/common/ComposerFrame.kt"
    private val attach =
        "app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/attach/AttachmentUi.kt"

    /** The `fun AttachButton(...)` body, up to the next top-level closing brace. */
    private fun attachButtonBody(): String {
        val text = source(attach)
        val start = text.indexOf("fun AttachButton(")
        assertTrue(start > 0, "AttachButton has moved out of $attach")
        val end = text.indexOf("\n}", start)
        assertTrue(end > start, "could not find the end of AttachButton in $attach")
        return text.substring(start, end)
    }

    @Test
    fun `the compact action controls are sized from the shared constant`() {
        val text = source(frame)
        assertTrue(
            "Composer.CONTROL_DP" in text && "Composer.CONTROL_GLYPH_DP" in text,
            "$frame must size its compact controls from Composer.CONTROL_DP / " +
                "CONTROL_GLYPH_DP, not from numbers of its own",
        )
    }

    @Test
    fun `the compact attach control is sized from the same constant`() {
        val body = attachButtonBody()
        assertTrue(
            "Composer.CONTROL_DP" in body && "Composer.CONTROL_GLYPH_DP" in body,
            "AttachButton draws the compact clip, so it must be built from the SAME " +
                "Composer.CONTROL_DP / CONTROL_GLYPH_DP the actions beside it use — " +
                "a labelled button here is 8px off the send icon, which is the bug " +
                "this gate exists for",
        )
        assertTrue(
            "ComposerLayout.COMPACT" in body,
            "AttachButton must still branch on the composer's layout: one form for " +
                "both shapes is how the sizes drifted apart in the first place",
        )
    }

    /**
     * No dp LITERAL may size a composer control. `Modifier.size(32.dp)` beside a
     * `Modifier.size(Composer.CONTROL_DP.dp)` is the same 32 twice until someone
     * changes one of them — which is the shape of the original defect, not a
     * hypothetical.
     */
    @Test
    fun `no composer control carries a size of its own`() {
        val literal = Regex("""Modifier\.size\(\s*\d+(\.\d+)?\.dp\s*\)""")
        val offences = listOf(frame, attach).flatMap { path ->
            val text = if (path == attach) attachButtonBody() else source(path)
            literal.findAll(text).map { "$path  ${it.value}" }
        }
        assertTrue(
            offences.isEmpty(),
            "size a composer control from Composer.CONTROL_DP / CONTROL_GLYPH_DP:\n" +
                offences.joinToString("\n"),
        )
    }
}
