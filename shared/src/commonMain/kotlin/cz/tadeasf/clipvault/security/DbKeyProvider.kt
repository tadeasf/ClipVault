package cz.tadeasf.clipvault.security

const val DB_KEY_BYTES = 32

/** Where the database key lives: Android Keystore, the Linux keyring, or a passphrase-protected file. */
interface DbKeyProvider {
    /** The stored key, or null if this provider has never created one. */
    fun loadKey(): ByteArray?

    /** Generates, stores and returns a new key. */
    fun createKey(): ByteArray
}

class KeyUnavailableException(message: String) : IllegalStateException(message)

/** Never mints a new key for an existing database: that would make it unreadable. */
fun DbKeyProvider.keyFor(databaseExists: Boolean): ByteArray =
    loadKey() ?: if (databaseExists) {
        throw KeyUnavailableException("History database exists but its key is missing")
    } else {
        createKey()
    }

fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

expect fun sha256Hex(text: String): String
