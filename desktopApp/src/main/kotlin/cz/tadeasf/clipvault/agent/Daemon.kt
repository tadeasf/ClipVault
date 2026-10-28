package cz.tadeasf.clipvault.agent

import cz.tadeasf.clipvault.sync.db.SyncDatabase
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Socket commands the daemon answers. */
object Commands {
    const val INGEST = "INGEST"
    const val STATUS = "STATUS"
    const val PUSH = "PUSH"
    const val PING = "PING"
    const val PEERS = "PEERS"
    const val UNPAIR = "UNPAIR"
}

/**
 * `clipvault-agent run`. Never reads the clipboard on start (so a restart can't double-ingest);
 * captures only what CopyQ, or the GNOME extension fallback, hands over afterwards.
 */
suspend fun runDaemon(core: AgentCore, copyq: CopyQ, paths: AgentPaths, gnome: GnomeFallback?, sync: SyncDatabase? = null): Nothing = coroutineScope {
    paths.prepare()
    core.onSensitive = { hash -> launch { scrubNewest(core, copyq, hash) } }
    val host = sync?.let { SyncHost(core.db, it, core, this) }
    val registered = gnome?.takeIf { !copyq.commandInstalled() }?.start { launch { host.write { core.ingest(it) } } } == true
    serve(paths.socket, this) { handle(core, copyq, it, registered, host) }
    launch {
        while (isActive) {
            host?.startIfReady() // `pair` may have enabled sync and paired a device meanwhile
            host?.refreshConfig()
            core.flushOutbox()
            core.applyRetention()
            delay(HOUSEKEEPING_MILLIS)
        }
    }
    println("clipvault-agent running; capture: ${captureModeOf(copyq, registered).label}")
    awaitCancellation()
}

private suspend fun handle(core: AgentCore, copyq: CopyQ, request: AgentRequest, gnomeRegistered: Boolean, host: SyncHost?): String =
    when (request.command) {
        Commands.INGEST -> host.write { core.ingest(request.toIngest()) }.reply(core.state.scrubSensitive)
        Commands.STATUS -> core.statusReport("running", captureModeOf(copyq, gnomeRegistered), copyq) + syncReport(host)
        Commands.PUSH -> host.write { pushReport(core, request.payload.decodeToString()) }
        Commands.PING -> "pong"
        Commands.PEERS -> host?.let { peerLines(it.peers.peers(), it.status()).joinToString("\n") }.orEmpty().ifEmpty { "no paired devices" }
        Commands.UNPAIR -> unpairReply(host, request.payload.decodeToString())
        else -> "error: unknown command ${request.command}"
    }

private suspend fun <T> SyncHost?.write(block: suspend () -> T): T = this?.write(block) ?: block()

private suspend fun syncReport(host: SyncHost?): String = buildString {
    if (host == null || host.peers.identity() == null) {
        appendLine("sync: off (`clipvault-agent pair` turns it on)")
        return@buildString
    }
    appendLine("sync: ${if (host.running) "on, listening on port ${host.port}" else "on, no paired device yet"}")
    peerLines(host.peers.peers(), host.status()).forEach(::appendLine)
}

suspend fun unpairReply(host: SyncHost?, nameOrId: String): String {
    host ?: return "error: sync is not set up"
    val matches = host.find(nameOrId)
    return when (matches.size) {
        0 -> "error: no paired device \"$nameOrId\" (see `clipvault-agent peers`)"
        1 -> matches.single().let { host.unpair(it); "unpaired ${it.name} [${it.deviceId.take(8)}]" }
        else -> "error: \"$nameOrId\" matches ${matches.size} devices; use a device id prefix: " + matches.joinToString { it.deviceId.take(8) }
    }
}

fun AgentRequest.toIngest() = IngestRequest(payload, params["title"]?.ifEmpty { null }, nonText = params["nonText"] == "1")

suspend fun pushReport(core: AgentCore, text: String): String {
    val flush = core.pushTest(text)
    return when {
        !flush.copyqReachable -> "queued: CopyQ not reachable, ${flush.backlog} waiting"
        flush.written > 0 -> "pushed to CopyQ"
        else -> "not written to CopyQ: sensitive or excluded"
    }
}

/**
 * Belt and braces next to the command's ignore(): on the GNOME D-Bus path CopyQ stores the clip itself,
 * a moment after the change. Retries a few times; only ever touches the newest row, and only on a hash match.
 */
private suspend fun scrubNewest(core: AgentCore, copyq: CopyQ, hash: String) {
    val tab = copyq.clipboardTab() ?: return
    for (wait in SCRUB_DELAYS_MILLIS) {
        delay(wait)
        if (copyq.removeNewestIfHash(tab, hash)) return
    }
}

private val SCRUB_DELAYS_MILLIS = listOf(1_000L, 2_000L, 5_000L)
private const val HOUSEKEEPING_MILLIS = 5_000L
