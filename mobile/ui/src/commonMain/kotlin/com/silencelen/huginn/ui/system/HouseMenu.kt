package com.silencelen.huginn.ui.system

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.ui.VerbTone
import com.silencelen.huginn.ui.theme.LocalHuginnColors
import com.silencelen.huginn.ui.theme.verbInk

/**
 * ONE MENU ROW, platform-neutral (pr/03-row-system.md, "The house Menu").
 *
 * The phone's ⋮ menus were each a stock `DropdownMenu` with its own idea of how
 * a destructive verb looks — one tinted the kill, one left Delete in plain ink,
 * one forgot the icon. A row is now data: the label, an optional icon, the
 * [VerbTone] that says how hard it lands, and whether it can be pressed. The
 * desktop's `HuginnMenuItem` converts to and from this, so the two models cannot
 * drift (see `Menus.kt`).
 *
 * [selected] marks the current choice in a picker (semibold, the old
 * bold-for-current); [dividerBefore] draws a hairline above the row, for a
 * picker's "none" row set apart from the list.
 */
class HouseMenuItem(
    val label: String,
    val icon: ImageVector? = null,
    val tone: VerbTone = VerbTone.PLAIN,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val dividerBefore: Boolean = false,
    val onClick: () -> Unit,
)

/** The disabled-content alpha Material uses, so a disabled verb reads as one. */
private const val DISABLED_ALPHA = 0.38f

/** The tighter picker row (owner, 10-01: the model/effort/mode lists were "loose"). */
private val DENSE_ROW_DP = 40.dp

/**
 * THE MENU. Tier 3 (`surfaceContainerHigh`, BRAND.md section 4: "dialogs,
 * menus") at the card radius; each label inked by [verbInk] for its tone, the
 * leading icon at [Scale.iconLarge] in the same ink (a plain verb's icon stays
 * the quieter `onSurfaceVariant`, Material's own default).
 *
 * A press closes the menu FIRST ([onDismiss]) and then runs the row, in that
 * order: a row that opens a second menu on the same anchor needs this one gone.
 *
 * [dense] is for pickers whose lists are long (40 dp rows, 12 dp inset).
 */
@Composable
fun HouseMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<HouseMenuItem>,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = RoundedCornerShape(Scale.radiusCard),
        containerColor = scheme.surfaceContainerHigh,
    ) {
        // A menu row's text was never usefully selectable (the drag that would
        // select it dismisses it), and a menu opened under a SelectionContainer
        // on the desktop is the press that crashed — see the desktop's
        // OverlaysDisableSelectionTest.
        DisableSelection { HouseMenuRows(items, scheme, dense, onDismiss) }
    }
}

@Composable
private fun HouseMenuRows(
    items: List<HouseMenuItem>,
    scheme: ColorScheme,
    dense: Boolean,
    onDismiss: () -> Unit,
) {
    items.forEach { item ->
        if (item.dividerBefore) {
            HorizontalDivider(color = LocalHuginnColors.current.outlineHairline)
        }
        val ink = verbInk(item.tone, scheme).let {
            if (item.enabled) it else it.copy(alpha = DISABLED_ALPHA)
        }
        val iconInk = (if (item.tone == VerbTone.PLAIN) scheme.onSurfaceVariant else verbInk(item.tone, scheme))
            .let { if (item.enabled) it else it.copy(alpha = DISABLED_ALPHA) }
        DropdownMenuItem(
            text = {
                Text(
                    item.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink,
                    fontWeight = if (item.selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = item.icon?.let { icon ->
                {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = iconInk,
                        modifier = Modifier.size(Scale.iconLarge),
                    )
                }
            },
            enabled = item.enabled,
            onClick = { onDismiss(); item.onClick() },
            modifier = if (dense) Modifier.height(DENSE_ROW_DP) else Modifier,
            contentPadding = if (dense) PaddingValues(horizontal = Scale.s12) else MenuDefaults.DropdownMenuItemContentPadding,
        )
    }
}

/** The ⋮ button and its menu: the door every overflow on the phone uses. */
@Composable
fun HouseMenuButton(
    contentDescription: String,
    items: List<HouseMenuItem>,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.MoreVert,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }) {
            Icon(icon, contentDescription = contentDescription)
        }
        HouseMenu(expanded = open, onDismiss = { open = false }, items = items)
    }
}
