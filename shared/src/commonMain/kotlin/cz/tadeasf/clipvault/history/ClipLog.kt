package cz.tadeasf.clipvault.history

import cz.tadeasf.clipvault.db.ClipDatabase
import cz.tadeasf.clipvault.db.ClipMember
import cz.tadeasf.clipvault.model.ClipFlags
import cz.tadeasf.clipvault.model.MetaEvent
import cz.tadeasf.clipvault.model.foldMeta

/**
 * Log primitives shared by local writes ([SqlClipRepository]) and sync (`SyncApplier`). Every function
 * must run inside the caller's transaction.
 */
class ClipLog(private val db: ClipDatabase) {
    private val clips get() = db.clipQueries
    private val members get() = db.clipMemberQueries

    /** The UPDATE takes SQLite's write lock before the read, so two writers never share a seq. */
    fun nextSeq(): Long {
        clips.bumpSeq()
        return clips.currentSeq().executeAsOne()
    }

    /** Lamport clock for a local event. */
    fun tick(): Long {
        members.tickClock()
        return members.currentClock().executeAsOne()
    }

    /** Lamport receive rule: max(clock, seen) + 1. */
    fun observe(seen: Long) = members.observeClock(seen)

    fun flags(memberId: String): ClipFlags = foldMeta(db.metaEventQueries.forClip(memberId, ::MetaEvent).executeAsList())

    fun liveMembers(clipId: String): List<ClipMember> = members.forClip(clipId).executeAsList().filterNot { flags(it.id).deleted }

    /**
     * Recomputes a row from its members (docs/sync/protocol.md section 8): deleted when no member is live,
     * pinned when a live member is, createdAt the newest live member's. A deleted row loses its content.
     */
    fun refold(clipId: String) {
        val all = members.forClip(clipId).executeAsList().associateWith { flags(it.id) }
        if (all.isEmpty()) return
        val live = all.filterValues { !it.deleted }
        val deleted = live.isEmpty()
        val pinned = live.values.any { it.pinned }
        clips.setGroupState(pinned, deleted, live.ifEmpty { all }.keys.maxOf { it.createdAt }, clipId)
        if (deleted) clips.wipeContent(clipId)
    }
}
