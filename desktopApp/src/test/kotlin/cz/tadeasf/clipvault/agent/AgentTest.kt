package cz.tadeasf.clipvault.agent

import cz.tadeasf.clipvault.storage.JvmDriverFactory
import cz.tadeasf.clipvault.storage.clipDatabase
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class AgentTest {
    private val dir: File = Files.createTempDirectory("cv").toFile()
    private val fake = FakeCopyQ(File(dir, "bin").apply { mkdirs() })
    private var time = 1_000_000L
    private val db = clipDatabase(JvmDriverFactory(File(dir, "history.db")).open(Random.nextBytes(32)))
    private val core = AgentCore(db, fake.copyq(), { time }, Dispatchers.Unconfined)

    private suspend fun ingest(text: String, title: String? = null) = core.ingest(IngestRequest(text.encodeToByteArray(), title)).outcome

    private suspend fun storedCount() = core.repository.counts().first().total

    // Desktop -> store

    @Test
    fun storesTextAndRecordsTheSource() = runBlocking {
        assertEquals(Outcome.STORED, ingest("hello from firefox", "Mozilla Firefox"))
        assertEquals(1, core.state.count(Outcome.STORED))
        assertEquals("Mozilla Firefox", core.state.lastIngest()?.third)
        assertEquals(1, storedCount())
    }

    @Test
    fun sixDigitCodeIsStoredHiddenAndCountedAsSensitive() = runBlocking {
        assertEquals(Outcome.STORED_SENSITIVE, ingest("483920"))
        assertEquals(1, core.state.count(Outcome.STORED_SENSITIVE))
        assertEquals(1, core.repository.counts().first().sensitive)
    }

    @Test
    fun passwordManagerWindowIsNeverStored() = runBlocking {
        assertEquals(Outcome.SENSITIVE_SOURCE, ingest("hunter2", "Passwords.kdbx - KeePassXC"))
        assertEquals(0, storedCount())
    }

    @Test
    fun excludedWindowIsNeverStored() = runBlocking {
        core.state.excludedTitles = setOf("signal")
        assertEquals(Outcome.EXCLUDED, ingest("private chat", "Signal - Mom"))
        assertEquals(Outcome.STORED, ingest("public", "Firefox"))
        assertEquals(1, storedCount())
    }

    @Test
    fun duplicateEmptyAndNonText() = runBlocking {
        assertEquals(Outcome.STORED, ingest("same"))
        assertEquals(Outcome.DUPLICATE, ingest("same"))
        assertEquals(Outcome.BLANK, ingest(""))
        assertEquals(Outcome.BLANK, ingest("  \n "))
        assertEquals(Outcome.NON_TEXT, core.ingest(IngestRequest(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x41))).outcome)
        assertEquals(Outcome.NON_TEXT, core.ingest(IngestRequest(ByteArray(0), nonText = true)).outcome)
        assertEquals(1, storedCount())
        assertEquals(2, core.state.count(Outcome.NON_TEXT))
    }

    // Store -> CopyQ

    @Test
    fun pushWritesIntoCopyqClipboardTabOverStdinWithoutTouchingTheClipboard() = runBlocking {
        core.pushTest("hello")
        assertEquals(listOf("tab &clipboard add -"), fake.writes())
        assertEquals(listOf("hello"), fake.stdins())
        assertEquals(emptyList(), core.state.outbox())
    }

    @Test
    fun promoteAlsoSetsTheClipboardAndItsEchoIsDropped() = runBlocking {
        core.state.promoteToClipboard = true
        core.pushTest("from phone")
        assertEquals(listOf("tab &clipboard add -", "copy text/plain -"), fake.writes())
        assertEquals(Outcome.ECHO, ingest("from phone"))
        assertEquals(1, storedCount())
        time += AgentCore.ECHO_WINDOW_MILLIS + 1
        assertEquals(Outcome.DUPLICATE, ingest("from phone")) // a later real copy is a normal re-copy
    }

    @Test
    fun copyqDownQueuesAndFlushesLater() = runBlocking {
        fake.running = false
        assertEquals("queued: CopyQ not reachable, 1 waiting", pushReport(core, "while away"))
        assertEquals(emptyList(), fake.writes())
        fake.running = true
        assertEquals(Flush(written = 1, skipped = 0, backlog = 0, copyqReachable = true), core.flushOutbox())
        assertEquals(listOf("while away"), fake.stdins())
    }

    @Test
    fun sensitiveAndExcludedClipsNeverReachCopyq() = runBlocking {
        assertEquals("not written to CopyQ: sensitive or excluded", pushReport(core, "483920"))
        assertEquals(emptyList(), fake.writes())
        assertEquals(emptyList(), core.state.outbox())
    }

    @Test
    fun configuredTabWins() = runBlocking {
        core.state.copyqTab = "Phone"
        core.pushTest("x")
        assertEquals(listOf("tab Phone add -"), fake.writes())
    }

    @Test
    fun withoutCopyqTheAgentIsSyncOnly() = runBlocking {
        val missing = CopyQ(File(dir, "nope").absolutePath)
        assertEquals(CaptureMode.NONE_NO_COPYQ, captureModeOf(missing, gnomeRegistered = false))
        val report = AgentCore(db, missing, { time }, Dispatchers.Unconfined).statusReport("running", CaptureMode.NONE_NO_COPYQ, missing)
        assertContains(report, "copyq: not installed")
    }

    @Test
    fun statusReportsEncryptionAndBacklog() = runBlocking {
        ingest("one")
        val report = core.statusReport("running", captureModeOf(fake.copyq(), false), fake.copyq())
        assertContains(report, "copyq: tabs unencrypted")
        assertContains(report, "copyq outbox: 0 waiting")
        assertContains(report, "stored=1")
        assertContains(report, "capture: none: run `clipvault-agent install-copyq`")
    }

    // Daemon

    @Test
    fun daemonServesTheSocketAndNeverIngestsOnStart() = runBlocking {
        val paths = AgentPaths(File(dir, "data"), File(dir, "run"))
        repeat(2) { round ->
            val daemon = launch(Dispatchers.IO) { runDaemon(core, fake.copyq(), paths, gnome = null) }
            delay(500)
            assertEquals("pong", sendToDaemon(paths.socket, AgentRequest(Commands.PING, emptyMap(), ByteArray(0))))
            assertEquals(2L * round, storedCount()) // a restart adds nothing on its own (each round stores 2)
            val answer = sendToDaemon(paths.socket, AgentRequest(Commands.INGEST, mapOf("title" to "Ünïcode window"), "via socket $round".encodeToByteArray()))
            assertEquals("stored", answer)
            val otp = sendToDaemon(paths.socket, AgentRequest(Commands.INGEST, mapOf("title" to "Ünïcode window"), "48392$round".encodeToByteArray()))
            assertEquals("stored_sensitive scrub", otp) // tells the CopyQ command to ignore() it
            assertContains(sendToDaemon(paths.socket, AgentRequest(Commands.STATUS, emptyMap(), ByteArray(0)))!!, "daemon: running")
            daemon.cancelAndJoin()
        }
        assertEquals("Ünïcode window", core.state.lastIngest()?.third)
        assertEquals(null, sendToDaemon(paths.socket, AgentRequest(Commands.PING, emptyMap(), ByteArray(0))))
    }

    @Test
    fun requestCodecRoundTrips() {
        val request = AgentRequest("INGEST", mapOf("title" to "a b=c ü", "nonText" to "1"), byteArrayOf(0, 10, -1))
        val decoded = AgentRequest.decode(request.encode())
        assertEquals(request.command, decoded.command)
        assertEquals(request.params, decoded.params)
        assertTrue(request.payload.contentEquals(decoded.payload))
    }

    @Test
    fun installSendsTheScriptOnStdin() {
        assertEquals(null, fake.copyq().installCommand("/opt/clipvault-agent/bin/clipvault-agent"))
        assertContains(fake.stdins().last(), "setCommands(cmds)") // not argv: CopyQ would unescape it
    }

    @Test
    fun installScriptOnlyReplacesItsOwnCommand() {
        val script = installScript("/opt/clipvault agent/bin/clipvault-agent")
        assertContains(script, """filter(function (c) { return c.name != name; })""")
        assertContains(script, """"ClipVault ingest"""")
        assertContains(script, """\"/opt/clipvault agent/bin/clipvault-agent\"""") // path quoted inside the command string
        assertEquals("\"a\\\"b\\\\c\\n\"", jsString("a\"b\\c\n"))
    }

    // Scrub

    @Test
    fun sensitiveClipsAreFlaggedForScrubbingUnlessDisabled() = runBlocking {
        val scrubbed = mutableListOf<String>()
        core.onSensitive = { scrubbed += it }
        val otp = core.ingest(IngestRequest("483920".encodeToByteArray()))
        assertEquals("stored_sensitive scrub", otp.reply(core.state.scrubSensitive))
        val manager = core.ingest(IngestRequest("hunter2".encodeToByteArray(), "Vault - KeePassXC"))
        assertEquals("sensitive_source scrub", manager.reply(core.state.scrubSensitive))
        assertEquals("stored", core.ingest(IngestRequest("plain".encodeToByteArray())).reply(true))
        assertEquals(2, scrubbed.size)
        assertEquals(cz.tadeasf.clipvault.security.sha256Hex("483920"), scrubbed.first())

        core.state.scrubSensitive = false
        assertEquals("stored_sensitive", core.ingest(IngestRequest("771204".encodeToByteArray())).reply(core.state.scrubSensitive))
        assertEquals(2, scrubbed.size)
    }

    @Test
    fun removeNewestComparesHashesInsideCopyq() {
        assertEquals(false, fake.copyq().removeNewestIfHash("&clipboard", "abc123"))
        val script = fake.stdins().last()
        assertContains(script, "sha256sum(read(0))")
        assertContains(script, "\"abc123\"")
        assertContains(script, "remove(0)")
    }

    @Test
    fun installedCommandGoesFirstAndHonoursScrub() {
        val script = installScript("/opt/clipvault-agent/bin/clipvault-agent")
        assertContains(script, "cmds.unshift(")
        assertContains(script, "indexOf(\\\"scrub\\\") >= 0) ignore();")
    }

    @Test
    fun sessionSwitchIsPassedToEveryCall() {
        assertEquals(listOf("copyq", "-s", "cvtest", "eval", "-"), CopyQ("copyq", session = "cvtest").command(listOf("eval", "-")))
        assertEquals(listOf("copyq", "exit"), CopyQ("copyq").command(listOf("exit")))
    }

}
