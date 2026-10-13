package com.silencelen.huginn.ui.theme

import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableChipColors
import androidx.compose.runtime.Composable

/**
 * Every FilterChip in the product, selected on `primaryContainer` (the tint a
 * selected row and the active tab take; round 7, Q4). Material's default is
 * `secondaryContainer`, which this theme never defines (PaletteTest guards
 * that), so chips used to select in the library's own violet.
 */
@Composable
fun huginnFilterChipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
)
