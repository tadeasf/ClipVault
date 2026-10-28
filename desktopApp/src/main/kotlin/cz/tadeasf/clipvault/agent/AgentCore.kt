package cz.tadeasf.clipvault.agent

import cz.tadeasf.clipvault.db.ClipDatabase
import cz.tadeasf.clipvault.history.ClipIngest
import cz.tadeasf.clipvault.history.IngestResult
import cz.tadeasf.clipvault.history.SkipReason
import cz.tadeasf.clipvault.history.SqlClipRepository
import cz.tadeasf.clipvault.model.ClipDraft
import cz.tadeasf.clipvault.model.ClipHints
import cz.tadeasf.clipvault.security.sha256Hex
import cz.tadeasf.clipvault.settings.SqlSettingsRepository
import cz.tadeasf.clipvault.settings.deviceId
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

/** One clipboard item handed over by CopyQ (or the GNOME extension). */
class IngestRequest(val bytes: ByteArray, val title: String? = null, val nonText: Boolean = false)

/** What one ingest did, and whether the clip was sensitive (so CopyQ should drop it). */
data class Ingested(val outcome: Outcome, val sensitive: Boolean) {
    /** The answer the CopyQ command parses: "scrub" makes it call ignore(). */
    fun reply(scrub: Boolean): String = outcome.name.lowercase() + if (scrub && sensitive) " scrub" else ""
}

/** Result of one CopyQ outbox flush. */
data class Flush(val written: Int, val skipped: Int, val backlog: Int, val copyqReachable: Boolean)

/**
 * Everything the agent does to its store, in one place: the daemon serves it over the socket, and CLI
 * commands use it directly when no daemon answers.
 */
class AgentCore(
    db: ClipDatabase,
    private val copyq: CopyQ,
    private val now: () -> Long = System::currentTimeMillis,
    io: CoroutineContext = Dispatchers.IO,
) {
    val state = AgentState(db)
    val settings = SqlSettingsRepository(db, io)
    val repository = SqlClipRepository(db, deviceId(db), now, io)
    private val ingest = ClipIngest(repository) { state.isExcluded(it.sourceWindowTitle) }

    /** Content hashes this agent put on the live clipboard, with when; a re-capture of them is an echo. */
    private val promoted = ConcurrentHashMap<String, Long>()

    /** Called with the clip's SHA-256 when a sensitive clip should be removed from CopyQ (daemon only). */
    var onSensitive: ((String) -> Unit)? = null

    suspend fun ingest(request: IngestRequest): Ingested {
        val ingested = classify(request)
        state.record(ingested.outcome, request.title, now())
        if (ingested.sensitive && state.scrubSensitive) onSensitive?.invoke(sha256Hex(request.bytes.decodeToString()))
        return ingested
    }

    private suspend fun classify(request: IngestRequest): Ingested {
        if (request.nonText) return Ingested(Outcome.NON_TEXT, sensitive = false)
        val text = runCatching { request.bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
            ?: return Ingested(Outcome.NON_TEXT, sensitive = false)
        if (isEcho(text)) return Ingested(Outcome.ECHO, sensitive = false)
        val result = ingest.ingest(text, ClipHints(sourceWindowTitle = request.title))
        applyRetention()
        val sensitive = (result as? IngestResult.Stored)?.sensitive == true ||
            (result as? IngestResult.Skipped)?.reason == SkipReason.SENSITIVE_SOURCE
        return Ingested(result.toOutcome(), sensitive)
    }

    private fun isEcho(text: String): Boolean {
        val at = promoted[sha256Hex(text)] ?: return false
        return now() - at < ECHO_WINDOW_MILLIS
    }

    suspend fun applyRetention() = repository.applyRetention(settings.settings.value.retentionPolicy())

    /** Stores [text] like a phone-origin clip and sends it to CopyQ. */
    suspend fun pushTest(text: String): Flush {
        state.enqueue(repository.insert(ClipDraft(text)).id)
        return flushOutbox()
    }

    /** Writes queued clips into CopyQ. Sensitive and excluded clips are dropped, never written. */
    suspend fun flushOutbox(): Flush {
        val queue = state.outbox()
        if (queue.isEmpty()) return Flush(0, 0, 0, copyqReachable = true)
        if (!copyq.running()) return Flush(0, 0, queue.size, copyqReachable = false)
        val tab = state.copyqTab ?: copyq.clipboardTab() ?: DEFAULT_TAB
        var written = 0
        var skipped = 0
        for (id in queue) {
            val clip = repository.get(id)
            if (clip == null || clip.deleted || clip.sensitive || state.isExcluded(clip.sourcePackage)) {
                skipped++
            } else if (write(tab, clip.content)) {
                written++
            } else {
                return Flush(written, skipped, state.outbox().size, copyqReachable = false)
            }
            state.dequeue(id)
        }
        return Flush(written, skipped, state.outbox().size, copyqReachable = true)
    }

    private fun write(tab: String, text: String): Boolean {
        if (!copyq.add(tab, text)) return false
        if (state.promoteToClipboard) {
            promoted[sha256Hex(text)] = now()
            copyq.copy(text)
        }
        return true
    }

    companion object {
        const val ECHO_WINDOW_MILLIS = 30_000L
        const val DEFAULT_TAB = "&clipboard"
    }
}

private fun IngestResult.toOutcome(): Outcome = when (this) {
    is IngestResult.Stored -> when {
        duplicate -> Outcome.DUPLICATE
        sensitive -> Outcome.STORED_SENSITIVE
        else -> Outcome.STORED
    }
    is IngestResult.Skipped -> when (reason) {
        SkipReason.BLANK -> Outcome.BLANK
        SkipReason.SENSITIVE_SOURCE -> Outcome.SENSITIVE_SOURCE
        SkipReason.EXCLUDED_SOURCE -> Outcome.EXCLUDED
    }
}
