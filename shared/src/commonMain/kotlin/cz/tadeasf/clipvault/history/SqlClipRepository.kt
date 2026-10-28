package cz.tadeasf.clipvault.history

import androidx.paging.PagingSource
import app.cash.sqldelight.Query
import app.cash.sqldelight.paging3.QueryPagingSource
import cz.tadeasf.clipvault.db.ClipDatabase
import cz.tadeasf.clipvault.detect.SensitiveDetector
import cz.tadeasf.clipvault.detect.detectContentType
import cz.tadeasf.clipvault.model.Clip
import cz.tadeasf.clipvault.model.ClipDraft
import cz.tadeasf.clipvault.model.MetaEvent
import cz.tadeasf.clipvault.model.MetaField
import cz.tadeasf.clipvault.model.RetentionPolicy
import cz.tadeasf.clipvault.model.foldMeta
import cz.tadeasf.clipvault.security.sha256Hex
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.withContext

class SqlClipRepository(
    private val db: ClipDatabase,
    private val deviceId: String,
    private val now: () -> Long,
    private val io: CoroutineContext,
) : ClipRepository {
    private val clips get() = db.clipQueries
    private val meta get() = db.metaEventQueries

    override suspend fun insert(draft: ClipDraft): String = withContext(io) {
        val hash = sha256Hex(draft.content)
        db.transactionWithResult {
            val existing = clips.findLiveByHash(hash).executeAsOneOrNull()
            existing?.also { clips.touch(now(), nextSeq(), it) } ?: newClip(draft, hash)
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun newClip(draft: ClipDraft, hash: String): String {
        val id = Uuid.generateV7().toString()
        val type = detectContentType(draft.content)
        val categories = SensitiveDetector.classify(draft.content, draft.hints)
        clips.insert(id, deviceId, now(), type, hash, draft.content, categories.isNotEmpty(), nextClock(), nextSeq(), categories)
        return id
    }

    override suspend fun get(id: String): Clip? = withContext(io) {
        clips.selectById(id, ::Clip).executeAsOneOrNull()
    }

    override suspend fun search(text: String, includeSensitive: Boolean, limit: Long): List<Clip> {
        val query = ftsQuery(text) ?: return emptyList()
        return withContext(io) { searchPage(query, includeSensitive, limit, 0).executeAsList() }
    }

    override fun pagingSource(text: String, includeSensitive: Boolean): PagingSource<Int, Clip> {
        val query = ftsQuery(text)
        val count = when (query) {
            null -> clips.countLive(includeSensitive)
            is FtsQuery.Substring -> clips.countSearchTrigram(query.match, includeSensitive)
            is FtsQuery.Prefix -> clips.countSearchPrefix(query.match, includeSensitive)
        }
        return QueryPagingSource(count, clips, io, { limit, offset ->
            if (query == null) clips.page(includeSensitive, limit, offset, ::Clip)
            else searchPage(query, includeSensitive, limit, offset)
        })
    }

    private fun searchPage(query: FtsQuery, includeSensitive: Boolean, limit: Long, offset: Long): Query<Clip> =
        when (query) {
            is FtsQuery.Substring -> clips.searchTrigram(query.match, includeSensitive, limit, offset, ::Clip)
            is FtsQuery.Prefix -> clips.searchPrefix(query.match, includeSensitive, limit, offset, ::Clip)
        }

    override suspend fun setPinned(id: String, pinned: Boolean) = appendMeta(id, MetaField.PINNED, pinned)

    override suspend fun delete(id: String) = appendMeta(id, MetaField.DELETED, true)

    private suspend fun appendMeta(id: String, field: MetaField, value: Boolean) = withContext(io) {
        db.transaction {
            if (clips.selectById(id).executeAsOneOrNull() == null) return@transaction
            meta.insert(nextSeq(), id, field, value, nextClock(), deviceId)
            val flags = foldMeta(meta.forClip(id, ::MetaEvent).executeAsList())
            clips.setFlags(flags.pinned, flags.deleted, id)
            if (flags.deleted) clips.wipeContent(id)
        }
    }

    override suspend fun applyRetention(policy: RetentionPolicy) = withContext(io) {
        db.transaction {
            policy.sensitiveTtlMillis?.let { clips.deleteSensitiveOlderThan(now() - it) }
            policy.maxAgeMillis?.let { clips.deleteOlderThan(now() - it) }
            policy.maxCount?.let { clips.keepNewest(it) }
            meta.deleteOrphans()
        }
    }

    private fun nextClock(): Long = (meta.maxLamport().executeAsOne().max ?: 0) + 1

    /** Must run inside the caller's transaction: the UPDATE takes SQLite's write lock before the read. */
    private fun nextSeq(): Long {
        clips.bumpSeq()
        return clips.currentSeq().executeAsOne()
    }
}
