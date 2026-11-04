package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.desktop.ui.common.Tip
import com.silencelen.huginn.ui.VerbTone
import com.silencelen.huginn.ui.system.Scale
import com.silencelen.huginn.ui.theme.verbInk

/**
 * A directory as one line can show it: whole when it fits, otherwise cut in the
 * MIDDLE, keeping its last segments.
 *
 * ⚠ THE BUG (desktop 2.10.0, 2026-11-03): the New session dialog's Recent list
 * drew `/root/projects/apv2/assets/src/voice/…` for two different directories.
 * The menu cut every path at its end, and two recents under one project differ
 * exactly there — the leaf is what tells them apart, and the prefix every row
 * shares is what can go. So the first segment stays (which tree it is in), then
 * "…", then as many of the LAST segments as there is room for.
 */
internal object DirLabel {

    const val ELLIPSIS: String = "…"

    /**
     * Every way to write [path] on one line, longest first: the path itself; its
     * first segment, "…" and fewer and fewer of its last segments; "…" and the
     * last segment alone; then the end of that segment, for a name too long to
     * show whole. Lazy: the first one that fits is usually the first one.
     */
    fun forms(path: String): Sequence<String> = sequence {
        val p = path.trim()
        yield(p)
        val segs = p.split('/').filter { it.isNotEmpty() }
        // Only a path with a middle has a middle to cut.
        if (segs.size >= 3) {
            val head = (if (p.startsWith("/")) "/" else "") + segs.first()
            for (keep in segs.size - 2 downTo 1) {
                yield("$head/$ELLIPSIS/" + segs.takeLast(keep).joinToString("/"))
            }
        }
        val leaf = segs.lastOrNull() ?: return@sequence
        if (segs.size >= 2) yield("$ELLIPSIS/$leaf")
        for (n in leaf.length - 1 downTo 1) yield(ELLIPSIS + leaf.takeLast(n))
    }

    /** The longest form that [fits]; the shortest there is when none does. */
    fun fit(path: String, fits: (String) -> Boolean): String {
        var last = path.trim()
        for (form in forms(path)) {
            if (fits(form)) return form
            last = form
        }
        return last
    }

    /** [fit] with a character budget, for when nothing can be measured. */
    fun fit(path: String, maxChars: Int): String = fit(path) { it.length <= maxChars }
}

/** A menu row's inset each side, and so what is left of the menu's width for the words. */
private val ROW_INSET = 12.dp

/** Never narrower than Material's own widest menu row, whatever the field measures. */
private val MENU_MIN_WIDTH = 280.dp

/**
 * The New session dialog's Recent list: one row per directory, as wide as the
 * field it drops from, each label MEASURED into its row by [DirLabel] — "fits"
 * is a measurement here, not a character count, because Plex Sans is not
 * monospaced and a budget that suits `/root/x` cuts the end off `/home/mmm` —
 * and the whole path on hover whenever the row had to shorten it.
 *
 * ⚠ NOT THE SHARED `HouseMenu`, deliberately: its rows are data with a `String`
 * label and nowhere to hang a hover tip, and the tip is a pointer's affordance
 * the phone does not have. The surface, radius, ink and the `DisableSelection`
 * around the rows are the house menu's own, so this reads as the same menu.
 */
@Composable
internal fun RecentDirsMenu(
    expanded: Boolean,
    dirs: List<String>,
    /** The field the menu drops from, in px; 0 before it has been measured. */
    anchorWidthPx: Int,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodyMedium
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val width = maxOf(with(density) { anchorWidthPx.toDp() }, MENU_MIN_WIDTH)
    val room = with(density) { (width - ROW_INSET * 2).roundToPx() }
    val labels = remember(dirs, room, style, measurer) {
        dirs.associateWith { d ->
            DirLabel.fit(d) { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width <= room }
        }
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width),
        shape = RoundedCornerShape(Scale.radiusCard),
        containerColor = scheme.surfaceContainerHigh,
    ) {
        // The house menu's reason: a row's text was never usefully selectable, and
        // a menu opened under a SelectionContainer is the press that crashed.
        DisableSelection {
            dirs.forEach { d ->
                val label = labels[d] ?: d
                // No tip for a row that already says everything.
                Tip(if (label != d) d else "") {
                    DropdownMenuItem(
                        text = {
                            Text(
                                label,
                                style = style,
                                color = verbInk(VerbTone.PLAIN, scheme),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = { onDismiss(); onPick(d) },
                        contentPadding = PaddingValues(horizontal = ROW_INSET),
                    )
                }
            }
        }
    }
}
