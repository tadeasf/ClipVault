package cz.tadeasf.clipvault.history

import androidx.paging.PagingSource
import cz.tadeasf.clipvault.model.Clip
import cz.tadeasf.clipvault.model.ClipCounts
import cz.tadeasf.clipvault.model.ClipDraft
import cz.tadeasf.clipvault.model.ClipFilter
import cz.tadeasf.clipvault.model.ClipHints
import cz.tadeasf.clipvault.model.InsertResult
import cz.tadeasf.clipvault.model.RetentionPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

class ClipIngestTest {

    private class RecordingRepository : ClipRepository {
        val inserted = mutableListOf<ClipDraft>()
        override suspend fun insert(draft: ClipDraft) =
            InsertResult("id-${inserted.size}", duplicate = false, sensitive = false).also { inserted += draft }
        override suspend fun get(id: String): Clip? = null
        override suspend fun search(text: String, includeSensitive: Boolean, limit: Long) = emptyList<Clip>()
        override fun pagingSource(filter: ClipFilter): PagingSource<Int, Clip> = error("unused")
        override fun counts(): Flow<ClipCounts> = emptyFlow()
        override suspend fun setPinned(id: String, pinned: Boolean) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun applyRetention(policy: RetentionPolicy) = Unit
    }

    private val repository = RecordingRepository()
    private val excluded = mutableSetOf<String>()
    private val ingest = ClipIngest(repository) { excluded }

    @Test
    fun storesTextWithHints() = runTest {
        val hints = ClipHints(sourcePackage = "com.android.chrome")
        assertEquals(IngestResult.Stored("id-0", duplicate = false, sensitive = false), ingest.ingest("hello", hints))
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

    @Test
    fun skipsExcludedSourcesButKeepsOthers() = runTest {
        excluded += "com.whatsapp"
        assertEquals(IngestResult.Skipped(SkipReason.EXCLUDED_SOURCE), ingest.ingest("hi", ClipHints(sourcePackage = "com.whatsapp")))
        assertEquals(IngestResult.Stored("id-0", false, false), ingest.ingest("hi", ClipHints(sourcePackage = "org.mozilla.firefox")))
        assertEquals(IngestResult.Stored("id-1", false, false), ingest.ingest("no source"))
    }
}
