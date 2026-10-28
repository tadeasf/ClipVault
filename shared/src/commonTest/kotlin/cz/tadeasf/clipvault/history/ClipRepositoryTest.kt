package cz.tadeasf.clipvault.history

import androidx.paging.PagingSource
import cz.tadeasf.clipvault.model.Clip
import cz.tadeasf.clipvault.model.ClipDraft
import cz.tadeasf.clipvault.model.RetentionPolicy
import cz.tadeasf.clipvault.security.sha256Hex
import cz.tadeasf.clipvault.storage.clipDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

/** Runs against a real SQLCipher database on jvm and on Android devices. */
class ClipRepositoryTest {
    private val key = ByteArray(32) { it.toByte() }
    private var time = 1_000L
    private val store = newTestDatabase()
    private val driver = store.open(key)
    private val repo = SqlClipRepository(clipDatabase(driver), "device-a", { time }, Dispatchers.Unconfined)

    @AfterTest
    fun close() = driver.close()

    private suspend fun list(text: String = "", includeSensitive: Boolean = false): List<Clip> {
        val params = PagingSource.LoadParams.Refresh<Int>(key = null, loadSize = 100, placeholdersEnabled = false)
        val page = repo.pagingSource(text, includeSensitive).load(params) as PagingSource.LoadResult.Page
        return page.data
    }

    @Test
    fun insertStoresClipWithHashAndDevice() = runTest {
        val clip = assertNotNull(repo.get(repo.insert(ClipDraft("hello"))))
        assertEquals("hello", clip.content)
        assertEquals(sha256Hex("hello"), clip.contentHash)
        assertEquals("device-a", clip.deviceId)
        assertEquals(1_000L, clip.createdAt)
    }

    @Test
    fun duplicateContentBumpsCreatedAtInsteadOfInserting() = runTest {
        val first = repo.insert(ClipDraft("same"))
        repo.insert(ClipDraft("other"))
        time = 5_000L
        val second = repo.insert(ClipDraft("same"))
        assertEquals(first, second)
        assertEquals(5_000L, repo.get(first)?.createdAt)
        assertEquals(listOf("same", "other"), list().map { it.content })
    }

    @Test
    fun copyingADeletedClipAgainCreatesANewOne() = runTest {
        val first = repo.insert(ClipDraft("again"))
        repo.delete(first)
        val second = repo.insert(ClipDraft("again"))
        assertTrue(first != second)
        assertEquals(listOf(second), list().map { it.id })
    }

    @Test
    fun searchMatchesPrefixesAndHidesSensitiveAndDeleted() = runTest {
        repo.insert(ClipDraft("kotlin multiplatform"))
        repo.insert(ClipDraft("kotlin secret", sensitive = true))
        val gone = repo.insert(ClipDraft("kotlin deleted"))
        repo.delete(gone)
        repo.insert(ClipDraft("unrelated"))

        assertEquals(listOf("kotlin multiplatform"), repo.search("kot multi").map { it.content })
        assertEquals(setOf("kotlin multiplatform", "kotlin secret"), repo.search("kotlin", includeSensitive = true).map { it.content }.toSet())
        assertEquals(listOf("kotlin multiplatform"), list("kotlin").map { it.content })
    }

    @Test
    fun searchTreatsFtsSyntaxAsLiteralText() = runTest {
        repo.insert(ClipDraft("a OR b"))
        assertEquals(1, repo.search("OR").size)
        assertEquals(0, repo.search("\"").size)
        assertEquals(0, repo.search("NEAR(").size)
        assertEquals(emptyList(), repo.search("   "))
    }

    @Test
    fun listHidesSensitiveUnlessAsked() = runTest {
        repo.insert(ClipDraft("plain"))
        repo.insert(ClipDraft("otp 123456", sensitive = true))
        assertEquals(listOf("plain"), list().map { it.content })
        assertEquals(2, list(includeSensitive = true).size)
    }

    @Test
    fun pinnedClipsComeFirstAndUnpinWorks() = runTest {
        val old = repo.insert(ClipDraft("old"))
        time = 2_000L
        repo.insert(ClipDraft("new"))
        repo.setPinned(old, true)
        assertEquals(listOf("old", "new"), list().map { it.content })
        assertTrue(repo.get(old)!!.pinned)

        repo.setPinned(old, false)
        assertEquals(listOf("new", "old"), list().map { it.content })
    }

    @Test
    fun deleteWipesContentAndBeatsALaterPin() = runTest {
        val id = repo.insert(ClipDraft("password-ish"))
        repo.delete(id)
        repo.setPinned(id, true)
        val clip = assertNotNull(repo.get(id))
        assertTrue(clip.deleted)
        assertFalse(clip.pinned)
        assertEquals("", clip.content)
        assertEquals(emptyList(), repo.search("password"))
        assertEquals(emptyList(), list())
    }

    @Test
    fun metaChangesOnUnknownClipAreIgnored() = runTest {
        repo.setPinned("missing", true)
        repo.delete("missing")
        assertEquals(null, repo.get("missing"))
    }

    @Test
    fun lamportClockIncreasesAcrossClipsAndMetaEvents() = runTest {
        val a = repo.insert(ClipDraft("a"))
        repo.setPinned(a, true)
        val b = repo.insert(ClipDraft("b"))
        assertEquals(1L, repo.get(a)?.lamportClock)
        assertEquals(3L, repo.get(b)?.lamportClock)
    }

    @Test
    fun retentionByCountKeepsNewestAndAllPinned() = runTest {
        val pinned = repo.insert(ClipDraft("pinned"))
        repo.setPinned(pinned, true)
        for (i in 1..5) {
            time += 1
            repo.insert(ClipDraft("clip $i"))
        }
        repo.applyRetention(RetentionPolicy(maxCount = 2))
        assertEquals(listOf("pinned", "clip 5", "clip 4"), list().map { it.content })
    }

    @Test
    fun retentionByAgeSparesPinned() = runTest {
        val pinned = repo.insert(ClipDraft("pinned old"))
        repo.setPinned(pinned, true)
        repo.insert(ClipDraft("old"))
        time = 10_000L
        repo.insert(ClipDraft("fresh"))
        repo.applyRetention(RetentionPolicy(maxAgeMillis = 5_000L))
        assertEquals(listOf("pinned old", "fresh"), list().map { it.content })
    }

    @Test
    fun pagingSourcePagesThroughResults() = runTest {
        for (i in 1..5) {
            time += 1
            repo.insert(ClipDraft("item $i"))
        }
        val first = repo.pagingSource().load(PagingSource.LoadParams.Refresh(null, 2, false)) as PagingSource.LoadResult.Page
        assertEquals(listOf("item 5", "item 4"), first.data.map { it.content })
        val next = assertNotNull(first.nextKey)
        val second = repo.pagingSource().load(PagingSource.LoadParams.Append(next, 2, false)) as PagingSource.LoadResult.Page
        assertEquals(listOf("item 3", "item 2"), second.data.map { it.content })
    }

    @Test
    fun databaseFileIsNotPlaintextSqlite() = runTest {
        repo.insert(ClipDraft("on disk"))
        val plaintextHeader = "SQLite format 3\u0000".encodeToByteArray()
        assertFalse(plaintextHeader.contentEquals(store.header()))
    }

    @Test
    fun wrongKeyCannotOpenTheDatabase() = runTest {
        repo.insert(ClipDraft("secret"))
        driver.close()
        val wrongKey = ByteArray(32) { 9 }
        assertFails {
            val other = store.open(wrongKey)
            try {
                clipDatabase(other).clipQueries.countLive(true).executeAsOne()
            } finally {
                other.close()
            }
        }
        // And the right key still works.
        val reopened = store.open(key)
        try {
            assertEquals(1L, clipDatabase(reopened).clipQueries.countLive(true).executeAsOne())
        } finally {
            reopened.close()
        }
    }
}
