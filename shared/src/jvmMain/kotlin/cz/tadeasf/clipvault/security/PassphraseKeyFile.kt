package cz.tadeasf.clipvault.security

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

class WrongPassphraseException : IllegalArgumentException("Wrong passphrase for the ClipVault key file")

/** Argon2id cost. Defaults follow RFC 9106's second recommended option (64 MiB, t=3). */
data class Argon2Cost(val memoryKiB: Int = 64 * 1024, val iterations: Int = 3, val parallelism: Int = 1)

/**
 * Keyring-less fallback: the DB key sealed with AES-GCM under an Argon2id-derived key.
 * Weaker than a keyring (offline guessing of the passphrase), which the UI must say.
 * Layout: MAGIC | memoryKiB | iterations | parallelism | salt(16) | iv+ciphertext+tag.
 */
class PassphraseKeyFile(
    private val file: File,
    private val passphrase: () -> CharArray,
    private val cost: Argon2Cost = Argon2Cost(),
) {
    fun exists(): Boolean = file.exists()

    fun load(): ByteArray {
        val input = DataInputStream(file.inputStream().buffered())
        val header = input.use { it.readHeader() }
        val kek = deriveKey(header.salt, header.cost)
        return try {
            AesGcm.open(kek, header.sealed)
        } catch (_: AEADBadTagException) {
            throw WrongPassphraseException()
        }
    }

    fun store(key: ByteArray) {
        val salt = randomBytes(SALT_BYTES)
        val sealed = AesGcm.seal(deriveKey(salt, cost), key)
        file.writeAtomically(encode(salt, sealed))
        runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
    }

    private fun deriveKey(salt: ByteArray, cost: Argon2Cost): SecretKeySpec {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withMemoryAsKB(cost.memoryKiB)
            .withIterations(cost.iterations)
            .withParallelism(cost.parallelism)
            .build()
        val secret = passphrase()
        val out = ByteArray(DB_KEY_BYTES)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(secret, out)
        secret.fill('\u0000')
        return SecretKeySpec(out, "AES")
    }

    private fun encode(salt: ByteArray, sealed: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use {
            it.writeInt(MAGIC)
            it.writeInt(cost.memoryKiB)
            it.writeInt(cost.iterations)
            it.writeInt(cost.parallelism)
            it.write(salt)
            it.write(sealed)
        }
        return bytes.toByteArray()
    }

    private class Header(val cost: Argon2Cost, val salt: ByteArray, val sealed: ByteArray)

    private fun DataInputStream.readHeader(): Header {
        check(readInt() == MAGIC) { "Not a ClipVault key file: $file" }
        val cost = Argon2Cost(memoryKiB = readInt(), iterations = readInt(), parallelism = readInt())
        val salt = ByteArray(SALT_BYTES).also(::readFully)
        return Header(cost, salt, readBytes())
    }

    private companion object {
        const val MAGIC = 0x43564b31 // "CVK1"
        const val SALT_BYTES = 16
    }
}
