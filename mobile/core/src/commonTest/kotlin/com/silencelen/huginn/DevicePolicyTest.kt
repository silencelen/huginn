package com.silencelen.huginn

import com.silencelen.huginn.data.DeviceWork
import com.silencelen.huginn.device.DevicePolicy
import com.silencelen.huginn.device.DevicePolicyTable
import com.silencelen.huginn.device.DeviceScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fence around somebody's personal computer.
 *
 * Every assertion here is the answer to "what does a request from the homelab
 * actually become on this machine", and the failure mode of getting it wrong is
 * not a wrong pixel.
 */
class DevicePolicyTest {

    private fun work(mode: String = "ask", model: String? = null, resume: String? = null) =
        DeviceWork(id = "w1", chatId = "c1", prompt = "look", mode = mode, model = model, resumeSessionId = resume)

    @Test
    fun anUnrecognisedScopeIsTheFloor() {
        // The floor is generate, the exclusive rung: a junk scope can run nothing
        // a claude device runs, and no claude engine will ever serve generate. A
        // typo is a dead row, not a privilege escalation in either direction.
        assertEquals(DeviceScope.GENERATE, DevicePolicy.parse("root"))
        assertEquals(DeviceScope.GENERATE, DevicePolicy.parse(null))
        assertEquals(DeviceScope.GENERATE, DevicePolicy.parse(""))
        assertEquals(DeviceScope.OWN, DevicePolicy.parse("OWN"))
        assertEquals(DeviceScope.WORK, DevicePolicy.parse(" work "))
        assertEquals(DeviceScope.GENERATE, DevicePolicy.parse("generate"))
    }

    @Test
    fun lockingDropsEverythingToRead() {
        assertEquals(DeviceScope.LOOK, DevicePolicy.effective(DeviceScope.OWN, locked = true))
        assertEquals(DeviceScope.LOOK, DevicePolicy.effective(DeviceScope.WORK, locked = true))
        assertEquals(DeviceScope.OWN, DevicePolicy.effective(DeviceScope.OWN, locked = false))
    }

    @Test
    fun actNeedsMoreThanLook() {
        assertFalse(DevicePolicy.allows(DeviceScope.LOOK, "act"))
        assertTrue(DevicePolicy.allows(DeviceScope.WORK, "act"))
        assertTrue(DevicePolicy.allows(DeviceScope.OWN, "act"))
        assertTrue(DevicePolicy.allows(DeviceScope.LOOK, "ask"), "reading is what look means")
    }

    @Test
    fun aRefusalSaysWhichActionWouldFixIt() {
        // "Unlock the machine" and "change what it is enrolled to do" are different
        // actions; one message for both would leave the reader guessing.
        val locked = DevicePolicy.refusal(DeviceScope.OWN, locked = true, mode = "act")
        assertTrue(locked!!.contains("locked"), locked)

        val narrow = DevicePolicy.refusal(DeviceScope.LOOK, locked = false, mode = "act")
        assertTrue(narrow!!.contains("look"), narrow)
        assertFalse(narrow.contains("locked"))

        assertNull(DevicePolicy.refusal(DeviceScope.WORK, locked = false, mode = "act"))
        assertNull(DevicePolicy.refusal(DeviceScope.LOOK, locked = false, mode = "ask"))
    }

    @Test
    fun aLookRunNeverGetsAShell() {
        val argv = DevicePolicy.argvFor(work("ask"), DeviceScope.LOOK, locked = false, root = null)
        val allowed = argv[argv.indexOf("--allowedTools") + 1]
        assertFalse(allowed.contains("Bash"), allowed)
        assertFalse(allowed.contains("Write"), allowed)
        // DENIED, not merely un-granted: Claude Code's safe-Bash classification is
        // content-dependent, so an un-granted Bash is a fence that works and then
        // does not. Deny is deterministic.
        val denied = argv[argv.indexOf("--disallowedTools") + 1]
        assertTrue(denied.contains("Bash"), denied)
    }

    @Test
    fun anActRequestOnALockedMachineIsDowngradedNotHonoured() {
        // The load-bearing test. A remote `act` arriving while the screen is locked
        // must come out the far side as a read-only run, with the shell denied.
        val argv = DevicePolicy.argvFor(work("act"), DeviceScope.OWN, locked = true, root = null)
        val allowed = argv[argv.indexOf("--allowedTools") + 1]
        assertFalse(allowed.contains("Bash"), "a locked machine granted Bash: $allowed")
        assertTrue(argv.contains("--disallowedTools"))
    }

    @Test
    fun anActRunOnAnUnlockedWorkMachineGetsWhatItNeeds() {
        val argv = DevicePolicy.argvFor(work("act"), DeviceScope.WORK, locked = false, root = "/src")
        val allowed = argv[argv.indexOf("--allowedTools") + 1]
        assertTrue(allowed.contains("Bash"))
        assertTrue(allowed.contains("Write"))
        assertFalse(argv.contains("--disallowedTools"), "nothing to deny when act is granted")
    }

    @Test
    fun theStreamFlagsAreAlwaysThere() {
        // The daemon parses stream-json and nothing else; a runner that forgot
        // these would return a wall of prose the transcript could not read.
        val argv = DevicePolicy.argvFor(work(), DeviceScope.LOOK, locked = false, root = null)
        assertTrue(argv.containsAll(listOf("-p", "--output-format", "stream-json", "--verbose")))
    }

    @Test
    fun modelEffortAndResumeRideAlongOnlyWhenSet() {
        val bare = DevicePolicy.argvFor(work(), DeviceScope.WORK, locked = false, root = null)
        assertFalse(bare.contains("--model"))
        assertFalse(bare.contains("--resume"))

        // A REAL session id. This fixture used to be "sess-1", which now fails
        // validation — the test was right to break, because the shape of a session
        // id is the thing being enforced.
        val sid = "4f1c2b8a-9d3e-4a71-8b52-6c0f7e1a2d94"
        val full = DevicePolicy.argvFor(
            work(mode = "act", model = "opus", resume = sid),
            DeviceScope.WORK, locked = false, root = null,
        )
        assertEquals("opus", full[full.indexOf("--model") + 1])
        assertEquals(sid, full[full.indexOf("--resume") + 1])
    }

    @Test
    fun aResumeIdThatIsReallyAFlagNeverReachesTheArgv() {
        // `--resume` takes its value OPTIONALLY, so a string beginning with "--"
        // does not become the session id — it becomes the NEXT FLAG. The value
        // originates in an event the far end posted, and this machine builds its
        // own argv, so it validates rather than assuming somebody else did.
        val clean = DevicePolicy.argvFor(work(mode = "act"), DeviceScope.OWN, locked = false, root = null)
        for (hostile in listOf("--dangerously-skip-permissions", "--allowedTools Bash", "-p", "", "  ", "not-a-uuid")) {
            val argv = DevicePolicy.argvFor(
                work(mode = "act", resume = hostile),
                DeviceScope.OWN, locked = false, root = null,
            )
            assertFalse(argv.contains("--resume"), "a bogus resume id got through: $hostile")
            // Not "the value is absent" — `-p` is legitimately the first stream
            // flag, so that assertion would fail on correct behaviour. The real
            // property is that a rejected id adds NOTHING: the argv is identical
            // to the one built with no resume id at all.
            assertEquals(clean, argv, "a rejected resume id changed the argv: $hostile")
        }
    }

    @Test
    fun aModeNobodyDefinedIsRefusedRatherThanMappedToAScope() {
        // A Kotlin Map has no prototype, so the JS failure cannot happen here —
        // but mapping an unknown mode to "work" let anything unrecognised run on
        // every machine enrolled at work or own.
        for (mode in listOf("constructor", "toString", "hasOwnProperty", "", "sudo")) {
            for (scope in DeviceScope.entries) {
                assertFalse(DevicePolicy.allows(scope, mode), "$scope allowed mode '$mode'")
                assertNotNull(DevicePolicy.refusal(scope, locked = false, mode = mode),
                    "$scope did not refuse mode '$mode'")
            }
        }
    }

    @Test
    fun workStartsInItsRootAndOwnStartsAtHome() {
        assertEquals("/src", DevicePolicy.cwdFor(DeviceScope.WORK, false, "/src", "/home/me"))
        assertEquals("/home/me", DevicePolicy.cwdFor(DeviceScope.OWN, false, "/src", "/home/me"))
        assertEquals("/home/me", DevicePolicy.cwdFor(DeviceScope.WORK, false, null, "/home/me"))
        assertEquals("/home/me", DevicePolicy.cwdFor(DeviceScope.WORK, false, "  ", "/home/me"))
        // Locked drops to look, which has no root of its own.
        assertEquals("/home/me", DevicePolicy.cwdFor(DeviceScope.WORK, true, "/src", "/home/me"))
    }

    // ------------------------------------------------------- the generate rung

    @Test
    fun generateIsExclusiveInBothDirections() {
        // Rank ordering alone would let `own` satisfy generate — and this machine
        // would spawn claude to answer a local-model request, the silent engine
        // substitution the policy bans both ways.
        assertFalse(DevicePolicy.allows(DeviceScope.OWN, "generate"))
        assertFalse(DevicePolicy.allows(DeviceScope.WORK, "generate"))
        assertFalse(DevicePolicy.allows(DeviceScope.LOOK, "generate"))
        assertFalse(DevicePolicy.allows(DeviceScope.GENERATE, "ask"))
        assertFalse(DevicePolicy.allows(DeviceScope.GENERATE, "act"))
        assertTrue(DevicePolicy.allows(DeviceScope.GENERATE, "generate"))
    }

    @Test
    fun aLockedGenerateDeviceKeepsServingAndNeverBlamesTheLock() {
        // The lock stops unwatched mutation; a generate run mutates nothing, and
        // dropping to look would sideways-grant ask, a mode the row has no engine
        // for. When ask IS refused, the reason names the scope — unlocking would
        // not help, so the lock must not be blamed.
        assertEquals(DeviceScope.GENERATE, DevicePolicy.effective(DeviceScope.GENERATE, locked = true))
        assertNull(DevicePolicy.refusal(DeviceScope.GENERATE, locked = true, mode = "generate"))
        val ask = DevicePolicy.refusal(DeviceScope.GENERATE, locked = true, mode = "ask")
        assertNotNull(ask)
        assertTrue(ask.contains("generate"), ask)
        assertFalse(ask.contains("locked"), "unlocking would not help, so the lock is not blamed")
    }

    @Test
    fun aGenerateArgvShedsEverythingButTheRequest() {
        val argv = DevicePolicy.argvFor(
            DeviceWork(
                id = "w1", chatId = "c1", prompt = "p", mode = "generate",
                model = "local-x-q", effort = "high",
                resumeSessionId = "123e4567-e89b-12d3-a456-426614174000",
            ),
            DeviceScope.GENERATE, locked = false, root = "/src", persona = "P",
        )
        assertEquals(
            DevicePolicyTable.STREAM_FLAGS +
                listOf("--model", "local-x-q", "--resume", "123e4567-e89b-12d3-a456-426614174000"),
            argv,
        )
        assertFalse(argv.contains("--effort"), "a local model has no effort knob")
        assertFalse(argv.contains("--append-system-prompt"), "no context egress to a serving box")
        assertFalse(argv.contains("--allowedTools"), "no tool surface, no tool flags")
    }

    @Test
    fun theEngineFenceRefusesGenerateWithoutAnEngine() {
        // This runner only ever spawns claude, so DeviceRunner passes
        // hasEngine = false unconditionally; serving is the headless service's job.
        assertEquals(DevicePolicyTable.REFUSAL_ENGINE, DevicePolicy.engineRefusal("generate", hasEngine = false))
        assertNull(DevicePolicy.engineRefusal("generate", hasEngine = true))
        assertNull(DevicePolicy.engineRefusal("ask", hasEngine = false))
    }
}
