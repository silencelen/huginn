package com.silencelen.huginn.desktop.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.desktop.diag.AppLog
import com.silencelen.huginn.ui.LinkPeek
import java.awt.Desktop
import java.net.URI

/**
 * Where a link in Claude's output is allowed to go, and how it gets there.
 *
 * Lifted out of the settings page, which owned [openInBrowser] because it was the
 * only screen with a link in it. The transcript now has links in every answer, so
 * the opener — and the guard around it — belong in one place that both can name.
 */

/**
 * Opens [url] in the user's browser. False when this JVM has no desktop
 * integration — headless, a bare WM, or a sandbox — which is not an error so much
 * as a reason to show the link instead of pretending it opened.
 *
 * ⚠ http(s) ONLY, HERE AND NOT JUST IN THE HANDLER BELOW. Every caller hands it
 * an address someone else wrote: the daemon's sign-in URL, an app's address from
 * the registry, a link in an answer. `Desktop.browse` gives a URI to whatever the
 * OS has registered for its scheme, so a `huginn://` link would reach this app's
 * own activation handler, a `file:` one the disk, and any other scheme whatever
 * program claims it. Refused, and false, so the caller shows the address instead.
 */
fun openInBrowser(url: String): Boolean =
    browseWebLink(url, ::desktopBrowse) { AppLog.warn("link", "refused to open a non-http(s) address") }

/** [openInBrowser] with the browser and the refusal handed in, so the gate can be tested. */
internal fun browseWebLink(url: String, browse: (URI) -> Boolean, refused: () -> Unit): Boolean = runCatching {
    if (!isWebLink(url)) {
        refused()
        return false
    }
    browse(URI(url))
}.getOrDefault(false)

/** http or https, by the scheme [URI] reads — the one [Desktop.browse] is handed. */
internal fun isWebLink(url: String): Boolean {
    val scheme = runCatching { URI(url) }.getOrNull()?.scheme?.lowercase()
    return scheme == "http" || scheme == "https"
}

private fun desktopBrowse(uri: URI): Boolean {
    if (!Desktop.isDesktopSupported()) return false
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.BROWSE)) return false
    desktop.browse(uri)
    return true
}

/**
 * The `UriHandler` both the transcript and the settings pages click through.
 *
 * ⚠ http(s) ONLY, and this is the second of the two gates rather than the first.
 * `Markdown.isLinkable` already refuses to MAKE a link out of anything else, so
 * nothing should ever reach here with a `huginn://` — which is exactly why it is
 * checked again: the desktop's own scheme handler is fingerprint-gated because it
 * is reachable from outside, and a link in a model's output must not be a second
 * door to it. `file:` reads the disk and `javascript:` speaks for itself.
 *
 * A URL that cannot be opened is copied instead, because a link the reader can
 * still paste is a better failure than a click that silently did nothing.
 */
@Composable
fun rememberLinkUriHandler(onCopy: (String) -> Unit): UriHandler = remember(onCopy) {
    object : UriHandler {
        override fun openUri(uri: String) {
            // Checked before [openInBrowser] (which checks again) so that a refused
            // scheme is NOT copied: only a web link that would not open is.
            if (!isWebLink(uri)) {
                AppLog.warn("link", "refused a non-http scheme")
                return
            }
            if (!openInBrowser(uri)) onCopy(uri)
        }
    }
}

/**
 * The desktop's answer to "where does that link go": the URL under the pointer,
 * in a tooltip.
 *
 * ⚠ THE `TooltipArea` IS UNCONDITIONAL, and that is load-bearing. Swapping
 * between a tooltip wrapper and a bare `Box` as the hovered URL comes and goes
 * changes the node type around the text, which throws away the remembered
 * `TextLayoutResult` the hover test depends on — and the peek then flickers
 * between a URL and nothing as fast as it can recompose. One node, always; the
 * tooltip content is what varies.
 */
@OptIn(ExperimentalFoundationApi::class)
val DesktopLinkPeek = LinkPeek { url, content ->
    TooltipArea(
        // DisableSelection for the same reason [Tip] has it, and this one is
        // drawn over text that is being READ WITH A POINTER — hover a link,
        // start dragging out a quote, and the URL card's `Text` is a selectable
        // in another layout root. See OverlaysDisableSelectionTest.
        tooltip = { DisableSelection { if (!url.isNullOrBlank()) UrlCard(url) } },
        // Shorter than the 400ms of an explanatory Tip: this is not an
        // explanation, it is the thing the click is about to do.
        delayMillis = 220,
        // ⚠ ABOVE THE POINTER, because the row's timestamp tooltip is below it.
        // Both were `CursorPoint(DpOffset(12, 16))`, decided independently in two
        // files, and both are up at once whenever a link sits in a message that
        // has a stamp: the tip's 400ms lands on top of this card and the reader
        // loses the one fact the click is about to act on. See [CursorOverlays].
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = CursorOverlays.LINK_PEEK),
        content = content,
    )
}

@Composable
private fun UrlCard(url: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(6.dp),
        tonalElevation = 8.dp,
        modifier = Modifier.widthIn(max = 460.dp),
    ) {
        Text(
            url,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}
