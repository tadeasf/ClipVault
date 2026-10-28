package cz.tadeasf.clipvault.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** A random DB key, wrapped with an AES-GCM key that never leaves AndroidKeyStore. */
class KeystoreDbKeyProvider(
    private val wrappedKeyFile: File,
    private val alias: String = "clipvault-db-kek",
) : DbKeyProvider {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "db.key"))

    override fun loadKey(): ByteArray? =
        if (wrappedKeyFile.exists()) AesGcm.open(wrappingKey(), wrappedKeyFile.readBytes()) else null

    override fun createKey(): ByteArray = randomBytes(DB_KEY_BYTES).also {
        wrappedKeyFile.writeAtomically(AesGcm.seal(wrappingKey(), it))
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return keyStore.getKey(alias, null) as SecretKey? ?: generateWrappingKey()
    }

    private fun generateWrappingKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
    }
}
