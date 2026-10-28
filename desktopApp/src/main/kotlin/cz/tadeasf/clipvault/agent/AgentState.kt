package cz.tadeasf.clipvault.agent

import cz.tadeasf.clipvault.db.ClipDatabase

/** What one ingest did; also the name of its counter in `status`. */
enum class Outcome { STORED, STORED_SENSITIVE, DUPLICATE, SENSITIVE_SOURCE, EXCLUDED, BLANK, NON_TEXT, ECHO }

/**
 * Agent config, counters and the CopyQ outbox, kept in the encrypted store's `Setting` table so they
 * survive restarts. Only the daemon (or a fallback `ingest` while it is down) writes them.
 */
class AgentState(private val db: ClipDatabase) {
    private val settings get() = db.settingQueries

    private fun get(key: String): String? = settings.get(key).executeAsOneOrNull()

    private fun put(key: String, value: String) {
        settings.put(key, value)
    }

    var promoteToClipboard: Boolean
        get() = get(PROMOTE) == "true"
        set(value) = put(PROMOTE, value.toString())

    /** Remove sensitive clips from CopyQ as soon as they are classified (default on). */
    var scrubSensitive: Boolean
        get() = get(SCRUB) != "false"
        set(value) = put(SCRUB, value.toString())

    /** Null means "CopyQ's own clipboard tab" (`copyq config clipboard_tab`). */
    var copyqTab: String?
        get() = get(TAB)?.ifEmpty { null }
        set(value) = put(TAB, value.orEmpty())

    var excludedTitles: Set<String>
        get() = get(EXCLUDE)?.split('\n')?.filter(String::isNotBlank)?.toSet() ?: emptySet()
        set(value) = put(EXCLUDE, value.sorted().joinToString("\n"))

    fun isExcluded(source: String?): Boolean =
        source != null && excludedTitles.any { source.contains(it, ignoreCase = true) }

    fun count(outcome: Outcome): Long = get("$COUNTER${outcome.name}")?.toLongOrNull() ?: 0

    fun record(outcome: Outcome, source: String?, at: Long) = db.transaction {
        put("$COUNTER${outcome.name}", (count(outcome) + 1).toString())
        put(LAST, "$at\t${outcome.name}\t${source.orEmpty()}")
    }

    /** "time<TAB>outcome<TAB>source" of the most recent ingest. */
    fun lastIngest(): Triple<Long, String, String>? = get(LAST)?.split('\t')?.takeIf { it.size == 3 }
        ?.let { (at, outcome, source) -> Triple(at.toLong(), outcome, source) }

    fun outbox(): List<String> = get(OUTBOX)?.split(',')?.filter(String::isNotEmpty) ?: emptyList()

    fun enqueue(clipId: String) = db.transaction { put(OUTBOX, (outbox() - clipId + clipId).joinToString(",")) }

    fun dequeue(clipId: String) = db.transaction { put(OUTBOX, (outbox() - clipId).joinToString(",")) }

    private companion object {
        const val PROMOTE = "agent.promoteToClipboard"
        const val SCRUB = "agent.scrubCopyqSensitive"
        const val TAB = "agent.copyqTab"
        const val EXCLUDE = "agent.excludedTitles"
        const val OUTBOX = "agent.copyqOutbox"
        const val COUNTER = "agent.count."
        const val LAST = "agent.lastIngest"
    }
}
