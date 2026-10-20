package com.silencelen.huginn.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A press of Send, from the tap until its message is composed.
 *
 * ⚠⚠ THE BOX EMPTIES AT THE PRESS, AND A COMPOSER SENDS ONE PRESS AT A TIME.
 * A send waits for its composer's uploads to land before the message is composed
 * (up to [AttachmentSlots.SETTLE_TIMEOUT_MS]), and it used to wait with the text
 * still in the box and nothing marked. The tap looked like it had done nothing, a
 * second tap started a second send, and when the photo landed both went out: the
 * first with the photo, the second with the same words alone, so Claude was told
 * to do the thing twice. Words typed during the wait were then wiped, unsent, by
 * the clear that followed it.
 *
 * Now the box is emptied at the press, which is the desktop's rule (the send
 * carries the text, and a refused send puts it back), and a press waits its turn
 * behind an earlier press on the SAME composer instead of racing it for the
 * attachments. A second tap therefore sends only what was typed after the first,
 * and the photo rides the message it was attached for. Other composers are not
 * held up.
 *
 * A plain class for the reason [AttachmentSlots] is one: the view model is an
 * `AndroidViewModel`, and nothing that needs an `Application` can be tested here.
 */
class SendPresses(private val slots: AttachmentSlots) {

    private val turns = HashMap<String, Mutex>()

    private fun turnFor(owner: String): Mutex = synchronized(turns) { turns.getOrPut(owner) { Mutex() } }

    /**
     * Presses Send on [owner]'s composer. [clearDraft] runs now; [send] runs after
     * any earlier press on this composer has gone, once this composer's uploads
     * have settled, and is where the attachments are taken and the message built.
     */
    fun press(scope: CoroutineScope, owner: String, clearDraft: () -> Unit, send: () -> Unit): Job {
        clearDraft()
        val turn = turnFor(owner)
        return scope.launch {
            turn.withLock {
                slots.settle(owner)
                send()
            }
        }
    }
}
