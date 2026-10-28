package cz.tadeasf.clipvault.history

import androidx.paging.PagingSource
import cz.tadeasf.clipvault.model.Clip
import cz.tadeasf.clipvault.model.ClipDraft
import cz.tadeasf.clipvault.model.ClipHints
import cz.tadeasf.clipvault.model.RetentionPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class ClipIngestTest {

    private class RecordingRepository : ClipRepository {
        val inserted = mutableListOf<ClipDraft>()
        override suspend fun insert(draft: ClipDraft) = "id-${inserted.size}".also { inserted += draft }
        override suspend fun get(id: String): Clip? = null
        override suspend fun search(text: String, includeSensitive: Boolean, limit: Long) = emptyList<Clip>()
        override fun pagingSource(text: String, includeSensitive: Boolean): PagingSource<Int, Clip> = error("unused")
        override suspend fun setPinned(id: String, pinned: Boolean) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun applyRetention(policy: RetentionPolicy) = Unit
    }

    private val repository = RecordingRepository()
    private val ingest = ClipIngest(repository)

    @Test
    fun storesTextWithHints() = runTest {
        val hints = ClipHints(sourcePackage = "com.android.chrome")
        assertEquals(IngestResult.Stored("id-0"), ingest.ingest("hello", hints))
        assertEquals(listOf(ClipDraft("hello", hints)), repository.inserted)
    }

    @Test
    fun skipsBlankText() = runTest {
        assertEquals(IngestResult.Skipped(SkipReason.BLANK), ingest.ingest("  \n\t"))
        assertEquals(emptyList(), repository.inserted)
    }

    @Test
    fun neverStoresClipsFromSensitiveSources() = runTest {
        val sources = listOf(
            ClipHints(isSensitiveFlag = true),
            ClipHints(sourcePackage = "com.x8bit.bitwarden"),
            ClipHints(sourceWindowTitle = "Vault.kdbx - KeePassXC"),
        )
        for (hints in sources) {
            assertEquals(IngestResult.Skipped(SkipReason.SENSITIVE_SOURCE), ingest.ingest("hunter2", hints), "$hints")
        }
        assertEquals(emptyList(), repository.inserted)
    }
}
