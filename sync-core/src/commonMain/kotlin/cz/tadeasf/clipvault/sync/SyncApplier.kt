package cz.tadeasf.clipvault.sync

import cz.tadeasf.clipvault.db.ClipDatabase
import cz.tadeasf.clipvault.history.ClipLog
import cz.tadeasf.clipvault.model.ContentType
import cz.tadeasf.clipvault.model.MetaField
import cz.tadeasf.clipvault.security.sha256Hex
import cz.tadeasf.clipvault.sync.db.SyncDatabase
import cz.tadeasf.clipvault.sync.wire.ClipCreated
import cz.tadeasf.clipvault.sync.wire.HARD_MAX_PAYLOAD
import cz.tadeasf.clipvault.sync.wire.MetaChanged
import cz.tadeasf.clipvault.sync.wire.SyncEvent

/** The receive side, protocol.md section 9. Idempotent and order-independent. */
class SyncApplier(private val db: ClipDatabase, private val syncDb: SyncDatabase) {
    private val clips get() = db.clipQueries
    private val members get() = db.clipMemberQueries
    private val meta get() = db.metaEventQueries
    private val log = ClipLog(db)

    /**
     * Stores [event] from [fromPeer] under a new local seq so it can be relayed, and returns that seq;
     * null when it changed nothing (a duplicate).
     */
    fun apply(event: SyncEvent, fromPeer: String): Long? {
        val seq = db.transactionWithResult {
            log.observe(event.lamport)
            when (event) {
                is ClipCreated -> applyClip(event)
                is MetaChanged -> applyMeta(event)
            }
        }
        // Separate database: a crash before this only means the event may be sent back once, a no-op there.
        seq?.let { syncDb.syncQueries.recordOrigin(it, fromPeer) }
        return seq
    }

    /** A fetched payload; stored only if it matches the clip's hash. */
    fun fillPayload(memberId: String, bytes: ByteArray): Boolean = db.transactionWithResult {
        val member = members.selectById(memberId).executeAsOneOrNull() ?: return@transactionWithResult false
        fill(member.clipId, bytes)
    }

    private fun fill(clipId: String, bytes: ByteArray): Boolean {
        if (bytes.size > HARD_MAX_PAYLOAD) return false
        val clip = clips.selectById(clipId).executeAsOneOrNull() ?: return false
        val content = bytes.decodeToString() // step 6: decrypt first
        if (clip.content.isNotEmpty() || clip.contentHash.isEmpty() || sha256Hex(content) != clip.contentHash) return false
        clips.fillContent(content, clipId, clip.contentHash)
        return true
    }

    private fun applyClip(event: ClipCreated): Long? {
        members.selectById(event.clipId).executeAsOneOrNull()?.let { known ->
            event.payload?.let { fill(known.clipId, it) }
            if (event.createdAt <= known.createdAt) return null
            val seq = log.nextSeq() // re-copy bump: relay it onward
            members.bump(event.createdAt, seq, known.id)
            log.refold(known.clipId)
            return seq
        }
        // A payload that doesn't match its hash is dropped; the row waits for a fetch like an over-cap one.
        val content = event.payload?.takeIf { it.size <= HARD_MAX_PAYLOAD }?.decodeToString()?.takeIf { sha256Hex(it) == event.contentHash }
        val categories = categoriesOf(event.categories)
        val seq = log.nextSeq()
        val live = event.contentHash.takeIf { it.isNotEmpty() }?.let { clips.findLiveByHash(it).executeAsOneOrNull() }
        val clipId = if (live != null) {
            val clip = clips.selectById(live).executeAsOne()
            if (event.sensitive && !(clip.sensitive && clip.categories.containsAll(categories))) {
                clips.setSensitivity(true, clip.categories + categories, live)
            }
            content?.let { clips.fillContent(it, live, event.contentHash) }
            live
        } else {
            val type = ContentType.entries.find { it.name == event.contentType } ?: ContentType.PLAIN
            clips.insert(
                event.clipId, event.deviceId, event.createdAt, type, event.contentHash, content ?: "",
                event.sensitive, event.lamport, seq, categories, null,
            )
            event.clipId
        }
        members.insert(event.clipId, clipId, event.deviceId, event.createdAt, event.lamport, seq)
        log.refold(clipId) // also folds MetaEvents that arrived before the clip
        return seq
    }

    private fun applyMeta(event: MetaChanged): Long? {
        val field = MetaField.entries.find { it.name == event.field } ?: return null
        if (meta.exists(event.clipId, field, event.value, event.lamport, event.deviceId).executeAsOne() > 0) return null
        val seq = log.nextSeq()
        meta.insert(seq, event.clipId, field, event.value, event.lamport, event.deviceId)
        members.selectById(event.clipId).executeAsOneOrNull()?.let { log.refold(it.clipId) }
        return seq
    }
}
