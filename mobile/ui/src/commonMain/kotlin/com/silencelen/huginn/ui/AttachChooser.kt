package com.silencelen.huginn.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * What the composer's attach control offers when it is pressed.
 *
 * The control used to be a launcher: one press, one picker. Pages made it a
 * question — a message can carry a file OR a page — and the answer to that
 * question is a menu on the button that already means "attach", not a second
 * standing affordance above every composer for a thing used occasionally.
 *
 * The ROWS are shared because the rule about them is shared: whether the page row
 * exists at all depends on a feature probe both clients run, and a rule written
 * twice is a rule that gets fixed once. The framing is NOT shared — the phone
 * hangs system launchers off its rows and the desktop hangs an AWT dialog off
 * its, and neither is expressible in the other's module.
 */
data class AttachRow(
    /** Stable identity, for tests and for a shell that dispatches on it. */
    val id: String,
    /** Sentence case, no emoji — this is a menu row, not a label for a feature. */
    val label: String,
    val icon: ImageVector? = null,
    val onPick: () -> Unit,
)

object AttachChooser {
    const val NOTES_PAGE: String = "notes-page"

    /**
     * The shell's own rows, plus the page row when the daemon has pages.
     *
     * A daemon older than scratchpads answers 404 to the list route and both
     * clients collapse that to an empty list, so [padsAvailable] is the probe's
     * verdict arriving in the only form the composer ever sees it in. Offering a
     * row that can only fail is worse than not offering it.
     */
    fun rows(
        own: List<AttachRow>,
        padsAvailable: Boolean,
        onNotesPage: () -> Unit,
    ): List<AttachRow> =
        if (!padsAvailable) own
        else own + AttachRow(NOTES_PAGE, "Notes page", Icons.Outlined.EditNote, onNotesPage)

    /**
     * The row to run WITHOUT opening a menu, when there is only one.
     *
     * A menu of one is worse than no menu: it is an extra press and an extra
     * decision to reach the only thing behind it. With pages unavailable the
     * desktop is back to a single row, and this is what keeps its clip button
     * behaving exactly as it did before the chooser existed.
     */
    fun direct(rows: List<AttachRow>): AttachRow? = rows.singleOrNull()
}

/**
 * The chooser's rows, drawn the same way in both shells.
 *
 * [onPicked] closes the shell's own menu and then runs the row — in that order,
 * because a row that opens a second menu (the page picker) on the same anchor
 * needs the first one gone before the second appears.
 */
@Composable
fun AttachChooserItems(rows: List<AttachRow>, onPicked: (AttachRow) -> Unit) {
    rows.forEach { row ->
        DropdownMenuItem(
            text = { Text(row.label, style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = row.icon?.let { icon -> { Icon(icon, contentDescription = null) } },
            onClick = { onPicked(row) },
        )
    }
}
