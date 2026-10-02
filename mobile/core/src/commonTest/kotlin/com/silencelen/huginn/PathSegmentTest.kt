package com.silencelen.huginn

import com.silencelen.huginn.data.HuginnClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 2026-10-02: a session name went into the URL by plain string interpolation,
 * so `releaseSize("VICTIM?x=")` left as `DELETE /v1/sessions/VICTIM?x=/size`.
 * appd routes on the pathname alone, so that was `killSession("VICTIM")` on the
 * wire, and the name is reachable from the exported MainActivity's `session`
 * extra. Every name, id, slug and role a path is built from is ONE segment, so
 * every one of them is pinned here, not just the call that was reported.
 */
class PathSegmentTest {

    private val seen = mutableListOf<HttpRequestData>()

    private val client = HuginnClient(
        baseUrlProvider = { "http://appd.test" },
        tokenProvider = { "t" },
        engine = MockEngine { request -> seen += request; respond("{}", HttpStatusCode.OK) },
    )

    private val evil = "x?y#z/../w"
    private val enc = "x%3Fy%23z%2F..%2Fw"

    @Test
    fun releasingThePaneSizeIsNeverTheKillRoute() = runTest {
        client.releaseSize("VICTIM?x=")
        val release = seen.last()
        client.killSession("VICTIM")
        val kill = seen.last()
        assertNotEquals(kill.method to kill.url.encodedPath, release.method to release.url.encodedPath)
        assertEquals("/v1/sessions/VICTIM%3Fx=/size", release.url.encodedPath)
        assertEquals("", release.url.encodedQuery)
    }

    @Test
    fun everyNameOrIdInAPathIsOneEncodedSegment() = runTest {
        val calls: List<Pair<String, suspend () -> Unit>> = listOf(
            "answerPrompt" to { client.answerPrompt(evil, 1) },
            "answerPromptMulti" to { client.answerPromptMulti(evil, listOf(1)) },
            "activateAccount" to { client.activateAccount(evil) },
            "forgetAccount" to { client.forgetAccount(evil) },
            "refreshAccount" to { client.refreshAccount(evil) },
            "undoLadder" to { client.undoLadder(evil) },
            "killSession" to { client.killSession(evil) },
            "sessionOverview" to { client.sessionOverview(evil) },
            "sessionGraph" to { client.sessionGraph(evil) },
            "saveSessionMeta" to { client.saveSessionMeta(evil, goals = "g") },
            "softEndSession" to { client.softEndSession(evil) },
            "compactSession" to { client.compactSession(evil) },
            "archiveSession" to { client.archiveSession(evil) },
            "reviveArchive" to { client.reviveArchive(evil) },
            "deleteArchive" to { client.deleteArchive(evil) },
            "archiveTranscript" to { client.archiveTranscript(evil) },
            "renameSession" to { client.renameSession(evil, "b") },
            "screen" to { client.screen(evil) },
            "releaseSize" to { client.releaseSize(evil) },
            "sessionSuggestions" to { client.sessionSuggestions(evil) },
            "chatSuggestions" to { client.chatSuggestions(evil) },
            "renameChat" to { client.renameChat(evil, "t") },
            "sessionAgents" to { client.sessionAgents(evil) },
            "agentTranscript" to { client.agentTranscript(evil, "a") },
            "sessionTranscript" to { client.sessionTranscript(evil) },
            "chatTranscript" to { client.chatTranscript(evil) },
            "sendKeys" to { client.sendKeys(evil, text = "hi") },
            "typingStatus" to { client.typingStatus(evil) },
            "device" to { client.device(evil) },
            "deleteDevice" to { client.deleteDevice(evil) },
            "deviceBeat" to { client.deviceBeat(evil) },
            "pollWork" to { client.pollWork(evil) },
            "round" to { client.round(evil) },
            "updateRound" to { client.updateRound(evil, enabled = true) },
            "deleteRound" to { client.deleteRound(evil) },
            "runRound" to { client.runRound(evil) },
            "ackRound" to { client.ackRound(evil) },
            "scratchpad" to { client.scratchpad(evil) },
            "saveScratchpad" to { client.saveScratchpad(evil, 1, content = "c") },
            "deleteScratchpad" to { client.deleteScratchpad(evil) },
            "updateChat" to { client.updateChat(evil, model = "m") },
            "chat" to { client.chat(evil) },
            "deleteChat" to { client.deleteChat(evil) },
            "cancelChat" to { client.cancelChat(evil) },
            "queueMessage" to { client.queueMessage(evil, "t") },
            "sendMessage" to { client.sendMessage(evil, "t").toList() },
            "streamChat" to { client.streamChat(evil).toList() },
            "project" to { client.project(evil) },
            "projectDashboard" to { client.projectDashboard(evil) },
            "saveProject" to { client.saveProject(evil, 1, name = "n") },
            "spawnProject" to { client.spawnProject(evil, 1) },
            "discardProposal" to { client.discardProposal(evil) },
            "adoptMember" to { client.adoptMember(evil, "r") },
            "messageProject" to { client.messageProject(evil, "a", "b", "t") },
            "deleteProject" to { client.deleteProject(evil) },
            "saveApp" to { client.saveApp(evil, 1, name = "n") },
            "deleteApp" to { client.deleteApp(evil) },
            "probeApp" to { client.probeApp(evil) },
            "appIconBytes" to { client.appIconBytes(evil) },
        )
        val bad = mutableListOf<String>()
        for ((label, call) in calls) {
            val before = seen.size
            runCatching { call() }
            if (seen.size == before) { bad += "$label: sent nothing"; continue }
            val path = seen.last().url.encodedPath
            if (enc !in path || path.contains("/../") || path.contains("?") || seen.last().url.fragment.isNotEmpty()) {
                bad += "$label: $path"
            }
        }
        // The second-position segments: a member role, a work id, a rename target's source.
        runCatching { client.dropMember("p", evil) }
        if (enc !in seen.last().url.encodedPath) bad += "dropMember role: ${seen.last().url.encodedPath}"
        runCatching { client.postWorkEvents("d", evil, emptyList()) }
        if (enc !in seen.last().url.encodedPath) bad += "postWorkEvents workId: ${seen.last().url.encodedPath}"
        runCatching { client.postWorkEvents(evil, "w", emptyList()) }
        if (enc !in seen.last().url.encodedPath) bad += "postWorkEvents deviceId: ${seen.last().url.encodedPath}"
        assertTrue(bad.isEmpty(), "unencoded path segments:\n" + bad.joinToString("\n"))
    }

    /**
     * Percent-encoding cannot save a segment that IS a dot segment: `..` and `.`
     * are legal characters, and a URL parser resolves them before routing. Such a
     * name is never a real one, so it never leaves the device.
     */
    @Test
    fun aDotSegmentNameIsRefusedBeforeTheWire() = runTest {
        for (n in listOf("..", ".", "")) {
            val before = seen.size
            assertFailsWith<HuginnClient.HuginnException> { client.killSession(n) }
            assertEquals(before, seen.size, "'$n' reached the wire")
        }
    }
}
