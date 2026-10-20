package com.silencelen.huginn.notify

/**
 * What a finished chat's notification thread may have appended to it.
 *
 * ⚠⚠ ONE FINISH ARRIVES TWICE. A chat's answer reaches the phone by push AND by
 * the next observation of the host: the push's own reconcile, the watch stream,
 * the alarm. Every other notice is drawn in one slot, so the second arrival only
 * replaces the first, which is what [PushAnnounce] counts on when it lets a
 * duplicate through. A chat is different: it is a MessagingStyle thread that
 * APPENDS, so the same answer showed twice under one exchange and the repost
 * could buzz again.
 *
 * Nothing upstream can tell the second arrival from a new answer: the push does
 * not say which run it is about, and the stream usually sees the finish before
 * the push lands. The thread can. An answer identical to the line the thread
 * already ends on, when that line is huginn's, is the same finish come a second
 * way. Only the LAST line, and only huginn's: the same words after your own
 * reply are a new answer to a new message, and are appended like any other.
 *
 * Pure, so it is asserted without a Context or a notification manager.
 */
object ChatThread {

    /** The key huginn's lines carry in the thread; see [SessionWatchWorker.huginn]. */
    const val HUGINN = "huginn"

    /**
     * Whether appending [text] from huginn would only repeat the thread's last line.
     *
     * @param lastSender the last line's sender key (its name when it has no key),
     *   or null when the thread is new
     * @param lastText the last line's text
     */
    fun repeatsLast(lastSender: String?, lastText: CharSequence?, text: String): Boolean =
        lastSender == HUGINN && lastText?.toString() == text
}
