package cz.tadeasf.clipvault.security

/**
 * Keyring first, passphrase file as fallback. Pass `keyring = null` when no Secret Service is
 * available (see [SecretServiceKeyring.detect]). An existing key file always wins, so a keyring that
 * appears later never shadows the key the database was created with.
 */
class DesktopDbKeyProvider(
    private val keyring: Keyring?,
    private val keyFile: PassphraseKeyFile,
) : DbKeyProvider {

    val usesFallback: Boolean get() = keyring == null || keyFile.exists()

    override fun loadKey(): ByteArray? = when {
        keyFile.exists() -> keyFile.load()
        else -> keyring?.load(ACCOUNT)
    }

    override fun createKey(): ByteArray = randomBytes(DB_KEY_BYTES).also { key ->
        if (keyring != null) keyring.store(ACCOUNT, key) else keyFile.store(key)
    }

    private companion object {
        const val ACCOUNT = "history-db"
    }
}
