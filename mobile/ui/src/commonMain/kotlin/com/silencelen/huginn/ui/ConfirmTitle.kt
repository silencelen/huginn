package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * ⚠⚠ WHAT IS ABOUT TO HAPPEN, TO WHAT — WITH THE *WHAT* AS THE BIG WORD (P-02).
 *
 * The list re-sorts under the finger, so the ⋮ a reader opened on one row can
 * fire a verb at another: the walker tapped **Kill session** on row 2 and was
 * asked **"Wrap up rv-desktop-1?"**. `OrderLock` is the fix for the mis-target;
 * this is the fix for the case where a mis-target still gets through, and it is
 * the LAST thing between a wrong tap and an ended session.
 *
 * A single-line title — `Text("Kill $name?")` — put the verb and the target in
 * the same weight at the same size in one sentence, which is exactly the shape
 * the eye skims. So the verb becomes a quiet label and the TARGET becomes the
 * headline: monospace, because that is how a session name is written everywhere
 * else in this product, and two lines rather than an ellipsis, because the tail
 * of a name like `rvphoneproj-docs-reviewer` is the part that distinguishes it
 * from `rvphoneproj-lead`.
 *
 * Shared rather than written per dialog because there are seven of them across
 * two shells, and the one that keeps the old shape is the one that ends a
 * session nobody chose.
 *
 * @param verb "Kill session", "Wrap up", "Archive", "Remove" — the action, small.
 * @param target the session, app or route name. The large text.
 */
@Composable
fun ConfirmTitle(verb: String, target: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            verb,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            target,
            // ⚠ THE LARGEST TEXT IN THE DIALOG, and the assertion in
            // `ConfirmTitleTest` is on exactly that. `headlineSmall` outranks the
            // `bodyMedium` of the explanation below it and the `labelLarge` of
            // both buttons, so the name is what the eye lands on first.
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
