package com.silencelen.huginn.desktop.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.desktop.TerminalLaunch

/**
 * The one "Open in terminal" verb every row menu and the palette share, built
 * from the Terminal setting and this machine.
 *
 * The LABEL is decided here, once per setting change, because it depends on the
 * machine: Windows says "Open in PowerShell" only when PowerShell is the terminal
 * that will actually open ([TerminalLaunch.label]), and asking the machine on
 * every menu build would be a PATH walk per right-click. The OPEN is the header's
 * behaviour, unchanged: spawn through the CLI, and when that cannot happen put
 * the line to type on the clipboard and say why in the status line
 * ([AppStore.noteAction]), because an app started from a menu has no stderr
 * anybody reads.
 */
@Composable
fun rememberTerminalVerb(store: AppStore): TerminalVerb {
    // Collected so a change on the Appearance page re-labels every menu at once;
    // the value itself is read inside open() through TerminalLaunch.preferred.
    val choice by store.settings.terminal.collectAsState()
    val clipboard = LocalClipboardManager.current
    return remember(choice, clipboard) {
        TerminalVerb(TerminalLaunch.label()) { name ->
            TerminalLaunch.open(name)?.let { why ->
                System.err.println("huginn: $why")
                clipboard.setText(AnnotatedString(TerminalLaunch.shellLine(name)))
                store.noteAction(TerminalLaunch.failureNote(name, why))
            }
        }
    }
}
