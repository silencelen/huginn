package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.ProjectLive
import com.silencelen.huginn.data.ProjectRow
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.desktop.DesktopSettings
import com.silencelen.huginn.desktop.Presence
import com.silencelen.huginn.ui.theme.HuginnTheme
import com.silencelen.huginn.ui.theme.MonoStyleDesktop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The Projects list pane scrolls when the tree is taller than the window.
 *
 * ⚠ THE BUG (owner, 2026-11-03): "the projects list isn't scrollable and should
 * be when it goes off the space vertically… when I have projects expanded I have
 * to minimise them to reach projects lower in the list." Reproduced on desktop
 * 2.10.0 with five projects folded open: the last project's members were below
 * the window and the wheel did nothing. The shared tree only scrolls when asked
 * to (`ProjectsListView(scroll = true)`, the phone's), and this pane never asked
 * and never scrolled around it either.
 *
 * Hosted the way `Shell` hosts the list pane: a fixed column, clipped, so a row
 * past the bottom edge is not on screen — which is what the reader saw.
 */
@OptIn(ExperimentalTestApi::class)
class ProjectsPaneScrollTest {

    /** Five live projects, every one folded open to a lead and five members: far taller than the pane. */
    private val projects = (1..5).map { i ->
        ProjectRow(id = "p$i", name = "project-$i", slug = "project-$i", kind = "team", status = "active", memberCount = 6, alive = 6)
    }
    private val members = projects.associate { p ->
        p.id to (0..5).map { m ->
            ProjectLive(
                role = if (m == 0) "lead" else "${p.slug}-w$m",
                name = "${p.slug}-m$m",
                alive = true,
                present = true,
                lead = m == 0,
                state = "idle",
            )
        }
    }

    /** The last row of the tree: the last project's last member (lead first, then by role). */
    private val last = "project-5-w5"

    private val dir = Files.createTempDirectory("projects-scroll").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> AppStore.seed(field: String, value: T) {
        val f = AppStore::class.java.getDeclaredField(field).apply { isAccessible = true }
        (f.get(this) as MutableStateFlow<T>).value = value
    }

    private fun ComposeUiTest.pane() {
        val store = AppStore(DesktopSettings(File(dir, "settings.json")), Presence(), scope)
        store.seed("_projects", projects)
        store.seed("_projectMembers", members)
        store.seed("_projectsExpanded", projects.map { it.id }.toSet())
        setContent {
            HuginnTheme(darkTheme = true, monoStyle = MonoStyleDesktop, rootSurface = true) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.width(320.dp).height(480.dp).clipToBounds().testTag("pane")) { ProjectsList(store) }
                }
            }
        }
        waitForIdle()
        // The premise: the tree really is taller than the pane.
        onNodeWithText(last).assertIsNotDisplayed()
    }

    /**
     * The tree is inside a scroll that can bring its last row on screen — which is
     * what the wheel drives. The wheel itself cannot be injected here: this
     * harness's synthetic wheel event moves no `verticalScroll` at all, not even a
     * bare one (measured, Compose 1.7.3), so the semantic scroll stands in for it;
     * with no scroll parent it throws, which is the old pane.
     */
    @Test
    fun `the last member of a tree taller than the pane can be scrolled to`() = runComposeUiTest {
        pane()
        onNodeWithText(last).performScrollTo()
        waitForIdle()
        onNodeWithText(last).assertIsDisplayed()
    }

    /** The other desktop lists carry the house scrollbar on their right edge; so does this one. */
    @Test
    fun `the scrollbar drags the tree to its last member`() = runComposeUiTest {
        pane()
        onNodeWithTag("pane").performMouseInput {
            // The thumb starts at the top of the bar, on the pane's right edge,
            // under the pinned header.
            val x = width - 4.dp.toPx()
            moveTo(Offset(x, height * 0.15f))
            press()
            moveTo(Offset(x, height - 2f))
            release()
        }
        waitForIdle()
        onNodeWithText(last).assertIsDisplayed()
    }
}
