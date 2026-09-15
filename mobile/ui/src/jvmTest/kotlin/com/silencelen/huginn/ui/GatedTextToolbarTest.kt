package com.silencelen.huginn.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The phone's long-press raises the app's selection bar; Android's own Copy /
 * Select all popup must not come up over it.
 *
 * Caught on the owner's Fold, 2026-09-15: one press, two toolbars, the platform's
 * floating in the middle of the conversation on top of the app's five verbs —
 * and one word highlighted where the design says a press selects the whole row.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class GatedTextToolbarTest {

    private class FakeToolbar : TextToolbar {
        var shown = 0
        var hidden = 0
        override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) {
            shown++
            status = TextToolbarStatus.Shown
        }

        override fun hide() {
            hidden++
            status = TextToolbarStatus.Hidden
        }
    }

    private val rect = Rect(0f, 0f, 10f, 10f)

    @Test
    fun `the platform popup is refused while the app's bar is up`() {
        val platform = FakeToolbar()
        val barUp = mutableStateOf(true)
        GatedTextToolbar(platform, barUp).showMenu(rect)
        assertEquals(0, platform.shown, "two toolbars for one press is the defect")
    }

    @Test
    fun `refusing also dismisses one that was already floating`() {
        // The container asks for the menu on every selection change, and one of
        // those is the very press that raised the app's bar — so the popup can be
        // on screen a frame before the gate closes. Declining is not enough.
        val platform = FakeToolbar()
        val barUp = mutableStateOf(true)
        GatedTextToolbar(platform, barUp).showMenu(rect)
        assertEquals(1, platform.hidden, "a popup raised a moment earlier would be orphaned")
    }

    @Test
    fun `with no app bar up the platform toolbar is untouched`() {
        // Dragging the handles to pull a phrase out of a message is a real thing
        // to want, and the platform is good at it. Only the press that already
        // has an answer is taken away from it.
        val platform = FakeToolbar()
        val gate = GatedTextToolbar(platform, mutableStateOf(false))
        gate.showMenu(rect)
        assertEquals(1, platform.shown)
        assertEquals(0, platform.hidden)
    }

    @Test
    fun `the gate follows the bar rather than being fixed at construction`() {
        // It is remembered across recompositions while the flag changes under it.
        val platform = FakeToolbar()
        val barUp = mutableStateOf(false)
        val gate = GatedTextToolbar(platform, barUp)
        gate.showMenu(rect)
        barUp.value = true
        gate.showMenu(rect)
        barUp.value = false
        gate.showMenu(rect)
        assertEquals(2, platform.shown, "shown before and after, refused during")
    }

    @Test
    fun `hide and status always reach the real toolbar`() {
        // NOT a no-op toolbar: the selection machinery reads `status` to decide
        // whether a menu is already up, and hide() must be able to take one down.
        val platform = FakeToolbar()
        val gate = GatedTextToolbar(platform, mutableStateOf(true))
        assertEquals(TextToolbarStatus.Hidden, gate.status)
        platform.status = TextToolbarStatus.Shown
        assertEquals(TextToolbarStatus.Shown, gate.status, "status is the platform's, not a second opinion")
        gate.hide()
        assertTrue(platform.hidden >= 1)
    }

    @Test
    fun `the app's bar is what a long press hands the row's whole text to`() {
        // The other half of the same defect: the container selects one WORD, so
        // if the bar took its text from the selection it would quote a word. It
        // does not — the press hands it the row, and SelectionMode holds it.
        val row = "Two security side-notes from the walk: a plaintext BitLocker recovery key"
        val mode = com.silencelen.huginn.ui.SelectionMode.begin(row)
        assertTrue(mode.active)
        assertEquals(row, mode.text, "the ROW, not the word under the thumb")
    }
}
