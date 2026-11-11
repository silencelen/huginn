package com.silencelen.huginn

import com.silencelen.huginn.data.AgentRequest
import com.silencelen.huginn.data.AgentRequestList
import com.silencelen.huginn.data.Session
import com.silencelen.huginn.ui.AgentRequestLabels
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentRequestLabelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun bash(cmd: String?, agentType: String? = "general-purpose") = AgentRequest(
        id = "1-2", agentType = agentType, tool = "Bash", command = cmd,
        input = buildJsonObject { cmd?.let { put("command", it) } },
    )

    @Test
    fun aBashRequestShowsItsWholeCommand() {
        val long = "cd /repo && git push origin ios-ui 2>&1 | tail -1; " + "x".repeat(300)
        val r = bash(long)
        assertEquals("general-purpose wants to run", AgentRequestLabels.headline(r))
        assertEquals(long, AgentRequestLabels.body(r), "never summarised: the person approves exactly these words")
        assertTrue(AgentRequestLabels.isBash(r))
    }

    @Test
    fun aBashRequestFallsBackToTheInputsCommand() {
        val r = AgentRequest(tool = "Bash", input = buildJsonObject { put("command", "ls -la") })
        assertEquals("ls -la", AgentRequestLabels.body(r))
        assertEquals("An agent wants to run", AgentRequestLabels.headline(r))
    }

    @Test
    fun anotherToolShowsEveryKeyTellingKeysFirst() {
        val r = AgentRequest(
            agentType = "Explore", tool = "WebFetch",
            input = buildJsonObject { put("prompt", "summarise"); put("url", "https://example.net/a") },
        )
        assertEquals("Explore wants to use WebFetch", AgentRequestLabels.headline(r))
        assertEquals("url: https://example.net/a\nprompt: summarise", AgentRequestLabels.body(r))
    }

    @Test
    fun aLongInputIsNeverCut() {
        // Security scan 2026-11-11, F1: Allow approves all of it, so all of it is shown.
        val old = "y".repeat(400)
        val r = AgentRequest(tool = "Edit", input = buildJsonObject {
            put("file_path", "/a/b.kt"); put("old_string", old); put("new_string", "line 1\nrm -rf ~")
            put("edits", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))))
            put("replace_all", true)
        })
        val s = AgentRequestLabels.body(r)!!
        assertTrue(s.startsWith("file_path: /a/b.kt\n"))
        assertTrue("old_string: $old" in s)
        assertTrue("new_string:\nline 1\nrm -rf ~" in s, "a multi-line value starts on its own line, whole")
        assertTrue("replace_all: true" in s)
        assertTrue("edits:\n[\n    1,\n    2\n]" in s, "nested values are spelled out, not counted")
        assertNull(AgentRequestLabels.detail(buildJsonObject { }))
    }

    @Test
    fun aPaddedPathCannotHideWhereItGoes() {
        // The scan's attack: no-break-space padding pushing `/../../.ssh/...` past the cut.
        val path = "/home/alex/proj/notes.md" + "\u00a0".repeat(200) + "/../../../.ssh/authorized_keys"
        val r = AgentRequest(tool = "Write", input = buildJsonObject { put("file_path", path); put("content", "ssh-ed25519 AAAA") })
        val shown = com.silencelen.huginn.ui.PromptDisplay.visible(AgentRequestLabels.body(r)!!)
        assertTrue("authorized_keys" in shown)
        assertTrue(com.silencelen.huginn.ui.PromptDisplay.GAP in shown, "the padding is one visible gap")
        assertTrue("\u00a0" !in shown)
        assertTrue("content: ssh-ed25519 AAAA" in shown)
    }

    @Test
    fun aPathThatMovesSaysWhereItResolves() {
        assertEquals("/home/.ssh/authorized_keys", AgentRequestLabels.resolvedPath("/home/alex/proj/../../.ssh/authorized_keys"))
        assertEquals("/etc/passwd", AgentRequestLabels.resolvedPath("/../../etc//passwd"))
        assertEquals("/a/b", AgentRequestLabels.resolvedPath("/a/./b"))
        assertNull(AgentRequestLabels.resolvedPath("/a/b.kt"))
        assertNull(AgentRequestLabels.resolvedPath("/a/b/"))
        assertNull(AgentRequestLabels.resolvedPath("../x"))
        val r = AgentRequest(tool = "Write", input = buildJsonObject { put("file_path", "/p/q/../../root/.bashrc"); put("content", "x") })
        assertTrue(AgentRequestLabels.body(r)!!.startsWith("file_path: /p/q/../../root/.bashrc\n  → resolves to /root/.bashrc\n"))
    }

    @Test
    fun allowNeedsTheDaemonsDigest() {
        assertTrue(!AgentRequestLabels.canAllow(bash("ls")))
        assertTrue(AgentRequestLabels.canAllow(bash("ls").copy(digest = "ab".repeat(32))))
    }

    @Test
    fun anMcpToolReadsAsServerAndTool() {
        assertEquals("claude_ai_Gmail · send_message",
            AgentRequestLabels.toolName(AgentRequest(tool = "mcp__claude_ai_Gmail__send_message")))
        assertEquals("Read", AgentRequestLabels.toolName(AgentRequest(tool = "Read")))
    }

    @Test
    fun theExpiryCountsDownInWholeMinutesAndNeverNegative() {
        val r = bash("ls").copy(expiresAt = 1_000_000L)
        assertEquals("Declined automatically in 10 min", AgentRequestLabels.expiry(r, 1_000_000L - 9 * 60_000L - 30_000L))
        assertEquals("Declined automatically in under a minute", AgentRequestLabels.expiry(r, 1_000_000L - 20_000L))
        assertEquals("Being declined now", AgentRequestLabels.expiry(r, 1_000_001L))
        assertNull(AgentRequestLabels.expiry(r.copy(expiresAt = 0), 5L), "no deadline given")
        assertNull(AgentRequestLabels.expiry(r, 0L), "no clock")
    }

    @Test
    fun theRowMarkIsQuietAndAbsentAtZero() {
        assertNull(AgentRequestLabels.rowMark(0))
        assertEquals("agent asks", AgentRequestLabels.rowMark(1))
        assertEquals("3 agent requests", AgentRequestLabels.rowMark(3))
    }

    @Test
    fun theWireShapesDecodeAndAnOlderDaemonReadsAsNone() {
        val list = json.decodeFromString(AgentRequestList.serializer(), """
            {"requests":[{"id":"1794382118113601751-2255438","agentId":"a116","agentType":"general-purpose",
              "tool":"Bash","input":{"command":"git push","timeout":120000},"command":"git push",
              "askedAt":1794382118122,"expiresAt":1794382718122,"digest":"9f2c","future":"ignored"}]}
        """.trimIndent())
        val r = list.requests.single()
        assertEquals("git push", r.command)
        assertEquals(1794382718122L, r.expiresAt)
        assertEquals("9f2c", r.digest)
        val row = json.decodeFromString(Session.serializer(), """{"name":"s"}""")
        assertEquals(0, row.agentRequests, "absent on an older daemon")
        assertEquals(2, json.decodeFromString(Session.serializer(), """{"name":"s","agentRequests":2}""").agentRequests)
    }
}
