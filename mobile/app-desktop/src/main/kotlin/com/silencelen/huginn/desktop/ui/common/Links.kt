package com.silencelen.huginn.desktop.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.DpOffset
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
 */
fun openInBrowser(url: String): Boolean = runCatching {
    if (!Desktop.isDesktopSupported()) return false
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.BROWSE)) return false
    desktop.browse(URI(url))
    true
}.getOrDefault(false)

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
            val scheme = runCatching { URI(uri) }.getOrNull()?.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
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
        tooltip = { if (!url.isNullOrBlank()) UrlCard(url) },
        // Shorter than the 400ms of an explanatory Tip: this is not an
        // explanation, it is the thing the click is about to do.
        delayMillis = 220,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(12.dp, 16.dp)),
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
