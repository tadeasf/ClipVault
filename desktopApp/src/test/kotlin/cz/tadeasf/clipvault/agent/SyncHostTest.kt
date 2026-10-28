package cz.tadeasf.clipvault.agent

import cz.tadeasf.clipvault.detect.SensitiveCategory
import cz.tadeasf.clipvault.storage.JvmDriverFactory
import cz.tadeasf.clipvault.storage.clipDatabase
import cz.tadeasf.clipvault.sync.SyncConfig
import cz.tadeasf.clipvault.sync.db.SyncDatabase
import cz.tadeasf.clipvault.sync.pairing.loadSyncPrefs
import cz.tadeasf.clipvault.sync.pairing.saveSyncPrefs
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class SyncHostTest {
    private val dir: File = Files.createTempDirectory("cv").toFile()
    private val key = Random.nextBytes(32)
    private val db = clipDatabase(JvmDriverFactory(File(dir, "history.db")).open(key))
    private val syncDb = SyncDatabase(JvmDriverFactory(File(dir, "sync.db"), SyncDatabase.Schema).open(key))
    private val host = SyncHost(db, syncDb, AgentCore(db, FakeCopyQ(File(dir, "bin").apply { mkdirs() }).copyq(), io = Dispatchers.Unconfined), CoroutineScope(Dispatchers.Unconfined))
    private val applied = mutableListOf<SyncConfig>()

    /** The `config` command's own connection to the store, like its separate process. */
    private val other = clipDatabase(JvmDriverFactory(File(dir, "history.db")).open(key))

    init {
        host.reconfigure = { applied += it }
    }

    @Test
    fun aSyncRelevantSettingsChangeReconfiguresOnceAndNothingElseDoes() = runBlocking {
        host.refreshConfig()
        AgentState(other).promoteToClipboard = true
        host.refreshConfig()
        assertEquals(0, applied.size, "nothing sync-relevant changed")

        saveSyncPrefs(other, loadSyncPrefs(other).copy(name = "Work laptop"))
        host.refreshConfig()
        host.refreshConfig()
        assertEquals(listOf("Work laptop"), applied.map { it.name })

        saveSyncPrefs(other, loadSyncPrefs(other).copy(sensitiveOptIn = setOf(SensitiveCategory.OTP)))
        host.refreshConfig()
        assertEquals(setOf(SensitiveCategory.OTP), applied.last().sensitiveOptIn)

        AgentState(other).excludedTitles = setOf("signal")
        host.refreshConfig()
        host.refreshConfig()
        assertEquals(3, applied.size)
        assertEquals(true, applied.last().isExcludedSource("Signal - Mom"), "the capture path's matching")
    }

    @Test
    fun syncSensitiveParsesCategoryNames() {
        assertEquals(setOf(SensitiveCategory.OTP, SensitiveCategory.IBAN), sensitiveCategories("otp,IBAN"))
        assertEquals(emptySet(), sensitiveCategories("none"))
        assertEquals(null, sensitiveCategories("unknown"))
        assertEquals(null, sensitiveCategories("otp,pin"))
    }
}
