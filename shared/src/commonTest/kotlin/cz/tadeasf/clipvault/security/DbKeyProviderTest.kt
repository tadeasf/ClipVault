package cz.tadeasf.clipvault.security

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DbKeyProviderTest {

    private class FakeProvider(var stored: ByteArray? = null) : DbKeyProvider {
        override fun loadKey() = stored
        override fun createKey() = ByteArray(DB_KEY_BYTES) { 7 }.also { stored = it }
    }

    @Test
    fun createsKeyForNewDatabase() {
        val provider = FakeProvider()
        assertContentEquals(ByteArray(DB_KEY_BYTES) { 7 }, provider.keyFor(databaseExists = false))
    }

    @Test
    fun loadsExistingKey() {
        val key = ByteArray(DB_KEY_BYTES) { 1 }
        assertContentEquals(key, FakeProvider(key).keyFor(databaseExists = true))
    }

    @Test
    fun refusesToReplaceTheKeyOfAnExistingDatabase() {
        val provider = FakeProvider()
        assertFailsWith<KeyUnavailableException> { provider.keyFor(databaseExists = true) }
        assertEquals(null, provider.stored)
    }

    @Test
    fun hexRoundTrip() {
        val bytes = byteArrayOf(0, 1, 15, 16, 127, -128, -1)
        assertEquals("00010f107f80ff", bytes.toHex())
        assertContentEquals(bytes, bytes.toHex().hexToBytes())
    }

    @Test
    fun sha256MatchesKnownVector() {
        // FIPS 180-2 test vector "abc"
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc"))
    }
}
