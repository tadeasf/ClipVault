package cz.tadeasf.clipvault.history

import androidx.paging.PagingSource
import cz.tadeasf.clipvault.detect.SensitiveCategory
import cz.tadeasf.clipvault.model.Clip
import cz.tadeasf.clipvault.model.ClipCounts
import cz.tadeasf.clipvault.model.ClipDraft
import cz.tadeasf.clipvault.model.ClipFilter
import cz.tadeasf.clipvault.model.ClipHints
import cz.tadeasf.clipvault.model.ContentType
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
import kotlinx.coroutines.flow.first
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
        val page = repo.pagingSource(ClipFilter(text, includeSensitive = includeSensitive)).load(params) as PagingSource.LoadResult.Page
        return page.data
    }

    @Test
    fun insertStoresClipWithHashAndDevice() = runTest {
        val clip = assertNotNull(repo.get(repo.insert(ClipDraft("hello")).id))
        assertEquals("hello", clip.content)
        assertEquals(sha256Hex("hello"), clip.contentHash)
        assertEquals("device-a", clip.deviceId)
        assertEquals(1_000L, clip.createdAt)
    }

    @Test
    fun duplicateContentBumpsCreatedAtInsteadOfInserting() = runTest {
        val first = repo.insert(ClipDraft("same")).id
        repo.insert(ClipDraft("other")).id
        time = 5_000L
        val second = repo.insert(ClipDraft("same")).id
        assertEquals(first, second)
        assertEquals(5_000L, repo.get(first)?.createdAt)
        assertEquals(listOf("same", "other"), list().map { it.content })
    }

    @Test
    fun copyingADeletedClipAgainCreatesANewOne() = runTest {
        val first = repo.insert(ClipDraft("again")).id
        repo.delete(first)
        val second = repo.insert(ClipDraft("again")).id
        assertTrue(first != second)
        assertEquals(listOf(second), list().map { it.id })
    }

    @Test
    fun searchMatchesPrefixesAndHidesSensitiveAndDeleted() = runTest {
        repo.insert(ClipDraft("kotlin multiplatform")).id
        repo.insert(ClipDraft("kotlin secret", ClipHints(sourcePackage = "com.x8bit.bitwarden"))).id
        val gone = repo.insert(ClipDraft("kotlin deleted")).id
        repo.delete(gone)
        repo.insert(ClipDraft("unrelated")).id

        assertEquals(listOf("kotlin multiplatform"), repo.search("kot multi").map { it.content })
        assertEquals(listOf("kotlin multiplatform"), repo.search("platform").map { it.content })
        assertEquals(setOf("kotlin multiplatform", "kotlin secret"), repo.search("kotlin", includeSensitive = true).map { it.content }.toSet())
        assertEquals(listOf("kotlin multiplatform"), list("kotlin").map { it.content })
    }

    @Test
    fun searchTreatsFtsSyntaxAsLiteralText() = runTest {
        repo.insert(ClipDraft("a OR b")).id
        assertEquals(1, repo.search("OR").size)
        assertEquals(0, repo.search("\"").size)
        assertEquals(0, repo.search("NEAR(").size)
        assertEquals(emptyList(), repo.search("   "))
    }

    @Test
    fun listHidesSensitiveUnlessAsked() = runTest {
        repo.insert(ClipDraft("plain")).id
        repo.insert(ClipDraft("Váš ověřovací kód je 123456")).id
        assertEquals(listOf("plain"), list().map { it.content })
        assertEquals(2, list(includeSensitive = true).size)
    }

    @Test
    fun pinnedClipsComeFirstAndUnpinWorks() = runTest {
        val old = repo.insert(ClipDraft("old")).id
        time = 2_000L
        repo.insert(ClipDraft("new")).id
        repo.setPinned(old, true)
        assertEquals(listOf("old", "new"), list().map { it.content })
        assertTrue(repo.get(old)!!.pinned)

        repo.setPinned(old, false)
        assertEquals(listOf("new", "old"), list().map { it.content })
    }

    @Test
    fun deleteWipesContentAndBeatsALaterPin() = runTest {
        val id = repo.insert(ClipDraft("password-ish")).id
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
        val a = repo.insert(ClipDraft("a")).id
        repo.setPinned(a, true)
        val b = repo.insert(ClipDraft("b")).id
        assertEquals(1L, repo.get(a)?.lamportClock)
        assertEquals(3L, repo.get(b)?.lamportClock)
    }

    @Test
    fun clipsAndMetaEventsShareOneSeqCounter() = runTest {
        val a = repo.insert(ClipDraft("a")).id // seq 1
        repo.setPinned(a, true) // seq 2 (MetaEvent)
        val b = repo.insert(ClipDraft("b")).id // seq 3
        assertEquals(1L, repo.get(a)?.seq)
        assertEquals(3L, repo.get(b)?.seq)
        repo.insert(ClipDraft("a")).id // re-copy: a moves to seq 4
        assertEquals(4L, repo.get(a)?.seq)
    }

    @Test
    fun trigramSearchFindsSubstringsAndFoldsDiacritics() = runTest {
        repo.insert(ClipDraft("https://github.com/tadeasf/ClipVault")).id
        repo.insert(ClipDraft("Ověřovací kód pro přihlášení")).id
        assertEquals(1, repo.search("hub").size)
        assertEquals(1, repo.search("ipvau").size)
        assertEquals(1, repo.search("overovaci kod").size)
        assertEquals(1, repo.search("PŘIHLÁŠENÍ").size)
    }

    @Test
    fun shortTokensFallBackToWordPrefixSearch() = runTest {
        repo.insert(ClipDraft("go build ./...")).id
        repo.insert(ClipDraft("cargo run")).id
        assertEquals(listOf("go build ./..."), repo.search("go").map { it.content })
        assertEquals(listOf("go build ./..."), list("go bu").map { it.content })
    }

    @Test
    fun insertDetectsContentTypeAndSensitivity() = runTest {
        val url = assertNotNull(repo.get(repo.insert(ClipDraft("https://example.com/a?b=1")).id))
        assertEquals(ContentType.URL, url.contentType)
        assertFalse(url.sensitive)
        val card = assertNotNull(repo.get(repo.insert(ClipDraft("4111 1111 1111 1111")).id))
        assertTrue(card.sensitive)
        assertEquals(setOf(SensitiveCategory.CARD_NUMBER), card.categories)
        assertEquals(emptySet(), url.categories)
        val fromManager = repo.get(repo.insert(ClipDraft("hunter2", ClipHints(sourcePackage = "com.beemdevelopment.aegis"))).id)
        assertTrue(assertNotNull(fromManager).sensitive)
    }

    @Test
    fun sensitiveClipsExpireAfterTtlUnlessPinned() = runTest {
        val otp = repo.insert(ClipDraft("483920")).id
        val pinnedOtp = repo.insert(ClipDraft("771204")).id
        repo.setPinned(pinnedOtp, true)
        val plain = repo.insert(ClipDraft("shopping list")).id
        time += RetentionPolicy.DEFAULT_SENSITIVE_TTL_MILLIS - 1
        repo.applyRetention(RetentionPolicy())
        assertNotNull(repo.get(otp))
        time += 2
        repo.applyRetention(RetentionPolicy())
        assertEquals(null, repo.get(otp))
        assertNotNull(repo.get(pinnedOtp))
        assertNotNull(repo.get(plain))
    }

    @Test
    fun retentionByCountKeepsNewestAndAllPinned() = runTest {
        val pinned = repo.insert(ClipDraft("pinned")).id
        repo.setPinned(pinned, true)
        for (i in 1..5) {
            time += 1
            repo.insert(ClipDraft("clip $i")).id
        }
        repo.applyRetention(RetentionPolicy(maxCount = 2))
        assertEquals(listOf("pinned", "clip 5", "clip 4"), list().map { it.content })
    }

    @Test
    fun retentionByAgeSparesPinned() = runTest {
        val pinned = repo.insert(ClipDraft("pinned old")).id
        repo.setPinned(pinned, true)
        repo.insert(ClipDraft("old")).id
        time = 10_000L
        repo.insert(ClipDraft("fresh")).id
        repo.applyRetention(RetentionPolicy(maxAgeMillis = 5_000L))
        assertEquals(listOf("pinned old", "fresh"), list().map { it.content })
    }

    @Test
    fun pagingSourcePagesThroughResults() = runTest {
        for (i in 1..5) {
            time += 1
            repo.insert(ClipDraft("item $i")).id
        }
        val first = repo.pagingSource().load(PagingSource.LoadParams.Refresh(null, 2, false)) as PagingSource.LoadResult.Page
        assertEquals(listOf("item 5", "item 4"), first.data.map { it.content })
        val next = assertNotNull(first.nextKey)
        val second = repo.pagingSource().load(PagingSource.LoadParams.Append(next, 2, false)) as PagingSource.LoadResult.Page
        assertEquals(listOf("item 3", "item 2"), second.data.map { it.content })
    }

    @Test
    fun databaseFileIsNotPlaintextSqlite() = runTest {
        repo.insert(ClipDraft("on disk")).id
        val plaintextHeader = "SQLite format 3\u0000".encodeToByteArray()
        assertFalse(plaintextHeader.contentEquals(store.header()))
    }

    @Test
    fun wrongKeyCannotOpenTheDatabase() = runTest {
        repo.insert(ClipDraft("secret")).id
        driver.close()
        val wrongKey = ByteArray(32) { 9 }
        assertFails {
            val other = store.open(wrongKey)
            try {
                clipDatabase(other).clipQueries.countAll().executeAsOne()
            } finally {
                other.close()
            }
        }
        // And the right key still works.
        val reopened = store.open(key)
        try {
            assertEquals(1L, clipDatabase(reopened).clipQueries.countAll().executeAsOne())
        } finally {
            reopened.close()
        }
    }

    private suspend fun filtered(filter: ClipFilter): List<String> {
        val params = PagingSource.LoadParams.Refresh<Int>(key = null, loadSize = 100, placeholdersEnabled = false)
        return (repo.pagingSource(filter).load(params) as PagingSource.LoadResult.Page).data.map { it.content }
    }

    @Test
    fun insertReportsDuplicatesAndSensitivity() = runTest {
        val first = repo.insert(ClipDraft("hello"))
        assertFalse(first.duplicate)
        assertFalse(first.sensitive)
        val again = repo.insert(ClipDraft("hello"))
        assertEquals(first.id, again.id)
        assertTrue(again.duplicate)
        assertTrue(repo.insert(ClipDraft("483920")).sensitive)
    }

    @Test
    fun storesSourcePackageWhenKnown() = runTest {
        val id = repo.insert(ClipDraft("from chrome", ClipHints(sourcePackage = "com.android.chrome"))).id
        assertEquals("com.android.chrome", repo.get(id)?.sourcePackage)
        assertEquals(null, repo.get(repo.insert(ClipDraft("unknown source")).id)?.sourcePackage)
    }

    @Test
    fun filtersByTypeAndPinnedAlsoWhileSearching() = runTest {
        repo.insert(ClipDraft("https://example.com/kotlin"))
        val pinned = repo.insert(ClipDraft("kotlin notes")).id
        repo.setPinned(pinned, true)
        repo.insert(ClipDraft("jan@example.com"))
        assertEquals(listOf("https://example.com/kotlin"), filtered(ClipFilter(type = ContentType.URL)))
        assertEquals(listOf("kotlin notes"), filtered(ClipFilter(pinnedOnly = true)))
        assertEquals(listOf("https://example.com/kotlin"), filtered(ClipFilter("kotlin", type = ContentType.URL)))
        assertEquals(listOf("kotlin notes"), filtered(ClipFilter("kot", pinnedOnly = true)))
        assertEquals(3, filtered(ClipFilter()).size)
    }

    @Test
    fun countsTrackLiveAndSensitiveClips() = runTest {
        assertEquals(ClipCounts(0, 0), repo.counts().first())
        repo.insert(ClipDraft("plain"))
        val otp = repo.insert(ClipDraft("483920")).id
        assertEquals(ClipCounts(2, 1), repo.counts().first())
        repo.delete(otp)
        assertEquals(ClipCounts(1, 0), repo.counts().first())
    }

}
