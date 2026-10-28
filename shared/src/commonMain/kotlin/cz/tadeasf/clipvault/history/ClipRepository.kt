package cz.tadeasf.clipvault.history

import androidx.paging.PagingSource
import cz.tadeasf.clipvault.model.Clip
import cz.tadeasf.clipvault.model.ClipDraft
import cz.tadeasf.clipvault.model.RetentionPolicy

interface ClipRepository {
    /** Stores [draft], or bumps `createdAt` of the live clip with the same content. Returns the clip id. */
    suspend fun insert(draft: ClipDraft): String

    suspend fun get(id: String): Clip?

    suspend fun search(text: String, includeSensitive: Boolean = false, limit: Long = 50): List<Clip>

    /** Pinned first, then newest. A blank [text] lists everything. */
    fun pagingSource(text: String = "", includeSensitive: Boolean = false): PagingSource<Int, Clip>

    suspend fun setPinned(id: String, pinned: Boolean)

    /** Tombstones the clip and erases its content. */
    suspend fun delete(id: String)

    suspend fun applyRetention(policy: RetentionPolicy)
}
