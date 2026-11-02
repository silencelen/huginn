package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.SessionQuestion
import com.silencelen.huginn.data.SessionQuestions
import com.silencelen.huginn.ui.PromptDisplay
import com.silencelen.huginn.ui.QuestionCard
import com.silencelen.huginn.ui.QuestionDraft
import com.silencelen.huginn.ui.theme.HuginnTheme
import com.silencelen.huginn.ui.theme.MonoStyleDesktop
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `session` question card (appd 3.17): what a reader sees, and what Start
 * sends. The card is `:ui`'s and both shells draw it; this is the desktop's
 * window on it.
 */
@OptIn(ExperimentalTestApi::class)
class SessionQuestionCardTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun question(prompt: String = "Audit the LoRa node firmware.\nStart with power."): SessionQuestion = json.decodeFromString<SessionQuestions>(
        """
        {"questions":[{"id":"sq-1","kind":"session","toolName":"start_session",
          "input":{"directory":"/srv/work/lora","prompt":${JsonPrimitive(prompt)},"name":"lora-audit"},
          "fingerprint":"fp-1","since":1000,"bound":true,
          "requester":{"sessionName":"planner","chatId":null},"expiresAt":4600000}]}
        """.trimIndent(),
    ).questions[0]

    @Test
    fun `the card draws the directory, the prompt verbatim, who asked, and Start sends allow with the edited name`() = runComposeUiTest {
        val sent = mutableListOf<JsonObject>()
        // Hoisted as STATE, as the shells hold it: the edited name has to reach
        // the next composition, or Start would send the seed.
        val draft = mutableStateOf(QuestionDraft.of(question()))
        setContent {
            HuginnTheme(darkTheme = true, monoStyle = MonoStyleDesktop, rootSurface = true) {
                Box(Modifier.width(600.dp)) {
                    QuestionCard(
                        question = question(),
                        draft = draft.value,
                        onDraft = { draft.value = it },
                        onAnswer = { sent += it },
                        nowMs = 1_000_000,
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag("question.session.directory", useUnmergedTree = true).assertTextContains("/srv/work/lora")
        onNodeWithTag("question.session.prompt", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Audit the LoRa node firmware.\nStart with power.", substring = true, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("question.session.counts", useUnmergedTree = true).assertTextContains("2 lines · 47 characters")
        onNodeWithTag("question.session.meta", useUnmergedTree = true).assertTextContains("asked by planner", substring = true)
        onNodeWithTag("question.session.meta", useUnmergedTree = true).assertTextContains("expires in 1h", substring = true)
        onNodeWithTag("question.session.start", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("question.session.decline", useUnmergedTree = true).assertIsDisplayed()

        // The suggested name is in the field; edit it, then Start.
        onNodeWithTag("question.session.name", useUnmergedTree = true).assertTextContains("lora-audit")
        onNodeWithTag("question.session.name", useUnmergedTree = true).performTextClearance()
        onNodeWithTag("question.session.name", useUnmergedTree = true).performTextInput("lora-2")
        waitForIdle()
        onNodeWithTag("question.session.start", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(listOf("""{"behavior":"allow","name":"lora-2"}"""), sent.map { it.toString() })
    }

    @Test
    fun `a prompt padded with 200 spaces cannot hide its tail - the block wraps inside the card, the gap is marked, the size is said`() = runComposeUiTest {
        // ⚠ THE FAIL-FIRST (security scan 2026-11-02, F2). The old block scrolled
        // sideways, so the Text was laid out at its full single-line width —
        // some 1,900dp for this prompt in a 600dp card — and "rm -rf" sat past
        // the edge with no ellipsis and no scrollbar; the person saw "Tidy the
        // README." and tapped Start.
        val hidden = "Tidy the README." + " ".repeat(200) + "rm -rf /srv/work/lora"
        val draft = mutableStateOf(QuestionDraft.of(question(hidden)))
        setContent {
            HuginnTheme(darkTheme = true, monoStyle = MonoStyleDesktop, rootSurface = true) {
                Box(Modifier.width(600.dp)) {
                    QuestionCard(question = question(hidden), draft = draft.value, onDraft = { draft.value = it }, onAnswer = {}, nowMs = 1_000_000)
                }
            }
        }
        waitForIdle()
        val text = onNodeWithText("rm -rf", substring = true, useUnmergedTree = true)
        text.assertIsDisplayed()
        // UNCLIPPED: the clipped bounds are always inside the scroller, which is
        // exactly how the old block hid the tail. The laid-out width is the fact.
        val right = text.getUnclippedBoundsInRoot().right
        assertTrue(right <= 600.dp, "the prompt lays out inside the card's 600dp, not off its right edge (right = $right)")
        text.assertTextContains("Tidy the README.${PromptDisplay.GAP}rm -rf /srv/work/lora")
        onNodeWithTag("question.session.counts", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("question.session.counts", useUnmergedTree = true).assertTextContains("1 line · 237 characters")
    }

    @Test
    fun `a prompt of 300 lines then rm -rf - Start is disabled with the scroll reason until the block is scrolled to its end (F1, third scan 2026-11-02)`() = runComposeUiTest {
        // ⚠ THE FAIL-FIRST. The block was capped at 180dp with no scrollbar and no
        // cue; "rm -rf" sat below the fold and Start was live.
        val padded = (1..300).joinToString("\n") { "step $it: read the README" } + "\nrm -rf /srv/work/lora"
        val sent = mutableListOf<JsonObject>()
        val draft = mutableStateOf(QuestionDraft.of(question(padded)))
        setContent {
            HuginnTheme(darkTheme = true, monoStyle = MonoStyleDesktop, rootSurface = true) {
                Box(Modifier.width(600.dp)) {
                    QuestionCard(question = question(padded), draft = draft.value, onDraft = { draft.value = it }, onAnswer = { sent += it }, nowMs = 1_000_000)
                }
            }
        }
        waitForIdle()
        onNodeWithTag("question.session.start", useUnmergedTree = true).assertIsNotEnabled()
        onNodeWithTag("question.session.scroll", useUnmergedTree = true).assertTextContains("scroll", substring = true, ignoreCase = true)
        onNodeWithTag("question.session.prompt.more", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("question.session.counts", useUnmergedTree = true).assertTextContains("301 lines", substring = true)
        onNodeWithTag("question.session.decline", useUnmergedTree = true).assertIsEnabled()
        // Scroll the block to its end: the cue and the reason go, Start is live.
        onNodeWithTag("question.session.prompt.scroll", useUnmergedTree = true).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 1_000_000f) }
        waitForIdle()
        onNodeWithTag("question.session.prompt.more", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("question.session.scroll", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("question.session.start", useUnmergedTree = true).assertIsEnabled()
        onNodeWithTag("question.session.start", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(listOf("""{"behavior":"allow","name":"lora-audit"}"""), sent.map { it.toString() })
    }

    @Test
    fun `a prompt padded with 60 blank lines shows them as one line that says so, with the tail in view`() = runComposeUiTest {
        val padded = "Summarize README.md for me." + "\n".repeat(61) + "rm -rf /srv/work/lora"
        val draft = mutableStateOf(QuestionDraft.of(question(padded)))
        setContent {
            HuginnTheme(darkTheme = true, monoStyle = MonoStyleDesktop, rootSurface = true) {
                Box(Modifier.width(600.dp)) {
                    QuestionCard(question = question(padded), draft = draft.value, onDraft = { draft.value = it }, onAnswer = {}, nowMs = 1_000_000)
                }
            }
        }
        waitForIdle()
        onNodeWithText("Summarize README.md for me.\n⋯ 60 blank lines ⋯\nrm -rf /srv/work/lora", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("question.session.counts", useUnmergedTree = true).assertTextContains("62 lines, 60 blank", substring = true)
        // Three lines fit under the cap: nothing below the fold, Start is live at once.
        onNodeWithTag("question.session.prompt.more", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("question.session.start", useUnmergedTree = true).assertIsEnabled()
    }

    @Test
    fun `Decline opens the reason line and sends deny with the words`() = runComposeUiTest {
        val sent = mutableListOf<JsonObject>()
        val draft = mutableStateOf(QuestionDraft.of(question()))
        setContent {
            HuginnTheme(darkTheme = true, monoStyle = MonoStyleDesktop, rootSurface = true) {
                Box(Modifier.width(600.dp)) {
                    QuestionCard(question = question(), draft = draft.value, onDraft = { draft.value = it }, onAnswer = { sent += it })
                }
            }
        }
        waitForIdle()
        onNodeWithTag("question.session.decline", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag("question.session.words", useUnmergedTree = true).performTextInput("not now")
        waitForIdle()
        onNodeWithTag("question.session.decline.send", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(listOf("""{"behavior":"deny","message":"not now"}"""), sent.map { it.toString() })
    }
}
