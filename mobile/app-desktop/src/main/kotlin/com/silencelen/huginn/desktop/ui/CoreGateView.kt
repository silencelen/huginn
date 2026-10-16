package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.desktop.core.CoreGate

/**
 * The whole window while huginn core is not answering (owner rule 2026-10-15:
 * the desktop is a layer on top of the core, which includes the CLI, and does
 * not work without it). One action, the one that fixes it.
 */
@Composable
fun CoreGateView(store: AppStore, gate: CoreGate) {
    val setup by store.coreDevice.setup.collectAsState()
    val windows = (System.getProperty("os.name") ?: "").startsWith("Windows")
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (gate) {
                CoreGate.CHECKING, CoreGate.READY -> {
                    Text("Connecting to huginn core…", style = MaterialTheme.typography.titleMedium)
                }
                CoreGate.NOT_SET_UP -> {
                    Text("Set up huginn core", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Huginn Desktop runs on huginn core: the background service on this computer that " +
                            "also gives you the huginn command line. It is installed, and needs to be set up " +
                            "to start at boot." + if (windows) " Windows asks for administrator approval once." else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { store.coreDevice.setUp() }) { Text("Set up huginn core") }
                }
                CoreGate.NOT_RUNNING -> {
                    Text("huginn core is not running", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Huginn Desktop runs on huginn core, which is set up on this computer but not answering.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { store.coreDevice.startCore() }) { Text("Start huginn core") }
                }
            }
            setup?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (gate == CoreGate.NOT_RUNNING || gate == CoreGate.NOT_SET_UP) {
                Text(
                    "From a terminal: huginn core status says what is wrong, huginn core install sets it up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
