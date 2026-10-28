package cz.tadeasf.clipvault.sync

import cz.tadeasf.clipvault.db.ClipDatabase
import cz.tadeasf.clipvault.detect.SensitiveCategory
import cz.tadeasf.clipvault.sync.db.SyncDatabase
import cz.tadeasf.clipvault.sync.wire.DEFAULT_MAX_PAYLOAD
import cz.tadeasf.clipvault.sync.wire.ClipCreated
import cz.tadeasf.clipvault.sync.wire.Message
import cz.tadeasf.clipvault.sync.wire.SyncEvent

/** Per-device sync settings. [sensitiveOptIn] is what this device accepts from peers; it is sent in Hello. */
data class SyncConfig(
    val deviceId: String,
    /** Own clips created before this are never sent (protocol.md section 10). */
    val syncEnabledAt: Long = 0,
    val sensitiveOptIn: Set<SensitiveCategory> = emptySet(),
    val maxPayloadBytes: Long = DEFAULT_MAX_PAYLOAD,
    /** Excluded source apps by package/window title; applies to own clips. */
    val isExcludedSource: (String?) -> Boolean = { false },
    val pageSize: Long = 100,
)

/**
 * One device's side of sync: its log, its applier and its open sessions. Transport-agnostic: a session
 * gets messages from [SyncSession.receive] and hands messages to the transmit function it was opened
 * with; the secure connection (or a test bus) does the envelope encoding.
 * Not thread-safe; step 7 drives each node from one single-threaded dispatcher.
 */
class SyncEngine(private val db: ClipDatabase, private val syncDb: SyncDatabase, config: SyncConfig) {
    /** A change (e.g. a new opt-in) takes effect at the next connect. */
    var config: SyncConfig = config

    internal val log = SyncLog(db, syncDb) { this.config }
    val applier = SyncApplier(db, syncDb)
    private val sessions = mutableListOf<SyncSession>()

    /**
     * Opens a session over a fresh connection and sends Hello. [expectedPeerDeviceId]: the id the
     * authenticated identity must claim (design.md 5.3.1); null only on the unauthenticated test bus.
     */
    fun connect(expectedPeerDeviceId: String? = null, transmit: (Message) -> Unit): SyncSession =
        SyncSession(this, transmit, expectedPeerDeviceId).also {
        sessions += it
        it.start()
    }

    /** Called with the row id of every clip a peer created or bumped here (the agent queues them for CopyQ). */
    var onRemoteClip: ((clipRowId: String) -> Unit)? = null

    internal fun applied(event: SyncEvent, seq: Long?) {
        if (seq == null || event !is ClipCreated) return
        db.clipMemberQueries.selectById(event.clipId).executeAsOneOrNull()?.let { onRemoteClip?.invoke(it.clipId) }
    }

    /** (member id, peer id) for each member of a clip row that came from a peer: where to fetch it from. */
    internal fun originsOf(clipRowId: String): List<Pair<String, String>> =
        db.clipMemberQueries.forClip(clipRowId).executeAsList().mapNotNull { m ->
            syncDb.syncQueries.origin(m.seq).executeAsOneOrNull()?.let { m.id to it }
        }

    /** Call after every local change and every applied event: live peers get what is new. */
    fun changed() = sessions.toList().forEach { it.pushNew() }

    internal fun detach(session: SyncSession) {
        sessions -= session
    }

    internal fun cursor(peerId: String): Pair<Long, Set<SensitiveCategory>> =
        syncDb.syncQueries.cursor(peerId).executeAsOneOrNull()
            ?.let { it.lastSeqReceived to categoriesOf(it.optIns.split(',').filter(String::isNotEmpty).toSet()) }
            ?: (0L to emptySet())

    internal fun advance(peerId: String, seq: Long) = syncDb.syncQueries.run {
        ensure(peerId)
        advance(seq, peerId)
    }

    internal fun rememberOptIns(peerId: String, optIns: Set<SensitiveCategory>) = syncDb.syncQueries.run {
        ensure(peerId)
        setOptIns(optIns.map { it.name }.sorted().joinToString(","), peerId)
    }
}

/** Category names on the wire; a name this version doesn't know is UNKNOWN. */
internal fun categoriesOf(names: Set<String>): Set<SensitiveCategory> =
    names.map { name -> SensitiveCategory.entries.find { it.name == name } ?: SensitiveCategory.UNKNOWN }.toSet()

internal fun Set<SensitiveCategory>.names(): Set<String> = mapTo(mutableSetOf()) { it.name }
