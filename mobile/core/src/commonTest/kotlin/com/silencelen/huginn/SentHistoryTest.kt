package com.silencelen.huginn

import com.silencelen.huginn.data.HuginnSettings
import com.silencelen.huginn.data.SentHistory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A settings stub that records only what SentHistory touches. */
private class HistorySettings : HuginnSettings {
    val stored = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    override val sentHistory: Flow<Map<String, List<String>>> get() = stored
    override suspend fun setSentHistory(value: Map<String, List<String>>) { stored.value = value }

    override val baseUrl = flowOf(""); override val token = flowOf("")
    override val routePinned = flowOf(false)
    override suspend fun setBaseUrl(value: String) {}
    override suspend fun setToken(value: String) {}
    override suspend fun selectRoute(url: String, pinned: Boolean) {}
    override suspend fun clientId() = "t"
    override val fontScale = flowOf(9f)
    override suspend fun setFontScale(value: Float) {}
    override val notifyEnabled = flowOf(false)
    override suspend fun setNotifyEnabled(value: Boolean) {}
    override val watchEnabled = flowOf(false)
    override suspend fun setWatchEnabled(value: Boolean) {}
    override val watchSeeded = flowOf(false)
    override suspend fun setWatchSeeded(value: Boolean) {}
    override val notifiedSessions = flowOf(emptySet<String>())
    override suspend fun setNotifiedSessions(value: Set<String>) {}
    override val runningChats = flowOf(emptySet<String>())
    override suspend fun setRunningChats(value: Set<String>) {}
    override val chatRuns = flowOf(emptyMap<String, Long>())
    override suspend fun setChatRuns(value: Map<String, Long>) {}
    override val drafts = flowOf(emptyMap<String, String>())
    override suspend fun setDrafts(value: Map<String, String>) {}
    override val lastContactAt = flowOf(0L); override val lastAlarmAt = flowOf(0L)
    override val lastWatchError = flowOf(""); override val lastWatchErrorAt = flowOf(0L)
    override suspend fun noteContact(atMs: Long) {}
    override suspend fun noteAlarm(atMs: Long) {}
    override suspend fun noteWatchError(message: String, atMs: Long) {}
}

class SentHistoryTest {

    @Test
    fun `record appends, persists, and ignores blanks`() = runTest {
        val s = HistorySettings()
        val h = SentHistory(s, this)
        h.record("sess:a", "first")
        h.record("sess:a", "   ")
        h.record("sess:a", "second")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("first", "second"), h["sess:a"])
        assertEquals(listOf("first", "second"), s.stored.value["sess:a"])
    }

    @Test
    fun `an exact repeat of the newest entry is not recorded twice`() = runTest {
        val h = SentHistory(HistorySettings(), this)
        h.record("chat:x", "same")
        h.record("chat:x", "same")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("same"), h["chat:x"])
    }

    @Test
    fun `capacity drops the oldest`() = runTest {
        val h = SentHistory(HistorySettings(), this)
        repeat(SentHistory.CAPACITY + 5) { h.record("k", "msg$it") }
        testScheduler.advanceUntilIdle()
        assertEquals(SentHistory.CAPACITY, h["k"].size)
        assertEquals("msg5", h["k"].first())
    }

    @Test
    fun `move carries history across a rename`() = runTest {
        val h = SentHistory(HistorySettings(), this)
        h.record("sess:old", "hello")
        h.move("sess:old", "sess:new")
        testScheduler.advanceUntilIdle()
        assertTrue(h["sess:old"].isEmpty())
        assertEquals(listOf("hello"), h["sess:new"])
    }

    @Test
    fun `clear drops the target`() = runTest {
        val h = SentHistory(HistorySettings(), this)
        h.record("sess:x", "hello")
        h.clear("sess:x")
        testScheduler.advanceUntilIdle()
        assertTrue(h["sess:x"].isEmpty())
    }

    @Test
    fun `load merges — a send racing the load survives`() = runTest {
        val s = HistorySettings()
        s.stored.value = mapOf("sess:a" to listOf("from-disk"))
        val h = SentHistory(s, this)
        h.record("sess:b", "typed-before-load")
        h.load()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("from-disk"), h["sess:a"])
        assertEquals(listOf("typed-before-load"), h["sess:b"])
    }
}
