package com.silencelen.huginn.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Suggested next messages, as chips above the composer.
 *
 * A chip FILLS THE COMPOSER; it does not send. That is the entire contract and
 * it is what makes suggestions safe to offer at all: a wrong guess costs a
 * keystroke to fix rather than a message nobody meant to send. Whether they
 * belong on screen at this instant is [Suggest.visible]'s decision, not this
 * composable's — it draws what it is given.
 *
 * One row, scrolled horizontally rather than wrapped: wrapping lets a set of
 * long suggestions grow to three lines and push the composer off a phone, and
 * the whole surface is optional.
 */
@Composable
fun SuggestionChips(
    suggestions: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        suggestions.forEach { text ->
            SuggestionChip(
                onClick = { onPick(text) },
                label = {
                    Text(
                        text,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}
