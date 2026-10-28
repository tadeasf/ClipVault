package cz.tadeasf.clipvault.agent

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
}

/**
 * `clipvault-agent run`. Never reads the clipboard on start (so a restart can't double-ingest);
 * captures only what CopyQ, or the GNOME extension fallback, hands over afterwards.
 */
suspend fun runDaemon(core: AgentCore, copyq: CopyQ, paths: AgentPaths, gnome: GnomeFallback?): Nothing = coroutineScope {
    paths.prepare()
    core.onSensitive = { hash -> launch { scrubNewest(core, copyq, hash) } }
    val registered = gnome?.takeIf { !copyq.commandInstalled() }?.start { launch { core.ingest(it) } } == true
    serve(paths.socket, this) { handle(core, copyq, it, registered) }
    launch {
        while (isActive) {
            core.flushOutbox()
            core.applyRetention()
            delay(HOUSEKEEPING_MILLIS)
        }
    }
    println("clipvault-agent running; capture: ${captureModeOf(copyq, registered).label}")
    awaitCancellation()
}

private suspend fun handle(core: AgentCore, copyq: CopyQ, request: AgentRequest, gnomeRegistered: Boolean): String =
    when (request.command) {
        Commands.INGEST -> core.ingest(request.toIngest()).reply(core.state.scrubSensitive)
        Commands.STATUS -> core.statusReport("running", captureModeOf(copyq, gnomeRegistered), copyq)
        Commands.PUSH -> pushReport(core, request.payload.decodeToString())
        Commands.PING -> "pong"
        else -> "error: unknown command ${request.command}"
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
