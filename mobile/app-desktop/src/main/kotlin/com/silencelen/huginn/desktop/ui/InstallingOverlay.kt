package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Over the whole window while "Install and restart" runs, until the app quits.
 *
 * ⚠ THE UPDATE THAT LOOKED HUNG (2026-10-31). The install ran inside the click,
 * and a portable install unpacks the whole app before it returns: the window
 * froze with nothing on it to say why, and a second click queued a second
 * install. Now the work is off the window's thread ([com.silencelen.huginn.desktop.AppStore.installUpdate])
 * and this says what is happening, swallows clicks while it does, and stays up
 * until the window closes.
 *
 * @param version the version being installed; null draws nothing.
 */
@Composable
fun InstallingOverlay(version: String?) {
    if (version == null) return
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
            // Nothing under the scrim is clickable while the app is replacing itself.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .testTag(INSTALLING_OVERLAY_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 420.dp).padding(24.dp),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Installing Huginn $version", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Huginn closes when the new version is in place, and opens again by itself.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

const val INSTALLING_OVERLAY_TAG: String = "update.installing"
