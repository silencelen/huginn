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

    private companion object {
        val K = DraftBook.chatKey("c1")
    }
}
