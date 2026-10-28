package cz.tadeasf.clipvault.security

/** A desktop secret store holding one secret per account name. */
interface Keyring {
    fun load(account: String): ByteArray?
    fun store(account: String, secret: ByteArray)
}
