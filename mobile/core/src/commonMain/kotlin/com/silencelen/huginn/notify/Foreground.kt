package com.silencelen.huginn.notify

/**
 * What the reader is looking at right now.
 *
 * Exists so a notification can be withheld when it would describe the very screen
 * the reader has open — every messaging app does this, and for the same reason: a
 * buzz about the conversation you are watching teaches you that buzzes carry
 * nothing you don't already know, and then the ones that do are ignored too.
 *
 * Process-global mutable state, on purpose. The writers are Compose (which screen
 * is composed) and the activity lifecycle (whether any of it is actually visible);
 * the readers are the FCM service and the watch cycle, which run on their own
 * threads in this same process. When the process was just started to deliver a
 * push, nothing has written here yet and every field holds its default — which is
 * exactly the right answer, since a freshly started process has nothing on screen.
 *
 * `resumed` matters as much as the targets: a chat left open when the phone was
 * pocketed is still the composed destination, but nobody is looking at it, and a
 * finish that arrives then must notify.
 */
object Foreground {

    /** True between the activity's onResume and onPause. */
    @Volatile var resumed: Boolean = false

    /** The chat open in the UI, if the current destination is a chat. */
    @Volatile var chat: String? = null

    /** The session open in the UI, if the current destination is a session. */
    @Volatile var session: String? = null

    /** Is this chat on screen in front of the reader right now? */
    fun showsChat(id: String?): Boolean =
        resumed && id != null && chat == id

    /** Is this session on screen in front of the reader right now? */
    fun showsSession(name: String?): Boolean =
        resumed && name != null && session == name
}
