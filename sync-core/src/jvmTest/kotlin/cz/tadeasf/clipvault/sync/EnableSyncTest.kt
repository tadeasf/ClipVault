package cz.tadeasf.clipvault.sync

import cz.tadeasf.clipvault.settings.deviceId
import cz.tadeasf.clipvault.sync.crypto.crypto
import cz.tadeasf.clipvault.sync.pairing.BIP39_ENGLISH
import cz.tadeasf.clipvault.sync.pairing.PeerStore
import cz.tadeasf.clipvault.sync.pairing.deviceIdOf
import cz.tadeasf.clipvault.sync.pairing.enableSync
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlinx.coroutines.runBlocking

class EnableSyncTest {
    private val c = crypto()

    @Test
    fun kotlinWordlistIsTheVendoredFile() {
        assertEquals(File("../third_party/bip39/english.txt").readText().split('\n').filter(String::isNotEmpty), BIP39_ENGLISH)
        assertEquals(2048, BIP39_ENGLISH.toSet().size)
    }

    @Test
    fun enablingSyncRelabelsOnlyThisDevicesRowsOnce() = runBlocking<Unit> {
        Net("A", "B").use { net ->
            val a = net["A"]
            val random = deviceId(a.db) // the pre-sync per-install id
            a.deviceId = random
            a.copy("from before sync")
            a.pin("from before sync")
            val foreign = net["B"].copy("B's clip")
            net.sync("A", "B") // unauthenticated test bus: A now holds a row authored by "B"
            a.syncDb.syncQueries.forgetCursor("B") // a real device can't sync before enableSync

            val peers = PeerStore(a.syncDb, c) { 1L }
            val id = enableSync(a.db, peers, c)
            assertEquals(deviceIdOf(c, peers.identity()!!.publicKey), id)
            assertEquals(id, deviceId(a.db), "the shared deviceId() now returns the derived id")
            assertNotEquals(random, id)
            val clips = a.db.clipQueries.page(true, false, null, 100, 0).executeAsList().associate { it.content to it.deviceId }
            assertEquals(id, clips["from before sync"])
            assertEquals("B", clips["B's clip"], "foreign rows keep their author")
            assertEquals(listOf(id), a.db.metaEventQueries.forClip(a.rowOf("from before sync")!!).executeAsList().map { it.deviceId }.distinct())
            assertEquals(id, enableSync(a.db, peers, c), "idempotent")
            assertEquals(foreign, net["B"].rowOf("B's clip"))
        }
    }

    @Test
    fun enablingSyncAfterSyncingUnderTheOldIdIsRefused() = runBlocking<Unit> {
        Net("A", "B").use { net ->
            net.sync("A", "B") // leaves a PeerCursor, as if A had synced without an identity
            assertFailsWith<IllegalStateException> { enableSync(net["A"].db, PeerStore(net["A"].syncDb, c) { 1L }, c) }
        }
    }
}
