package com.silencelen.huginn.desktop.core

import com.silencelen.huginn.desktop.DesktopSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The core tray's menu acting on this app (owner rule 2026-10-17: one icon,
 * one menu). What arrives as "gui.cmd" is read strictly: an unknown verb, a
 * Close to tray with no value or a target that is not huginn:// does nothing.
 */
class GuiCommandTest {
    private val device = CoreDevice(
        CoroutineScope(Dispatchers.IO),
        DesktopSettings(Files.createTempDirectory("gui-cmd").resolve("settings.json").toFile()),
    ) { null }

    @Test
    fun `the tray's commands are read, and nothing else is`() {
        assertEquals(CoreDevice.GuiCommand.Quit, device.guiCommand("""{"cmd":"quit"}"""))
        assertEquals(CoreDevice.GuiCommand.Open(null), device.guiCommand("""{"cmd":"open"}"""))
        assertEquals(
            CoreDevice.GuiCommand.Open("huginn://open?view=sessions&id=dcwifi"),
            device.guiCommand("""{"cmd":"open","url":"huginn://open?view=sessions&id=dcwifi"}"""),
        )
        assertEquals(CoreDevice.GuiCommand.Open(null), device.guiCommand("""{"cmd":"open","url":"https://example.net"}"""), "only huginn:// targets")
        assertEquals(CoreDevice.GuiCommand.CloseToTray(false), device.guiCommand("""{"cmd":"closeToTray","value":false}"""))
        assertEquals(null, device.guiCommand("""{"cmd":"closeToTray"}"""))
        assertEquals(null, device.guiCommand("""{"cmd":"rm"}"""))
        assertEquals(null, device.guiCommand("not json"))
    }
}
