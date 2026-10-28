package cz.tadeasf.clipvault.sync.secure

import cz.tadeasf.clipvault.sync.crypto.Crypto
import cz.tadeasf.clipvault.sync.crypto.readU32be
import cz.tadeasf.clipvault.sync.crypto.readU64be
import cz.tadeasf.clipvault.sync.crypto.u32be
import cz.tadeasf.clipvault.sync.crypto.u64be

/** The connection must close; nothing is sent to the peer (handshake.md section 5). */
class ChannelClosed(message: String) : Exception(message)

/**
 * Transport frames of handshake.md section 5: `header (12) || XChaCha20-Poly1305(ciphertext || tag)`,
 * one key and one counter per direction. Counters start at 1, must be exactly last + 1 on receive, and
 * the session ends at counter 2^32 or [MAX_AGE_MILLIS] after the handshake.
 */
class SecureChannel internal constructor(
    private val c: Crypto,
    private val sendKey: ByteArray,
    private val receiveKey: ByteArray,
    private val now: () -> Long,
    private val startedAt: Long,
    private var sent: Long,
    private var received: Long,
) {
    constructor(c: Crypto, keys: SessionKeys, now: () -> Long) : this(c, keys.sendKey, keys.receiveKey, now, now(), 0, 0)

    /** The counter the next sealed frame will carry (protocol.md: Envelope.seq must equal it). */
    val nextCounter: Long get() = sent + 1

    val expired: Boolean get() = now() - startedAt >= MAX_AGE_MILLIS

    val ageMillis: Long get() = now() - startedAt

    /** Frames this side may still seal before the 2^32 limit. */
    val sendsLeft: Long get() = MAX_COUNTER - 1 - sent

    fun seal(plaintext: ByteArray): ByteArray {
        val counter = sent + 1
        if (counter >= MAX_COUNTER) throw ChannelClosed("send counter reached 2^32")
        if (expired) throw ChannelClosed("session older than 24 h")
        val header = header(counter)
        sent = counter
        return header + c.xchachaSeal(sendKey, nonce(counter), header, plaintext)
    }

    /** Returns the frame's counter and plaintext. */
    fun open(frame: ByteArray): Pair<Long, ByteArray> {
        if (frame.size < HEADER_SIZE + TAG_SIZE || frame.size > MAX_FRAME) throw ChannelClosed("frame size ${frame.size}")
        if (frame[0] != 'C'.code.toByte() || frame[1] != 'V'.code.toByte() || frame[2] != FRAME_VERSION || frame[3] != 0.toByte()) {
            throw ChannelClosed("bad frame header")
        }
        val counter = readU64be(frame, 4)
        if (counter != received + 1) throw ChannelClosed("counter $counter after $received")
        if (counter >= MAX_COUNTER) throw ChannelClosed("receive counter reached 2^32")
        if (expired) throw ChannelClosed("session older than 24 h")
        val header = frame.copyOfRange(0, HEADER_SIZE)
        val plaintext = c.xchachaOpen(receiveKey, nonce(counter), header, frame.copyOfRange(HEADER_SIZE, frame.size))
            ?: throw ChannelClosed("frame authentication failed")
        received = counter
        return counter to plaintext
    }

    private fun header(counter: Long) = byteArrayOf('C'.code.toByte(), 'V'.code.toByte(), FRAME_VERSION, 0) + u64be(counter)

    private fun nonce(counter: Long) = u64be(counter) + ByteArray(16)

    companion object {
        const val HEADER_SIZE = 12
        const val TAG_SIZE = 16
        private const val FRAME_VERSION: Byte = 1
        const val MAX_COUNTER = 1L shl 32
        const val MAX_AGE_MILLIS = 24L * 60 * 60 * 1000

        /** 2 MiB of plaintext (the 1 MiB payload cap plus overhead) + header + tag. */
        const val MAX_FRAME = 2 * 1024 * 1024 + HEADER_SIZE + TAG_SIZE

        /** Test hook: a channel whose counters already stand at [sent] / [received]. */
        internal fun at(c: Crypto, keys: SessionKeys, now: () -> Long, startedAt: Long, sent: Long, received: Long) =
            SecureChannel(c, keys.sendKey, keys.receiveKey, now, startedAt, sent, received)
    }
}

/**
 * The stream layer: every message is `u32be(len) || body`. Bytes go in as they arrive; whole bodies come
 * out. A length over [max] closes the connection before anything is read.
 */
class LengthFramer(private val max: Int = SecureChannel.MAX_FRAME) {
    private var buffer = ByteArray(0)

    fun push(bytes: ByteArray): List<ByteArray> {
        buffer += bytes
        val bodies = mutableListOf<ByteArray>()
        while (buffer.size >= 4) {
            val size = readU32be(buffer, 0)
            if (size > max) throw ChannelClosed("message of $size bytes")
            if (buffer.size < 4 + size) break
            bodies += buffer.copyOfRange(4, 4 + size.toInt())
            buffer = buffer.copyOfRange(4 + size.toInt(), buffer.size)
        }
        return bodies
    }

    companion object {
        fun frame(body: ByteArray): ByteArray = u32be(body.size.toLong()) + body
    }
}
