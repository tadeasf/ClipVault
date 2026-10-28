package cz.tadeasf.clipvault.security

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Instrumented: Robolectric has no AndroidKeyStore provider. */
class KeystoreDbKeyProviderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "test-${UUID.randomUUID()}"
    private val file = File(context.noBackupFilesDir, "$alias.key")

    @AfterTest
    fun cleanUp() {
        file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun keyRoundTripsThroughKeystoreWrapping() {
        val provider = KeystoreDbKeyProvider(file, alias)
        assertNull(provider.loadKey())
        val key = provider.createKey()
        assertEquals(DB_KEY_BYTES, key.size)
        assertContentEquals(key, KeystoreDbKeyProvider(file, alias).loadKey())
        assertFalse(file.readBytes().toHex().contains(key.toHex()))
    }
}
