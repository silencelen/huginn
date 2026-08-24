package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.ui.DevicesSection
import kotlinx.coroutines.launch

/**
 * The machines that have offered themselves to huginn.
 *
 * The rows are [DevicesSection], shared with the phone, so a device does not read
 * as one thing here and another in a pocket. What stays here is the frame: the
 * empty state names the toggle that lives in THIS app's Settings, which is not
 * advice a phone can give.
 */
@Composable
fun DevicesPane(store: AppStore) {
    val devices by store.devices.collectAsState()
    val scope = rememberCoroutineScope()

    if (devices.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No devices yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "A device is another machine that can run work in its own context. " +
                    "Turn on \"Give Huginn access to this PC\" in Settings, on the machine " +
                    "you want to offer, and it appears here. A machine with no desktop app " +
                    "offers itself with \"huginn device on\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
    ) {
        DevicesSection(
            devices = devices,
            onStart = { d, mode -> scope.launch { store.startChatOn(d.id, mode) } },
            onForget = { d -> scope.launch { store.forgetDevice(d.id) } },
            header = null,
        )
    }
}
