package cz.tadeasf.clipvault.sync.secure

import cz.tadeasf.clipvault.sync.crypto.Crypto
import cz.tadeasf.clipvault.sync.crypto.constantTimeEquals
import cz.tadeasf.clipvault.sync.crypto.lp

// docs/sync/handshake.md section 3: SIGMA-I style, three messages, no identity in clear.

enum class HandshakeMode(val wire: Byte) {
    SESSION(0x01),
    PAIR_QR(0x02),
    PAIR_CODE(0x03),
    ;

    val pairing: Boolean get() = this != SESSION

    companion object {
        fun of(wire: Byte): HandshakeMode? = entries.find { it.wire == wire }
    }
}

/** Every failure aborts the connection with no message to the peer (section 3.6). */
class HandshakeFailure(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { MALFORMED, VERSION, MODE_NOT_ACCEPTED, LOW_ORDER, NOT_AUTHENTICATED, TIMEOUT, LOCKED_OUT }
}

/** This device's long-term Ed25519 identity (handshake.md section 2). */
class LocalIdentity(val seed: ByteArray, crypto: Crypto) {
    val publicKey: ByteArray = crypto.ed25519PublicKey(seed)
}

/**
 * The outcome of a handshake. [peer] is the authenticated identity in SESSION mode, null in the pairing
 * modes (identities are exchanged afterwards, pairing.md section 3). [h3] is the session id.
 */
class SessionKeys(val mode: HandshakeMode, val sendKey: ByteArray, val receiveKey: ByteArray, val peer: ByteArray?, val h3: ByteArray)

internal const val INIT = 0x01.toByte()
internal const val RESP = 0x02.toByte()
internal const val FIN = 0x03.toByte()
private val MAGIC = "CVHS".encodeToByteArray()
private const val VERSION: Byte = 1
private const val LABEL = "clipvault-hs-v1"
const val MAX_HANDSHAKE_MESSAGE = 256

private fun label(suffix: String) = "$LABEL $suffix".encodeToByteArray()

/** The keys of section 4, all from one DH output and h1. */
private class Derived(c: Crypto, ss: ByteArray, h1: ByteArray) {
    val kAB = c.hkdfSha256(ss, h1, label("key A->B"), 32)
    val kBA = c.hkdfSha256(ss, h1, label("key B->A"), 32)
    val kMacA = c.hkdfSha256(ss, h1, label("mac A"), 32)
    val kMacB = c.hkdfSha256(ss, h1, label("mac B"), 32)
}

private fun transcript1(mode: HandshakeMode, ephA: ByteArray, nonceA: ByteArray, ephB: ByteArray, nonceB: ByteArray, binding: ByteArray) =
    lp(LABEL) + lp(byteArrayOf(mode.wire)) + lp(ephA) + lp(nonceA) + lp(ephB) + lp(nonceB) + lp(binding)

private fun sigMessage(role: String, h: ByteArray) = lp(label("sig $role")) + lp(h)

private fun macMessage(role: String, h: ByteArray, identity: ByteArray) = lp(label("mac $role")) + lp(h) + lp(identity)

private fun fail(kind: HandshakeFailure.Kind, message: String): Nothing = throw HandshakeFailure(kind, message)

/**
 * Section 3.4: the paired peer whose key verifies [signature] and whose identity makes [mac] right.
 * Pairing modes have no signature: the MAC over an empty identity proves knowledge of the binding.
 */
private fun authenticate(
    c: Crypto,
    mode: HandshakeMode,
    role: String,
    h: ByteArray,
    signature: ByteArray,
    mac: ByteArray,
    macKey: ByteArray,
    candidates: List<ByteArray>,
): ByteArray {
    if (mode.pairing) {
        if (!constantTimeEquals(mac, c.hmacSha256(macKey, macMessage(role, h, ByteArray(0))))) fail(HandshakeFailure.Kind.NOT_AUTHENTICATED, "mac $role")
        return ByteArray(0)
    }
    val peer = candidates.firstOrNull { c.ed25519Verify(it, sigMessage(role, h), signature) }
        ?: fail(HandshakeFailure.Kind.NOT_AUTHENTICATED, "no paired key verifies sig $role")
    if (!constantTimeEquals(mac, c.hmacSha256(macKey, macMessage(role, h, peer)))) fail(HandshakeFailure.Kind.NOT_AUTHENTICATED, "mac $role")
    return peer
}

/**
 * Side A, the device that connected. [binding] is empty for SESSION, the QR token or k_pake for pairing.
 * [candidates] are the peer identities that may answer (paired, plus pending revokes). [ephSecret] and
 * [nonce] are injectable for the test vectors only.
 */
class Initiator(
    private val c: Crypto,
    val mode: HandshakeMode,
    private val binding: ByteArray,
    private val me: LocalIdentity?,
    private val candidates: List<ByteArray>,
    private val ephSecret: ByteArray = c.randomBytes(32),
    private val nonce: ByteArray = c.randomBytes(32),
) {
    private val ephPublic = c.x25519PublicKey(ephSecret)

    init {
        require(mode.pairing || me != null) { "SESSION needs an identity" }
    }

    fun init(): ByteArray = byteArrayOf(INIT) + MAGIC + byteArrayOf(VERSION, mode.wire) + ephPublic + nonce

    /** Returns Fin and the session keys, or throws [HandshakeFailure]. */
    fun onResp(resp: ByteArray): Pair<ByteArray, SessionKeys> {
        val sigSize = if (mode.pairing) 0 else 64
        if (resp.size != 1 + 32 + 32 + sigSize + 32 || resp[0] != RESP) fail(HandshakeFailure.Kind.MALFORMED, "resp")
        val ephB = resp.copyOfRange(1, 33)
        val nonceB = resp.copyOfRange(33, 65)
        val sigB = resp.copyOfRange(65, 65 + sigSize)
        val macB = resp.copyOfRange(65 + sigSize, resp.size)

        val t1 = transcript1(mode, ephPublic, nonce, ephB, nonceB, binding)
        val h1 = c.sha256(t1)
        val ss = c.x25519(ephSecret, ephB) ?: fail(HandshakeFailure.Kind.LOW_ORDER, "x25519")
        val keys = Derived(c, ss, h1)
        ephSecret.fill(0)
        ss.fill(0)
        val peer = authenticate(c, mode, "B", h1, sigB, macB, keys.kMacB, candidates)

        val t2 = t1 + lp(sigB) + lp(macB) + lp(peer)
        val h2 = c.sha256(t2)
        val myId = if (mode.pairing) ByteArray(0) else me!!.publicKey
        val sigA = if (mode.pairing) ByteArray(0) else c.ed25519Sign(me!!.seed, sigMessage("A", h2))
        val macA = c.hmacSha256(keys.kMacA, macMessage("A", h2, myId))
        val h3 = c.sha256(t2 + lp(sigA) + lp(macA) + lp(myId))
        return (byteArrayOf(FIN) + sigA + macA) to SessionKeys(mode, keys.kAB, keys.kBA, peer.takeIf { it.isNotEmpty() }, h3)
    }
}

/**
 * Side B, the device that listened. [binding] answers which modes are open right now: the binding for
 * an open pairing window, an empty array for SESSION, null to refuse the mode.
 */
class Responder(
    private val c: Crypto,
    private val me: LocalIdentity?,
    private val candidates: List<ByteArray>,
    private val binding: (HandshakeMode) -> ByteArray?,
    private val ephSecret: ByteArray = c.randomBytes(32),
    private val nonce: ByteArray = c.randomBytes(32),
) {
    var mode: HandshakeMode? = null
        private set
    private var t2: ByteArray? = null
    private var h2: ByteArray? = null
    private var keys: Derived? = null

    fun onInit(init: ByteArray): ByteArray {
        if (init.size != 71 || init[0] != INIT || !init.copyOfRange(1, 5).contentEquals(MAGIC)) fail(HandshakeFailure.Kind.MALFORMED, "init")
        if (init[5] != VERSION) fail(HandshakeFailure.Kind.VERSION, "handshake version ${init[5]}")
        val mode = HandshakeMode.of(init[6]) ?: fail(HandshakeFailure.Kind.MALFORMED, "mode ${init[6]}")
        if (!mode.pairing && me == null) fail(HandshakeFailure.Kind.MODE_NOT_ACCEPTED, "no identity yet")
        val bind = binding(mode) ?: fail(HandshakeFailure.Kind.MODE_NOT_ACCEPTED, "$mode not open")
        this.mode = mode
        val ephA = init.copyOfRange(7, 39)
        val nonceA = init.copyOfRange(39, 71)
        val ephPublic = c.x25519PublicKey(ephSecret)

        val t1 = transcript1(mode, ephA, nonceA, ephPublic, nonce, bind)
        val h1 = c.sha256(t1)
        val ss = c.x25519(ephSecret, ephA) ?: fail(HandshakeFailure.Kind.LOW_ORDER, "x25519")
        val keys = Derived(c, ss, h1).also { keys = it }
        ephSecret.fill(0)
        ss.fill(0)
        val myId = if (mode.pairing) ByteArray(0) else me!!.publicKey
        val sigB = if (mode.pairing) ByteArray(0) else c.ed25519Sign(me!!.seed, sigMessage("B", h1))
        val macB = c.hmacSha256(keys.kMacB, macMessage("B", h1, myId))
        t2 = (t1 + lp(sigB) + lp(macB) + lp(myId)).also { h2 = c.sha256(it) }
        return byteArrayOf(RESP) + ephPublic + nonce + sigB + macB
    }

    fun onFin(fin: ByteArray): SessionKeys {
        val mode = mode ?: fail(HandshakeFailure.Kind.MALFORMED, "fin before init")
        val keys = keys!!
        val sigSize = if (mode.pairing) 0 else 64
        if (fin.size != 1 + sigSize + 32 || fin[0] != FIN) fail(HandshakeFailure.Kind.MALFORMED, "fin")
        val sigA = fin.copyOfRange(1, 1 + sigSize)
        val macA = fin.copyOfRange(1 + sigSize, fin.size)
        val peer = authenticate(c, mode, "A", h2!!, sigA, macA, keys.kMacA, candidates)
        val h3 = c.sha256(t2!! + lp(sigA) + lp(macA) + lp(peer))
        return SessionKeys(mode, keys.kBA, keys.kAB, peer.takeIf { it.isNotEmpty() }, h3)
    }
}
