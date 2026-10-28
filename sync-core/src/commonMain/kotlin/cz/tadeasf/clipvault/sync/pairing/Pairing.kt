package cz.tadeasf.clipvault.sync.pairing

import cz.tadeasf.clipvault.sync.crypto.Crypto
import cz.tadeasf.clipvault.sync.crypto.lp
import kotlin.io.encoding.Base64
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.cbor.CborLabel

/** design.md 5.3.1: hex of the first 16 bytes of H(lp("clipvault-devid-v1") || lp(idPub)). */
fun deviceIdOf(c: Crypto, identityPublicKey: ByteArray): String =
    c.sha256(lp("clipvault-devid-v1") + lp(identityPublicKey)).copyOf(16).toHexString()

/** pairing.md section 1.4: six BIP-39 words from both identities, the same on both devices. */
fun fingerprintWords(c: Crypto, a: ByteArray, b: ByteArray): List<String> {
    val (lo, hi) = if (compareUnsigned(a, b) <= 0) a to b else b to a
    val fp = c.sha256(lp("clipvault-fp-v1") + lp(lo) + lp(hi))
    return (0 until 6).map { k ->
        val index = (11 * k until 11 * k + 11).fold(0) { acc, bit -> (acc shl 1) or ((fp[bit / 8].toInt() shr (7 - bit % 8)) and 1) }
        BIP39_ENGLISH[index]
    }
}

private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
        if (d != 0) return d
    }
    return a.size - b.size
}

/**
 * pairing.md section 3: a peer's name is untrusted. Strips control characters and bidi controls, trims,
 * caps at 64 UTF-8 bytes on a code point boundary; empty becomes "unnamed device".
 */
fun sanitizeName(raw: String): String {
    val kept = buildString {
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAtCompat(i)
            if (!isStripped(cp)) appendCodePointCompat(cp)
            i += if (cp > 0xFFFF) 2 else 1
        }
    }.trim()
    val out = StringBuilder()
    var bytes = 0
    var i = 0
    while (i < kept.length) {
        val cp = kept.codePointAtCompat(i)
        val size = when {
            cp < 0x80 -> 1
            cp < 0x800 -> 2
            cp < 0x10000 -> 3
            else -> 4
        }
        if (bytes + size > MAX_NAME_BYTES) break
        out.appendCodePointCompat(cp)
        bytes += size
        i += if (cp > 0xFFFF) 2 else 1
    }
    return out.toString().trim().ifEmpty { "unnamed device" }
}

const val MAX_NAME_BYTES = 64

private fun isStripped(cp: Int) =
    cp < 0x20 || cp in 0x7F..0x9F || cp == 0x2028 || cp == 0x2029 ||
        cp == 0x061C || cp == 0x200E || cp == 0x200F || cp in 0x202A..0x202E || cp in 0x2066..0x2069

private fun String.codePointAtCompat(i: Int): Int {
    val high = this[i]
    if (high.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
        return 0x10000 + ((high.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00)
    }
    return high.code
}

private fun StringBuilder.appendCodePointCompat(cp: Int) {
    if (cp < 0x10000) {
        append(cp.toChar())
    } else {
        val v = cp - 0x10000
        append((0xD800 + (v shr 10)).toChar())
        append((0xDC00 + (v and 0x3FF)).toChar())
    }
}

/** pairing.md section 1.2. Integer keys, definite lengths: one IPv4 address encodes to 38 bytes. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
class QrPayload(
    @CborLabel(1) val version: Int = 1,
    @CborLabel(2) val addresses: List<ByteArray>,
    @CborLabel(3) val port: Int,
    @CborLabel(4) val token: ByteArray,
    @CborLabel(5) val expiresAt: Long,
) {
    fun encode(): ByteArray = cbor.encodeToByteArray(serializer(), this)

    fun toText(): String = PREFIX + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(encode())

    companion object {
        const val PREFIX = "CVP1:"

        @OptIn(ExperimentalSerializationApi::class)
        private val cbor = Cbor {
            preferCborLabelsOverNames = true
            encodeDefaults = true // version is on the wire even at its default (pairing.md 1.2)
            alwaysUseByteString = true
            useDefiniteLengthEncoding = true
            ignoreUnknownKeys = true
        }

        /** Null for anything that isn't a version-1 ClipVault pairing QR. */
        fun parse(text: String): QrPayload? = runCatching {
            require(text.startsWith(PREFIX))
            val bytes = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(text.removePrefix(PREFIX))
            cbor.decodeFromByteArray(serializer(), bytes).takeIf {
                it.version == 1 && it.token.size == 16 && it.addresses.size in 1..4 &&
                    it.addresses.all { a -> a.size == 4 || a.size == 16 } && it.port in 1..65535
            }
        }.getOrNull()
    }
}

/**
 * The responder's pairing windows (pairing.md sections 1.1 and 2.2). One QR token and one code at a time,
 * each valid for [TTL_MILLIS]. The token is single use; a failed code attempt replaces the code.
 */
class PairingWindows(private val c: Crypto, private val now: () -> Long) {
    private var token: ByteArray? = null
    private var tokenExpires = 0L
    var code: String? = null
        private set
    private var codeExpires = 0L

    fun openQr(addresses: List<ByteArray>, port: Int): QrPayload {
        token = c.randomBytes(16)
        tokenExpires = now() + TTL_MILLIS
        return QrPayload(addresses = addresses, port = port, token = token!!, expiresAt = tokenExpires / 1000)
    }

    fun openCode(): String {
        code = newCode()
        codeExpires = now() + TTL_MILLIS
        return code!!
    }

    val qrOpen: Boolean get() = token != null && now() < tokenExpires

    val codeOpen: Boolean get() = code != null && now() < codeExpires

    /** The token while the window is open; the handshake binds it at Init. */
    fun peekToken(): ByteArray? = token?.takeIf { qrOpen }

    /**
     * Uses the token up at the Fin check, whether that check then passes or fails (pairing.md 1.1).
     * False if it was already used or expired.
     */
    fun consumeToken(): Boolean = (peekToken() != null).also { token = null }

    /** After a failed attempt: a fresh code, a fresh 2 minutes. */
    fun replaceCode(): String = openCode()

    fun closeCode() {
        code = null
    }

    private fun newCode(): String {
        // Rejection sampling keeps the six digits uniform.
        while (true) {
            val r = c.randomBytes(4).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
            if (r < 4_294_000_000L) return (r % 1_000_000).toString().padStart(6, '0')
        }
    }

    companion object {
        const val TTL_MILLIS = 2L * 60 * 1000
    }
}
