package com.silencelen.huginn.desktop.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CLICKING THE LIVE VIEW HAS TO GIVE IT THE KEYBOARD BACK.
 *
 * ⚠⚠ IT DID NOT, AND THE LOSS WAS PERMANENT. `Modifier.focusable()` makes the
 * pane a focus TARGET; it does not make a press move the ring, and nothing else
 * in the chain did either. `LaunchedEffect(live)` asks for focus once, when Live
 * is switched on — so the first click on the desktop composer took the keyboard
 * away for good. Walked: typed into the pane (correct), clicked the composer
 * once to send a message, clicked back on the pane — on the prompt row, then on
 * the body — and every keystroke after that went to the composer, including the
 * twenty BackSpaces aimed at the pane's own draft. The HTTP transcript shows no
 * `/keys` POST at all for any of them. The only way back was toggling Live off
 * and on, which is a control whose label says nothing about focus.
 *
 * WHY A SOURCE GREP: there is no compose-ui-test in this module, and the failure
 * is a focus ring that did not move — nothing throws, nothing draws wrong, and a
 * screenshot of the broken version is identical to a screenshot of the fixed
 * one. The source text IS the bug. Same reasoning as `CapBeforeFillTest`.
 */
class LiveViewFocusTest {

    private val src = File("src/main/kotlin/com/silencelen/huginn/desktop/ui/SessionView.kt").readText()

    /** The pane's own modifier chain, from its focus requester to its key handler. */
    private fun paneChain(): String {
        val chain = src.substringAfter(".focusRequester(focus)").substringBefore(".onPreviewKeyEvent")
        assertTrue(chain.length in 1..3_000, "the live pane's modifier chain was not found")
        return chain
    }

    @Test
    fun `a press in the pane asks for focus`() {
        val chain = paneChain()
        assertTrue(
            "pointerInput" in chain,
            "focusable() alone does not move the ring on a click:\n$chain",
        )
        assertTrue(
            "focus.requestFocus()" in chain,
            "a click in the live view must take the keyboard back:\n$chain",
        )
    }

    /**
     * ⚠ AND IT MUST NOT EAT THE PRESS. The pane is inside the selection
     * container and the press still has to start a drag-selection and still has
     * to reach whatever is under it — so the gesture is watched on the INITIAL
     * pass and nothing is consumed.
     */
    @Test
    fun `the press is observed, not consumed`() {
        val chain = paneChain()
        assertTrue("requireUnconsumed = false" in chain, chain)
        assertTrue("PointerEventPass.Initial" in chain, chain)
    }

    /**
     * With Live off this box has no use for the keyboard, and stealing it from
     * the composer would be the same bug pointing the other way.
     */
    @Test
    fun `it only takes focus while live is on`() {
        assertTrue("if (live) focus.requestFocus()" in paneChain(), paneChain())
    }
}
