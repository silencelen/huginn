package com.silencelen.huginn

import com.silencelen.huginn.data.DraftBook
import com.silencelen.huginn.data.HuginnSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The draft rules, and mostly ONE of them: a draft that was deliberately cleared
 * must never be written back by a timer that was already in the air. That is the
 * Electron bug — send a message, watch it reappear in the composer as a draft.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftBookTest {

    /** Records every write, so a test can count them as well as read the last one. */
    private class FakeSettings : HuginnSettings {
        val writes = mutableListOf<Map<String, String>>()
        private val _drafts = MutableStateFlow<Map<String, String>>(emptyMap())

        fun seed(value: Map<String, String>) { _drafts.value = value }

        override val drafts: Flow<Map<String, String>> = _drafts
        override suspend fun setDrafts(value: Map<String, String>) {
            writes += value
            _drafts.value = value
        }

        // Nothing below is exercised here; the interface is wide because a client
        // remembers more than drafts.
        override val baseUrl: Flow<String> = MutableStateFlow("")
        override val token: Flow<String> = MutableStateFlow("")
        override val routePinned: Flow<Boolean> = MutableStateFlow(false)
        override val fontScale: Flow<Float> = MutableStateFlow(9f)
        override val notifyEnabled: Flow<Boolean> = MutableStateFlow(true)
        override val watchEnabled: Flow<Boolean> = MutableStateFlow(true)
        override val watchSeeded: Flow<Boolean> = MutableStateFlow(false)
        override val notifiedSessions: Flow<Set<String>> = MutableStateFlow(emptySet())
        override val runningChats: Flow<Set<String>> = MutableStateFlow(emptySet())
        override val chatRuns: Flow<Map<String, Long>> = MutableStateFlow(emptyMap())
        override val lastContactAt: Flow<Long> = MutableStateFlow(0)
        override val lastAlarmAt: Flow<Long> = MutableStateFlow(0)
        override val lastWatchError: Flow<String> = MutableStateFlow("")
        override val lastWatchErrorAt: Flow<Long> = MutableStateFlow(0)
        override suspend fun setBaseUrl(value: String) = Unit
        override suspend fun setToken(value: String) = Unit
        override suspend fun selectRoute(url: String, pinned: Boolean) = Unit
        override suspend fun clientId(): String = "test"
        override suspend fun setFontScale(value: Float) = Unit
        override suspend fun setNotifyEnabled(value: Boolean) = Unit
        override suspend fun setWatchEnabled(value: Boolean) = Unit
        override suspend fun setWatchSeeded(value: Boolean) = Unit
        override suspend fun setNotifiedSessions(value: Set<String>) = Unit
        override suspend fun setRunningChats(value: Set<String>) = Unit
        override suspend fun setChatRuns(value: Map<String, Long>) = Unit
        override suspend fun noteContact(atMs: Long) = Unit
        override suspend fun noteAlarm(atMs: Long) = Unit
        override suspend fun noteWatchError(message: String, atMs: Long) = Unit
    }

    @Test
    fun `typing writes once, after the pause`() = runTest {
        val settings = FakeSettings()
        val book = DraftBook(settings, this)

        "hello".forEachIndexed { i, _ -> book.set(K, "hello".take(i + 1)) }
        assertEquals(0, settings.writes.size, "a keystroke must not reach the disk")

        advanceTimeBy(DraftBook.DEBOUNCE_MS + 1)
        advanceUntilIdle()
        assertEquals(1, settings.writes.size, "five keystrokes, one write")
        assertEquals("hello", settings.writes.last()[K])
    }

    @Test
    fun `a cleared draft is not resurrected by the pending write`() = runTest {
        val settings = FakeSettings()
        val book = DraftBook(settings, this)

        book.set(K, "already sent")
        // The send happens INSIDE the debounce window — the exact race.
        book.clear(K)
        advanceUntilIdle()

        assertEquals("", book[K])
        assertEquals(emptyMap(), settings.writes.last(), "the sent text was written back as a draft")
    }

    @Test
    fun `leaving the view lands what is still in the air`() = runTest {
        val settings = FakeSettings()
        val book = DraftBook(settings, this)

        book.set(K, "half a thought")
        book.flush()
        advanceUntilIdle()

        assertEquals("half a thought", settings.writes.last()[K])
    }

    @Test
    fun `flushing with nothing pending does not write`() = runTest {
        val settings = FakeSettings()
        val book = DraftBook(settings, this)

        book.set(K, "typed")
        advanceTimeBy(DraftBook.DEBOUNCE_MS + 1)
        advanceUntilIdle()
        val after = settings.writes.size

        book.flush()
        advanceUntilIdle()
        assertEquals(after, settings.writes.size)
    }

    @Test
    fun `drafts are per target and survive a load`() = runTest {
        val settings = FakeSettings()
        settings.seed(mapOf(DraftBook.chatKey("c1") to "one", DraftBook.sessionKey("jtyper") to "two"))
        val book = DraftBook(settings, this)
        book.load()

        assertEquals("one", book[DraftBook.chatKey("c1")])
        assertEquals("two", book[DraftBook.sessionKey("jtyper")])
        assertEquals("", book[DraftBook.chatKey("c2")])
    }

    @Test
    fun `a chat key and a session key of the same name are different drafts`() {
        assertEquals("chat:x", DraftBook.chatKey("x"))
        assertEquals("sess:x", DraftBook.sessionKey("x"))
    }

    // ------------------------------------------------------- navigation
    //
    // The owner's report: "draft chats the user types and deleted between session
    // or chat navigations". The desktop's session composer was holding its text in
    // a Compose `remember`, which is discarded when the view leaves the
    // composition — so every switch away lost it, on every platform, with nothing
    // to do with the file. These are the properties that had to hold once the
    // session composer moved onto this book.

    @Test
    fun `walking between targets keeps every draft, under its own key`() = runTest {
        val settings = FakeSettings()
        val book = DraftBook(settings, this)

        // Type in a chat, leave for a session, type there, come back. This is the
        // exact sequence the owner described, at the layer that has to survive it.
        book.set(DraftBook.chatKey("c1"), "half a question")
        book.flush()
        book.set(DraftBook.sessionKey("jtyper"), "half an instruction")
        book.flush()
        advanceUntilIdle()

        assertEquals("half a question", book[DraftBook.chatKey("c1")])
        assertEquals("half an instruction", book[DraftBook.sessionKey("jtyper")])
        assertEquals(
            mapOf(
                DraftBook.chatKey("c1") to "half a question",
                DraftBook.sessionKey("jtyper") to "half an instruction",
            ),
            settings.writes.last(),
        )
    }

    @Test
    fun `a write in flight lands under the key it was typed in, not the one now open`() = runTest {
        // The Electron bug, in its other form: the timer is scheduled while chat A
        // is open and FIRES after the reader has moved to session B. The payload is
        // read at write time, so both texts land where they belong and neither is
        // written under the other's key.
        val settings = FakeSettings()
        val book = DraftBook(settings, this)

        book.set(DraftBook.chatKey("c1"), "for the chat")
        advanceTimeBy(DraftBook.DEBOUNCE_MS / 2)
        book.set(DraftBook.sessionKey("s1"), "for the session")
        advanceTimeBy(DraftBook.DEBOUNCE_MS + 1)
        advanceUntilIdle()

        assertEquals("for the chat", settings.writes.last()[DraftBook.chatKey("c1")])
        assertEquals("for the session", settings.writes.last()[DraftBook.sessionKey("s1")])
    }

    @Test
    fun `a draft typed before the load lands is not thrown away by it`() = runTest {
        val settings = FakeSettings()
        settings.seed(mapOf(DraftBook.chatKey("old") to "from disk"))
        val book = DraftBook(settings, this)

        // `load` is launched on the app scope from start(), while the window is
        // already composing — a composer can take a keystroke in that gap, and an
        // assigning load would silently swallow it.
        book.set(DraftBook.sessionKey("s1"), "typed during startup")
        book.load()

        assertEquals("typed during startup", book[DraftBook.sessionKey("s1")], "the load clobbered live typing")
        assertEquals("from disk", book[DraftBook.chatKey("old")])
    }

    @Test
    fun `what is on screen outranks what was on disk`() = runTest {
        // Same key on both sides. The phone wrote a draft for this session; the
        // reader is mid-sentence in this window. The reader wins.
        val settings = FakeSettings()
        settings.seed(mapOf(K to "stale"))
        val book = DraftBook(settings, this)

        book.set(K, "being typed right now")
        book.load()

        assertEquals("being typed right now", book[K])
    }

    @Test
    fun `a renamed target keeps its draft`() = runTest {
        val settings = FakeSettings()
        val book = DraftBook(settings, this)

        book.set(DraftBook.sessionKey("old-name"), "half an instruction")
        book.move(DraftBook.sessionKey("old-name"), DraftBook.sessionKey("new-name"))
        advanceUntilIdle()

        assertEquals("half an instruction", book[DraftBook.sessionKey("new-name")])
        assertEquals("", book[DraftBook.sessionKey("old-name")], "the old key would never be read again")
        // Written through, not merely held: the old key must not come back from
        // disk on the next launch, paid for on every keystroke thereafter.
        assertEquals(
            mapOf(DraftBook.sessionKey("new-name") to "half an instruction"),
            settings.writes.last(),
        )
    }

    @Test
    fun `moving a target with no draft writes nothing`() = runTest {
        val settings = FakeSettings()
        val book = DraftBook(settings, this)
        book.move(DraftBook.sessionKey("a"), DraftBook.sessionKey("b"))
        advanceUntilIdle()
        assertEquals(0, settings.writes.size)
    }

    private companion object {
        val K = DraftBook.chatKey("c1")
    }
}
