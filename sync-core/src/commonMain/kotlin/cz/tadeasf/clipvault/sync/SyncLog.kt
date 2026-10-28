package cz.tadeasf.clipvault.sync

import cz.tadeasf.clipvault.db.Clip
import cz.tadeasf.clipvault.db.ClipDatabase
import cz.tadeasf.clipvault.db.ClipMember
import cz.tadeasf.clipvault.detect.SensitiveCategory
import cz.tadeasf.clipvault.history.ClipLog
import cz.tadeasf.clipvault.sync.db.SyncDatabase
import cz.tadeasf.clipvault.sync.wire.ClipCreated
import cz.tadeasf.clipvault.sync.wire.Hello
import cz.tadeasf.clipvault.sync.wire.MetaChanged
import cz.tadeasf.clipvault.sync.wire.SyncEvent
import cz.tadeasf.clipvault.db.MetaEvent as StoredMeta

/** The send side: this device's log as events for one peer, with the filters of protocol.md section 10. */
internal class SyncLog(private val db: ClipDatabase, private val syncDb: SyncDatabase, private val config: () -> SyncConfig) {
    private val clips get() = db.clipQueries
    private val members get() = db.clipMemberQueries
    private val log = ClipLog(db)

    class Page(val events: List<SyncEvent>, val hasMore: Boolean, val throughSeq: Long)

    /** Events with seq > [since] for [peer], one page. [onlySensitive]: the opt-in re-scan. */
    fun page(since: Long, peer: Hello, onlySensitive: Set<SensitiveCategory>? = null): Page = db.transactionWithResult {
        val limit = config().pageSize
        // Each source's first limit+1 rows contain the merged first limit+1.
        val merged = (members.after(since, limit + 1).executeAsList().map { it.seq to it } +
            db.metaEventQueries.after(since, limit + 1).executeAsList().map { it.seq to it })
            .sortedBy { it.first }
        val scanned = merged.take(limit.toInt())
        Page(
            events = scanned.mapNotNull { (_, row) ->
                when (row) {
                    is ClipMember -> clipEvent(row, peer, onlySensitive)
                    is StoredMeta -> metaEvent(row, peer, onlySensitive)
                    else -> null
                }
            },
            hasMore = merged.size > limit,
            throughSeq = scanned.lastOrNull()?.first ?: since,
        )
    }

    /** FetchPayload: the content, if it is here and [peer] may have it. */
    fun payload(memberId: String, peer: Hello): ByteArray? {
        val member = members.selectById(memberId).executeAsOneOrNull() ?: return null
        val clip = clips.selectById(member.clipId).executeAsOneOrNull() ?: return null
        if (!allowed(clip, member, peer, null) || clip.content.isEmpty()) return null
        return clip.content.encodeToByteArray()
    }

    private fun cameFrom(seq: Long, peer: Hello) = syncDb.syncQueries.origin(seq).executeAsOneOrNull() == peer.deviceId

    private fun clipEvent(member: ClipMember, peer: Hello, onlySensitive: Set<SensitiveCategory>?): ClipCreated? {
        if (cameFrom(member.seq, peer) || log.flags(member.id).deleted) return null
        val clip = clips.selectById(member.clipId).executeAsOneOrNull() ?: return null
        if (!allowed(clip, member, peer, onlySensitive)) return null
        val bytes = clip.content.encodeToByteArray()
        val cap = minOf(config().maxPayloadBytes, peer.maxPayloadBytes)
        return ClipCreated(
            seq = member.seq,
            lamport = member.lamportClock,
            deviceId = member.deviceId,
            clipId = member.id,
            createdAt = member.createdAt,
            contentType = clip.contentType.name,
            contentHash = clip.contentHash,
            sensitive = clip.sensitive,
            categories = clip.categories.names(),
            payload = bytes.takeIf { it.isNotEmpty() && it.size <= cap },
            payloadSize = bytes.size.toLong(),
        )
    }

    /** A meta follows its clip's filters; one for a member this device doesn't have is relayed as is. */
    private fun metaEvent(event: StoredMeta, peer: Hello, onlySensitive: Set<SensitiveCategory>?): MetaChanged? {
        if (cameFrom(event.seq, peer)) return null
        val member = members.selectById(event.clipId).executeAsOneOrNull()
        val clip = member?.let { clips.selectById(it.clipId).executeAsOneOrNull() }
        if (clip == null) {
            if (onlySensitive != null) return null
        } else if (!allowed(clip, member, peer, onlySensitive)) {
            return null
        }
        return MetaChanged(event.seq, event.lamportClock, event.deviceId, event.clipId, event.field_.name, event.value_)
    }

    private fun allowed(clip: Clip, member: ClipMember, peer: Hello, onlySensitive: Set<SensitiveCategory>?): Boolean {
        val categories = clip.categories.ifEmpty { if (clip.sensitive) setOf(SensitiveCategory.UNKNOWN) else emptySet() }
        if (clip.sensitive && !peer.sensitiveOptIn.containsAll(categories.names())) return false
        if (onlySensitive != null && categories.none { it in onlySensitive }) return false
        val config = config()
        // Own clips only: the author already applied these to what it sent us.
        return member.deviceId != config.deviceId ||
            (member.createdAt >= config.syncEnabledAt && !config.isExcludedSource(clip.sourcePackage))
    }
}
