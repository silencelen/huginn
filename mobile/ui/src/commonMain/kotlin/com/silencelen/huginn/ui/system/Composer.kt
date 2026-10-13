package com.silencelen.huginn.ui.system

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation

/**
 * One line the composer has to say about the message being written: the send
 * queue ("held until the turn ends"), a held wrap-up, a message that went in on
 * top of somebody's draft. ONE line, in onSurfaceVariant, whatever it is —
 * none of these is a failure, and the error ink is for failures (UI-AUDIT.md
 * section 3, "Accent"). [onDismiss] draws the dismiss control when present.
 */
data class ComposerNotice(val text: String, val onDismiss: (() -> Unit)? = null)

/**
 * The first notice that has something to say, in the order given. A caller with
 * three possible notices passes them most-consequential first; the line shows
 * one at a time, and dismissing it uncovers the next.
 */
fun firstNotice(vararg notices: ComposerNotice?): ComposerNotice? = notices.firstOrNull { it != null }

/**
 * THE ONE COMPOSER (UI-AUDIT.md section 3, "Composer"; brief PR 4): Chat, Session
 * and Terminal on the phone draw through this.
 *
 * The anatomy, top to bottom, on surfaceContainerLow:
 *  - [header]: what rides with the message (the page reference, attachment chips);
 *  - the notice line ([notice]), labelSmall in onSurfaceVariant;
 *  - [extras]: an optional row of extra controls (key chips and the like);
 *  - the input row: [leading] plain icon buttons (attach, mic), the outlined field
 *    at the 20 dp radius, [trailing] plain icon buttons, then the verb — Send as
 *    THE one filled primary circle on the screen, or Stop as a plain outlined
 *    circle when [stopInstead].
 *
 * Presentation only. What [onSend] and [onStop] do, and when the caller allows
 * them ([canSend], [stopInstead]), is the caller's send contract and is passed
 * through untouched.
 *
 * @param stopInstead show Stop in Send's place.
 * @param sendAlongsideStop when [stopInstead] and [canSend] are both true, keep
 *   Send beside Stop rather than hiding it — for a composer where something other
 *   than typed text (an attachment) can make a message while a turn runs.
 * @param rowModifier applied to the input row before its own padding: the phone
 *   passes its keyboard and navigation inset here, so everything above the row
 *   (notice, chips) stays above the keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    canSend: Boolean = value.isNotBlank(),
    sendLabel: String = "Send",
    stopInstead: Boolean = false,
    onStop: () -> Unit = {},
    stopLabel: String = "Stop",
    sendAlongsideStop: Boolean = false,
    notice: ComposerNotice? = null,
    maxLines: Int = 6,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    fieldModifier: Modifier = Modifier,
    rowModifier: Modifier = Modifier,
    header: @Composable ColumnScope.() -> Unit = {},
    extras: (@Composable RowScope.() -> Unit)? = null,
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.surfaceContainerLow, modifier = modifier) {
        Column {
            header()
            notice?.let { ComposerNoticeLine(it) }
            extras?.let { row ->
                Row(
                    Modifier.fillMaxWidth().padding(start = Scale.s12, end = Scale.s8, top = Scale.s4),
                    horizontalArrangement = Arrangement.spacedBy(Scale.s4),
                    verticalAlignment = Alignment.CenterVertically,
                    content = row,
                )
            }
            // ONE HEIGHT FOR ONE LINE. The field is a plain BasicTextField in the
            // outlined decoration with its padding set so a single line is exactly
            // [COMPOSER_VERB] tall, the same as every circle beside it; the row
            // aligns to the bottom, so on one line everything shares a centre and
            // on more lines the controls stay anchored on the last one. Material's
            // OutlinedTextField is 56 dp at minimum, which sat every 46 dp control
            // 5 dp below the field's centre. One gap (Scale.s8) between everything.
            val interaction = remember { MutableInteractionSource() }
            Row(
                rowModifier
                    .fillMaxWidth()
                    .padding(start = Scale.s8, end = Scale.s8, top = Scale.s8, bottom = Scale.s8),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(Scale.s8),
            ) {
                leading()
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = fieldModifier.weight(1f).heightIn(min = COMPOSER_VERB),
                    enabled = enabled,
                    maxLines = maxLines,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    interactionSource = interaction,
                    decorationBox = { inner ->
                        OutlinedTextFieldDefaults.DecorationBox(
                            value = value,
                            innerTextField = inner,
                            enabled = enabled,
                            singleLine = false,
                            visualTransformation = VisualTransformation.None,
                            interactionSource = interaction,
                            placeholder = if (placeholder != null) {
                                {
                                    Text(
                                        placeholder,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = scheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                            } else {
                                null
                            },
                            contentPadding = PaddingValues(horizontal = Scale.s16, vertical = 11.dp),
                            container = {
                                OutlinedTextFieldDefaults.Container(
                                    enabled = enabled,
                                    isError = false,
                                    interactionSource = interaction,
                                    shape = RoundedCornerShape(Scale.s20),
                                )
                            },
                        )
                    },
                )
                trailing()
                if (stopInstead) {
                    StopCircle(stopLabel, onStop)
                    if (sendAlongsideStop && canSend) {
                        SendCircle(sendLabel, enabled && canSend, onSend)
                    }
                } else {
                    SendCircle(sendLabel, enabled && canSend, onSend)
                }
            }
        }
    }
}

/** The one filled button on the screen. Circle, primary, the outlined Send glyph. */
@Composable
fun SendCircle(label: String, enabled: Boolean, onClick: () -> Unit, size: Dp = COMPOSER_VERB) {
    val scheme = MaterialTheme.colorScheme
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (enabled) scheme.primary else scheme.surfaceContainerHigh),
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.Send,
            contentDescription = label,
            tint = if (enabled) scheme.onPrimary else scheme.onSurfaceVariant,
            modifier = Modifier.size(Scale.iconLarge),
        )
    }
}

/** Stopping is not a failure: a plain outlined circle in onSurface ink. */
@Composable
fun StopCircle(label: String, onClick: () -> Unit, size: Dp = COMPOSER_VERB) {
    val scheme = MaterialTheme.colorScheme
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .border(1.dp, scheme.outline, CircleShape),
    ) {
        Icon(Icons.Outlined.Stop, contentDescription = label, tint = scheme.onSurface, modifier = Modifier.size(Scale.iconLarge))
    }
}

@Composable
private fun ComposerNoticeLine(notice: ComposerNotice) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = Scale.s4, top = Scale.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            notice.text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        notice.onDismiss?.let { dismiss ->
            IconButton(onClick = dismiss, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Dismiss the notice",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** The verb circle: the phone's 46 dp thumb target, unchanged from the composers it replaces. */
val COMPOSER_VERB: Dp = 46.dp
