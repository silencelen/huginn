package com.silencelen.huginn.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.settings.SettingsCatalog
import com.silencelen.huginn.settings.SettingsCategory
import com.silencelen.huginn.settings.SettingsProbe
import com.silencelen.huginn.settings.SettingsSearch
import com.silencelen.huginn.settings.Surface as SettingsSurface

/**
 * The frame both Settings screens are drawn in: one search field, one category
 * list, one detail pane, and the two layouts those three make.
 *
 * WHY THIS IS SHARED AND THE PAGES ARE NOT. The complaint was navigation, not
 * controls — 82 controls in two flat scrolls with no hierarchy, one behind a
 * 41-parameter signature. Hierarchy is the same problem on both shells and the
 * shells disagreed about it; the controls themselves genuinely differ (a phone
 * has a doze exemption, a desktop has a tray). So the frame is here and the
 * pages stay in the shells, which is also what keeps this file from growing a
 * third 41-parameter signature.
 *
 * SEARCH IS ALWAYS VISIBLE, on both shells — the owner's answer to Q1. Nine
 * categories is exactly the count where search stops being decoration, and an
 * action icon costs a tap to discover the one thing that helps somebody who does
 * not know where a setting lives.
 *
 * ⚠ NO LEFT ACCENT BARS anywhere in here. The selected category and a search
 * arrival are both marked the way state is marked everywhere else in this
 * product: a dot and a tint.
 */

/**
 * What the frame shows, decided without Compose so it can be asserted.
 *
 * @param searching the field has something in it, so the list underneath is
 *   results rather than categories.
 * @param categories the drawers worth listing for this probe and shell.
 * @param hits the results, best first, capped. Empty while [searching] means
 *   "nothing matched" — a different screen from "here are the categories".
 */
object SettingsScaffoldRules {

    data class Shown(
        val searching: Boolean,
        val categories: List<SettingsCategory>,
        val hits: List<SettingsSearch.Hit>,
    ) {
        /** Searching, and nothing came back. The one state that needs a sentence. */
        val emptyResult: Boolean get() = searching && hits.isEmpty()
    }

    fun shown(probe: SettingsProbe, surface: SettingsSurface, query: String): Shown {
        val q = SettingsSearch.normalise(query)
        if (q.isEmpty()) {
            return Shown(
                searching = false,
                categories = SettingsCatalog.visibleCategories(probe, surface),
                hits = emptyList(),
            )
        }
        return Shown(
            searching = true,
            categories = SettingsCatalog.visibleCategories(probe, surface),
            hits = SettingsSearch.hits(q, probe, surface),
        )
    }

    /**
     * Opening a hit: which drawer, and which row to mark inside it.
     *
     * A pair rather than two calls because the two must not be able to drift —
     * marking a row in a category that does not hold it is the failure this
     * shape makes unrepresentable.
     */
    fun open(hit: SettingsSearch.Hit): Pair<String, String> = hit.category.id to hit.item.id

    /**
     * The first category worth landing on when a two-pane frame opens with
     * nothing selected, or when the selection no longer exists (a daemon that
     * stopped answering `/v1/headroom` while Usage was open).
     */
    fun landing(shown: Shown, selected: String?): String? {
        if (selected != null && shown.categories.any { it.id == selected }) return selected
        return shown.categories.firstOrNull()?.id
    }
}

/** How wide the category list is beside a detail pane. */
private val LIST_WIDTH = 280.dp

/** The reading cap, the same 840 the desktop's own ReadingPane uses. */
val SETTINGS_READING_WIDTH = 840.dp

/**
 * The whole frame.
 *
 * @param selectedCategory null means "the list is the screen" on a one-pane
 *   shell. On a two-pane one, null lands on the first visible category.
 * @param onSelectCategory the shell's own navigation — it owns the back stack,
 *   the saved destination and the persisted section, none of which belong here.
 * @param summaryOf the live one-line summary under each category title, supplied
 *   by the shell because that is where the live state is: "jacob@… · 2 logins",
 *   "Fable 37% · ladder set". Null draws no line rather than an empty one.
 * @param twoPane list and detail side by side. The desktop passes its own
 *   responsive answer; the phone passes false.
 * @param content the shell's page for one category, told which row to mark.
 */
@Composable
fun SettingsScaffold(
    probe: SettingsProbe,
    surface: SettingsSurface,
    selectedCategory: String?,
    onSelectCategory: (String?) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    summaryOf: (SettingsCategory) -> String?,
    twoPane: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (categoryId: String, highlightItemId: String?) -> Unit,
) {
    val shown = SettingsScaffoldRules.shown(probe, surface, query)

    // The arrival mark lives here rather than in the shell: it is a property of
    // how this category was reached, not of the category, and a shell that had
    // to carry it would have to remember to clear it.
    var highlight by remember { mutableStateOf<String?>(null) }
    var highlightFor by remember { mutableStateOf<String?>(null) }

    fun openHit(hit: SettingsSearch.Hit) {
        val (categoryId, itemId) = SettingsScaffoldRules.open(hit)
        highlight = itemId
        highlightFor = categoryId
        onSelectCategory(categoryId)
    }

    fun openCategory(categoryId: String) {
        highlight = null
        highlightFor = null
        onSelectCategory(categoryId)
    }

    val landing = if (twoPane) SettingsScaffoldRules.landing(shown, selectedCategory) else selectedCategory
    val mark = if (landing != null && landing == highlightFor) highlight else null

    if (twoPane) {
        Row(modifier.fillMaxSize()) {
            Column(Modifier.width(LIST_WIDTH).fillMaxHeight()) {
                SettingsSearchField(query, onQuery)
                SettingsListPane(shown, selectedCategory, summaryOf, ::openCategory, ::openHit)
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (landing != null) content(landing, mark)
            }
        }
    } else if (landing == null) {
        Column(modifier.fillMaxSize()) {
            SettingsSearchField(query, onQuery)
            SettingsListPane(shown, null, summaryOf, ::openCategory, ::openHit)
        }
    } else {
        // Detail. The caller owns back — it is the shell's back stack, and a
        // frame that also owned one would fight it.
        Box(modifier.fillMaxSize()) { content(landing, mark) }
    }
}

/** Always visible, on both shells. */
@Composable
fun SettingsSearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.widthIn(max = SETTINGS_READING_WIDTH).fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            label = { Text("Search settings") },
            modifier = Modifier.weight(1f),
        )
        if (query.isNotEmpty()) {
            TextButton(onClick = { onQuery("") }) { Text("Clear") }
        }
    }
}

/**
 * Categories, or results, depending on whether anything was typed.
 *
 * PUBLIC because the desktop does not draw the two panes with [SettingsScaffold]
 * — its list pane is the animated, clipped pane behind the seam, with the notch,
 * Ctrl+B and the under-700dp fold hanging off it, so it hosts this half itself
 * and hosts [SettingsCategoryPage] in its detail column. Shared rather than
 * copied: "a hit that opens onto a hidden row" and "a result row that looks
 * different on the two clients" are the same class of drift, and the search
 * result list is exactly the part both shells were told to render identically.
 */
@Composable
fun SettingsListPane(
    shown: SettingsScaffoldRules.Shown,
    selected: String?,
    summaryOf: (SettingsCategory) -> String?,
    onOpenCategory: (String) -> Unit,
    onOpenHit: (SettingsSearch.Hit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Column(modifier.fillMaxSize().verticalScroll(scroll)) {
        if (shown.searching) {
            Text(
                if (shown.hits.isEmpty()) "No settings match that."
                else "${shown.hits.size} ${if (shown.hits.size == 1) "match" else "matches"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            for (hit in shown.hits) {
                SettingsHitRow(hit, onOpen = { onOpenHit(hit) })
            }
        } else {
            SettingsCategoryList(shown.categories, selected, summaryOf, onOpenCategory)
        }
    }
}

/**
 * The nine (or fewer) drawers, each with the shell's live one-liner under it.
 *
 * The selected one is marked with a dot and a tint — the same vernacular as a
 * live session in the rail and an active account in the list. NOT a bar.
 */
@Composable
fun SettingsCategoryList(
    categories: List<SettingsCategory>,
    selected: String?,
    summaryOf: (SettingsCategory) -> String?,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        for (c in categories) {
            val isSelected = c.id == selected
            val tint = MaterialTheme.colorScheme.primary
                .copy(alpha = if (isSelected) SettingsRowStyle.HIGHLIGHT_ALPHA else 0f)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tint)
                    .clickable { onOpen(c.id) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isSelected) SettingsStateDot(MaterialTheme.colorScheme.primary)
                        Text(
                            c.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                    summaryOf(c)?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    "›",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One result: the setting, what it does, and the drawer it will open. */
@Composable
private fun SettingsHitRow(hit: SettingsSearch.Hit, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(hit.item.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                hit.category.title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                " ›",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            hit.item.summary,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * One category's page: its title, its blurb, and the shell's rows.
 *
 * ⚠ CAP BEFORE FILL — `widthIn(max).fillMaxWidth()`. The other order compiles,
 * reads as a capped column and spans the whole window; it has happened twice in
 * this tree, once in a file shared with the phone, which is why
 * `CapBeforeFillTest` greps for it.
 *
 * @param scroll the caller's scroll state, so the desktop can hang its own
 *   scrollbar off the same one. Defaulted, so the phone does not have to care.
 */
@Composable
fun SettingsCategoryPage(
    title: String,
    blurb: String?,
    modifier: Modifier = Modifier,
    scroll: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(scroll),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = SETTINGS_READING_WIDTH).fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!blurb.isNullOrBlank()) {
                Text(
                    blurb,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            content()
        }
    }
}
