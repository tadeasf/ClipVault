package cz.tadeasf.clipvault.security

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class DesktopKeyTest {
    private val dir = Files.createTempDirectory("clipvault-keys").toFile()
    private val cheap = Argon2Cost(memoryKiB = 1024, iterations = 1)

    private fun keyFile(passphrase: String, cost: Argon2Cost = cheap) =
        PassphraseKeyFile(File(dir, "db.key"), { passphrase.toCharArray() }, cost)

    private class MemoryKeyring : Keyring {
        val secrets = mutableMapOf<String, ByteArray>()
        override fun load(account: String) = secrets[account]
        override fun store(account: String, secret: ByteArray) {
            secrets[account] = secret
        }
    }

    @Test
    fun keyFileRoundTripWithDefaultArgon2Cost() {
        val key = ByteArray(DB_KEY_BYTES) { 3 }
        keyFile("correct horse", Argon2Cost()).store(key)
        assertContentEquals(key, keyFile("correct horse", Argon2Cost()).load())
    }

    @Test
    fun keyFileRejectsWrongPassphrase() {
        keyFile("right").store(ByteArray(DB_KEY_BYTES) { 1 })
        assertFailsWith<WrongPassphraseException> { keyFile("wrong").load() }
    }

    @Test
    fun keyFileDoesNotContainTheKeyInClear() {
        val key = ByteArray(DB_KEY_BYTES) { (it * 7).toByte() }
        keyFile("pw").store(key)
        val raw = File(dir, "db.key").readBytes()
        assertFalse(raw.toHex().contains(key.toHex()))
    }

    @Test
    fun keyFileRejectsForeignFiles() {
        File(dir, "db.key").writeBytes(ByteArray(64))
        assertFailsWith<IllegalStateException> { keyFile("pw").load() }
    }

    @Test
    fun noKeyringFallsBackToPassphraseFile() {
        val provider = DesktopDbKeyProvider(keyring = null, keyFile = keyFile("pw"))
        assertTrue(provider.usesFallback)
        assertNull(provider.loadKey())
        val key = provider.keyFor(databaseExists = false)
        assertTrue(File(dir, "db.key").exists())
        assertContentEquals(key, DesktopDbKeyProvider(null, keyFile("pw")).loadKey())
    }

    @Test
    fun keyringIsPreferredWhenAvailable() {
        val keyring = MemoryKeyring()
        val provider = DesktopDbKeyProvider(keyring, keyFile("pw"))
        assertFalse(provider.usesFallback)
        val key = provider.keyFor(databaseExists = false)
        assertFalse(File(dir, "db.key").exists())
        assertContentEquals(key, keyring.secrets.values.single())
        assertContentEquals(key, provider.loadKey())
    }

    @Test
    fun existingKeyFileWinsOverALaterKeyring() {
        val fileKey = DesktopDbKeyProvider(null, keyFile("pw")).createKey()
        val keyring = MemoryKeyring().apply { store("history-db", ByteArray(DB_KEY_BYTES) { 9 }) }
        val provider = DesktopDbKeyProvider(keyring, keyFile("pw"))
        assertTrue(provider.usesFallback)
        assertContentEquals(fileKey, provider.loadKey())
    }

    /**
     * Talks to a real Secret Service, so it runs in CI only (design.md section 12): never against the
     * keyring of the machine running the tests. Locally it is skipped and listed as such.
     */
    @Test
    fun secretServiceRoundTrip() {
        assumeTrue("keyring integration runs in CI only (design.md section 12)", System.getenv("CI") == "true")
        val detected = SecretServiceKeyring.detect(service = "cz.tadeasf.clipvault.test")
        assumeTrue("no Secret Service on this machine", detected != null)
        val keyring = checkNotNull(detected)
        val account = "test-${UUID.randomUUID()}"
        try {
            val secret = ByteArray(DB_KEY_BYTES) { (it + 1).toByte() }
            assertNull(keyring.load(account))
            keyring.store(account, secret)
            assertContentEquals(secret, assertNotNull(keyring.load(account)))
        } finally {
            keyring.clear(account)
        }
        assertEquals(null, keyring.load(account))
    }
}
