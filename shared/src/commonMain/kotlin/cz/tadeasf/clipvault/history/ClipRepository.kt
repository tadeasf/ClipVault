package cz.tadeasf.clipvault.history

import androidx.paging.PagingSource
import cz.tadeasf.clipvault.model.Clip
import cz.tadeasf.clipvault.model.ClipCounts
import cz.tadeasf.clipvault.model.ClipDraft
import cz.tadeasf.clipvault.model.ClipFilter
import cz.tadeasf.clipvault.model.InsertResult
import cz.tadeasf.clipvault.model.RetentionPolicy
import kotlinx.coroutines.flow.Flow

interface ClipRepository {
    /** Stores [draft], or bumps `createdAt` of the live clip with the same content (`duplicate = true`). */
    suspend fun insert(draft: ClipDraft): InsertResult

    suspend fun get(id: String): Clip?

    suspend fun search(text: String, includeSensitive: Boolean = false, limit: Long = 50): List<Clip>

    /** Pinned first, then newest. Invalidates itself when the table changes. */
    fun pagingSource(filter: ClipFilter = ClipFilter()): PagingSource<Int, Clip>

    /** Live counts of non-deleted clips, updated on every change. */
    fun counts(): Flow<ClipCounts>

    suspend fun setPinned(id: String, pinned: Boolean)

    /** Tombstones the clip and erases its content. */
    suspend fun delete(id: String)

    suspend fun applyRetention(policy: RetentionPolicy)
}
