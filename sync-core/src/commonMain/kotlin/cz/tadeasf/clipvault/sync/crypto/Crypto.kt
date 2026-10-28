package cz.tadeasf.clipvault.sync.crypto

/**
 * The primitives of docs/sync/handshake.md section 2 and the PAKE of pairing.md section 2. The only
 * implementation is BouncyCastle's lightweight API ([crypto], jvmAndAndroidMain); keeping it behind an
 * interface keeps the handshake itself in commonMain.
 */
interface Crypto {
    fun randomBytes(size: Int): ByteArray

    fun x25519PublicKey(secret: ByteArray): ByteArray

    /** Null when the agreement fails or is all zero (a low-order point): the handshake aborts. */
    fun x25519(secret: ByteArray, peerPublic: ByteArray): ByteArray?

    /** [seed] is the 32-byte Ed25519 private key (RFC 8032). */
    fun ed25519PublicKey(seed: ByteArray): ByteArray

    fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray

    /** False for a bad signature and for a malformed key or signature. */
    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean

    fun sha256(data: ByteArray): ByteArray

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray

    /** XChaCha20-Poly1305 with a caller-chosen 24-byte nonce; returns ciphertext || 16-byte tag. */
    fun xchachaSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray

    /** Null when authentication fails. */
    fun xchachaOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray?

    /** One side of finite-field J-PAKE over NIST_3072 with SHA-256 (pairing.md section 2). */
    fun jpake(participantId: String, code: CharArray): Jpake
}

/** pairing.md section 2.3. Every receive throws [CryptoFailure] when the peer's payload doesn't validate. */
interface Jpake {
    fun round1(): ByteArray

    fun receiveRound1(payload: ByteArray)

    fun round2(): ByteArray

    fun receiveRound2(payload: ByteArray)

    /** The keying material as 384-byte unsigned big-endian (`km`). */
    fun keyingMaterial(): ByteArray
}

class CryptoFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The BouncyCastle implementation. */
expect fun crypto(): Crypto

/** `u32be(len(x)) || x` (handshake.md section 3.1). */
fun lp(data: ByteArray): ByteArray = u32be(data.size.toLong()) + data

fun lp(text: String): ByteArray = lp(text.encodeToByteArray())

fun u32be(value: Long): ByteArray = ByteArray(4) { (value ushr (24 - 8 * it)).toByte() }

fun u64be(value: Long): ByteArray = ByteArray(8) { (value ushr (56 - 8 * it)).toByte() }

fun readU64be(bytes: ByteArray, offset: Int): Long =
    (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (bytes[offset + i].toLong() and 0xff) }

fun readU32be(bytes: ByteArray, offset: Int): Long =
    (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (bytes[offset + i].toLong() and 0xff) }

/** Comparison time doesn't depend on where the arrays differ (MAC checks). */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}
