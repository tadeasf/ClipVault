package cz.tadeasf.clipvault.security

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

actual fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).toHex()

internal fun randomBytes(size: Int): ByteArray = ByteArray(size).also(SecureRandom()::nextBytes)

/** AES-256-GCM sealing with the IV prepended. Works with AndroidKeyStore keys, which pick their own IV. */
internal object AesGcm {
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun seal(key: SecretKey, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return cipher.iv + cipher.doFinal(plaintext)
    }

    fun open(key: SecretKey, sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }
}

/** Write-then-rename so a crash never leaves a half-written key file (that would lose the database). */
internal fun File.writeAtomically(bytes: ByteArray) {
    parentFile?.mkdirs()
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeBytes(bytes)
    check(tmp.renameTo(this)) { "Could not move $tmp to $this" }
}
