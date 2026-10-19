package com.silencelen.huginn.ui

/**
 * What this app hands to a browser: http and https, and nothing else.
 *
 * The rule [Markdown.isLinkable] already applies to links in model output, made
 * the rule for EVERY address this app opens — the daemon's sign-in URL and the
 * links in Settings included. Those used to go straight to `ACTION_VIEW` on any
 * scheme, and an `ACTION_VIEW` is resolved by scheme: `intent:`, `market:`, a
 * `content:` URI or another app's own deep link would open whatever claims it,
 * with nobody having chosen to. The daemon writes those addresses, and a
 * daemon is not something the phone gets to trust that far.
 */
object WebLinks {

    /** [url] as it may be handed to a browser (trimmed), or null when it must not be. */
    fun browsable(url: String?): String? {
        val t = url?.trim() ?: return null
        return t.takeIf { Markdown.isLinkable(it) }
    }
}
