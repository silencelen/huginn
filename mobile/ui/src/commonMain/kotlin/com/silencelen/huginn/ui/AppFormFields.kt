package com.silencelen.huginn.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.AppForm

/**
 * The fields both shells put inside their own dialog chrome, and what the
 * daemon said when it refused.
 *
 * ⚠⚠ ONE COMPOSABLE BECAUSE THE 422 BEHAVIOUR MUST NOT DRIFT (decision 54). An
 * add can be refused because the app is not reachable from this person's
 * devices YET — they are going to run the lines below the fields and press Add
 * again — so the form keeps every character and grows the refusal underneath.
 * The phone draws an `AlertDialog` and the desktop a `DialogField` column; if
 * each held its own copy of that rule one of them would eventually clear the
 * form, and the person would retype the address they had just been told was the
 * right one.
 *
 * The chrome stays the shell's: title, confirm and dismiss buttons are where
 * each platform expects them.
 */
@Composable
fun AppFormFields(
    form: AppForm,
    kinds: List<String>,
    onChange: (AppForm) -> Unit,
    modifier: Modifier = Modifier,
    /** Hands the refusal's fix lines to the shell's clipboard. Null hides Copy. */
    onCopyFix: ((String) -> Unit)? = null,
) {
    // ⚠ THE ADDRESS IS ANSWERED IN THE FIELD as well as on the host —
    // `AppRules.urlProblem` mirrors the daemon's rule — so a typed `htp://` is
    // answered under the field instead of by a round trip. The daemon re-checks
    // it regardless; this is the courtesy, not the gate.
    val urlProblem = AppRules.urlProblem(form.url).takeIf { form.url.isNotBlank() }
    // Any keystroke drops the refusal: an error about text that has since been
    // changed is an error about nothing.
    fun edit(next: AppForm) = onChange(next.cleared())
    // ⚠⚠ THE FORM OWNS ITS OWN SCROLL, and it is the only surface in this feature
    // that does. A refusal is unbounded — the daemon decides how many addresses
    // it probed and how many lines the fix is — and a dialog measures its content
    // against the window rather than growing past it, so the panel's ONE control
    // (Copy fix) was clipped off the bottom edge the moment the fix ran past four
    // lines. A control that cannot be reached is a feature that is not there,
    // which is the same rule `ScrollOwnerTest` holds for the list. This lives here
    // rather than in `AppsView.kt` precisely so that gate keeps counting one.
    val scroll = rememberScrollState()
    Box(modifier.fillMaxWidth().heightIn(max = FORM_MAX_HEIGHT)) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            // Room for the track on the right, so a long line never runs under it.
            .padding(end = SCROLL_GUTTER),
    ) {
        OutlinedTextField(
            value = form.name,
            onValueChange = { edit(form.copy(name = it)) },
            singleLine = true,
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = form.url,
            onValueChange = { edit(form.copy(url = it)) },
            singleLine = true,
            isError = urlProblem != null,
            label = { Text("Address") },
            supportingText = urlProblem?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        AppKindPicker(form.kind, kinds) { edit(form.copy(kind = it)) }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = form.unit,
            onValueChange = { edit(form.copy(unit = it)) },
            singleLine = true,
            label = { Text("Unit (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = form.notes,
            onValueChange = { edit(form.copy(notes = it)) },
            label = { Text("Note") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        form.refusal?.takeIf { it.isNotBlank() }?.let { refusal ->
            Spacer(Modifier.height(8.dp))
            AppRefusalPanel(form, refusal, onCopyFix)
        }
    }
    // ⚠⚠ P-23 / D-19. NOTHING SAID THE BLOCK SCROLLED. The refusal is excellent
    // content — the addresses that did not answer, then a `# on huginn` comment,
    // then the firewall lines — and on first render it was cut mid-line at the
    // dialog's bottom edge with the comment half a row tall and every actual fix
    // line hidden. The surface DID scroll; there was no reason for a reader to
    // suspect it, so what they saw was a dialog that had run out of room.
    //
    // A drawn track rather than a platform scrollbar: `:ui` is common code and
    // Compose has no multiplatform scrollbar. It is present only when there is
    // something below, which makes its appearance the affordance.
    FormScrollTrack(scroll, Modifier.align(Alignment.TopEnd))
    }
}

/**
 * The right-edge track: where you are in the form and that there is more of it.
 *
 * Drawn only when the content overflows, so the ordinary five-field form has
 * nothing extra on it at all.
 */
@Composable
private fun FormScrollTrack(scroll: ScrollState, modifier: Modifier = Modifier) {
    val max = scroll.maxValue
    if (max <= 0 || max == Int.MAX_VALUE) return
    val colour = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.fillMaxHeight().width(SCROLL_GUTTER)) {
        val trackW = 3f.coerceAtMost(size.width)
        val x = size.width - trackW
        val visible = size.height
        // The thumb is the visible fraction of the whole, floored so it stays
        // grabbable-looking on a very long refusal.
        val whole = visible + max
        val thumbH = (visible * visible / whole).coerceAtLeast(24f).coerceAtMost(visible)
        val y = (visible - thumbH) * (scroll.value.toFloat() / max)
        drawRoundRect(
            color = colour.copy(alpha = 0.18f),
            topLeft = Offset(x, 0f),
            size = Size(trackW, visible),
            cornerRadius = CornerRadius(trackW / 2, trackW / 2),
        )
        drawRoundRect(
            color = colour.copy(alpha = 0.55f),
            topLeft = Offset(x, y),
            size = Size(trackW, thumbH),
            cornerRadius = CornerRadius(trackW / 2, trackW / 2),
        )
    }
}

/**
 * The daemon's refusal, the addresses that did not answer, and the lines that
 * would fix it — drawn UNDER the fields, which still hold what was typed.
 */
@Composable
private fun AppRefusalPanel(form: AppForm, refusal: String, onCopy: ((String) -> Unit)?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                refusal,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            form.addresses.filterNot { it.ok }.takeIf { it.isNotEmpty() }?.let { bad ->
                Spacer(Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    bad.forEach {
                        Text(
                            AppRules.addressWords(it),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            form.note.trim().takeIf { it.isNotEmpty() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val lines = form.fix.filter { it.isNotBlank() }
            if (lines.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    lines.forEach {
                        Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    AppRules.FIX_NEVER_RUN,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
                if (onCopy != null) {
                    Row(horizontalArrangement = Arrangement.Start) {
                        RowVerb("Copy fix") {
                            onCopy(AppRules.fixTextOf(form.name, form.unit, form.addresses, form.fix))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The kind, picked from what THIS daemon said it will accept.
 *
 * ⚠ FROM THE LIST, NOT FROM `AppRules.KINDS`. The client's copy of the
 * vocabulary is for rendering a kind that arrived from somewhere; the editor has
 * to offer what the host will store, or a newer daemon's sixth kind is one
 * nobody can ever choose. See [AppRules.kindChoices].
 */
@Composable
fun AppKindPicker(selected: String?, kinds: List<String>, onPick: (String) -> Unit) {
    val choices = kinds.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() } ?: AppRules.KINDS
    val current = selected?.trim()?.lowercase()
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Kind",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        // Chunked rather than a `FlowRow`: that one is still an experimental
        // layout API, and five short chips do not need a wrapping engine. Three
        // to a row fits the narrowest dialog this is drawn in.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            choices.chunked(3).forEach { rowOfKinds ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowOfKinds.forEach { kind ->
                        FilterChip(
                            selected = kind == current,
                            onClick = { onPick(kind) },
                            label = { Text(kind, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * How tall the form may get before it scrolls instead of growing.
 *
 * Tall enough that the five fields and a short refusal never scroll at all, low
 * enough that a long one cannot push the dialog's own buttons off a laptop
 * screen. Not a window fraction: the dialog is drawn by two shells with two
 * different window rules, and a number both can honour beats a calculation one
 * of them gets wrong.
 */
private val FORM_MAX_HEIGHT = 560.dp

/** The strip on the right the form leaves for [FormScrollTrack]. */
private val SCROLL_GUTTER = 8.dp
