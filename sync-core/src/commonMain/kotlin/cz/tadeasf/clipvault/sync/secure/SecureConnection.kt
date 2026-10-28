package cz.tadeasf.clipvault.sync.secure

import cz.tadeasf.clipvault.sync.SyncNode
import cz.tadeasf.clipvault.sync.SyncSession
import cz.tadeasf.clipvault.sync.crypto.Crypto
import cz.tadeasf.clipvault.sync.crypto.CryptoFailure
import cz.tadeasf.clipvault.sync.crypto.Jpake
import cz.tadeasf.clipvault.sync.crypto.lp
import cz.tadeasf.clipvault.sync.pairing.PairingWindows
import cz.tadeasf.clipvault.sync.pairing.PeerStore
import cz.tadeasf.clipvault.sync.pairing.deviceIdOf
import cz.tadeasf.clipvault.sync.pairing.fingerprintWords
import cz.tadeasf.clipvault.sync.pairing.sanitizeName
import cz.tadeasf.clipvault.sync.wire.Bye
import cz.tadeasf.clipvault.sync.wire.Decoded
import cz.tadeasf.clipvault.sync.wire.Identity
import cz.tadeasf.clipvault.sync.wire.Message
import cz.tadeasf.clipvault.sync.wire.PairConfirm
import cz.tadeasf.clipvault.sync.wire.Revoke
import cz.tadeasf.clipvault.sync.wire.WireCodec
import kotlinx.serialization.SerializationException

/** Asks this device's user whether the fingerprint words match; [answer] may be called later. */
class ConfirmRequest(val words: List<String>, val peerName: String, val answer: (Boolean) -> Unit)

/** What a device brings to every secure connection. */
class SecureEndpoint(
    val crypto: Crypto,
    val node: SyncNode,
    val peers: PeerStore,
    val windows: PairingWindows,
    val guard: HandshakeGuard,
    val now: () -> Long,
    /** This device's name, sent in the identity exchange. */
    val name: String,
    /** Shows the six words; the UI (or a test) answers. */
    val confirm: (ConfirmRequest) -> Unit,
)

/** How an initiator pairs: by the QR token, or by the 6-digit code. */
sealed interface PairBy {
    class Qr(val token: ByteArray) : PairBy

    class Code(val code: String) : PairBy
}

/**
 * One connection end to end (handshake.md, pairing.md): optional J-PAKE rounds, the handshake, then
 * sealed frames. In a pairing mode the identity exchange and both confirmations come first; a sync frame
 * before that aborts. Then a [SyncSession] runs over the channel. A pending-revoke peer only gets Revoke.
 *
 * Transport-agnostic: [receive] takes one message body (the stream's `u32be(len)` framing is the
 * transport's, see [LengthFramer]) and [send] gets one. Any failure closes without a word to the peer;
 * the transport then drops the connection. Not thread-safe: run each connection on the node's dispatcher.
 */
class SecureConnection private constructor(
    private val e: SecureEndpoint,
    /** The peer's address, for backoff and lockouts. */
    val remote: String,
    private val send: (ByteArray) -> Unit,
    private val initiating: Boolean,
) {
    enum class State { PAKE, HANDSHAKE, PAIRING, SYNC, CLOSED }

    var state = State.HANDSHAKE
        private set
    var closeReason: String? = null
        private set
    var failure: HandshakeFailure.Kind? = null
        private set

    /** The authenticated (SESSION) or paired (after pairing) identity of the peer. */
    var peerKey: ByteArray? = null
        private set
    var syncSession: SyncSession? = null
        private set

    private val c: Crypto = e.crypto
    private val startedAt = e.now()
    private var lastMessageAt = startedAt
    private var mode: HandshakeMode? = null
    private var initiator: Initiator? = null
    private var responder: Responder? = null
    private var jpake: Jpake? = null
    private var pakeKey: ByteArray? = null
    private var channel: SecureChannel? = null
    private var h3: ByteArray? = null

    // Pairing phase (pairing.md sections 1.4 and 3).
    private var pairingStartedAt = 0L
    private var peerName: String? = null
    private var localConfirmed = false
    private var peerConfirmed = false

    val closed: Boolean get() = state == State.CLOSED

    // --- initiator ---

    private fun dial(mode: HandshakeMode, pairBy: PairBy?) {
        this.mode = mode
        val me = e.peers.identity()
        if (me == null) return abort(HandshakeFailure.Kind.MODE_NOT_ACCEPTED, "sync is not enabled on this device")
        when (pairBy) {
            is PairBy.Code -> {
                state = State.PAKE
                jpake = c.jpake(PAKE_A, pairBy.code.toCharArray())
                send(byteArrayOf(PAKE_R1) + PAKE_MAGIC + byteArrayOf(PAKE_VERSION) + jpake!!.round1())
            }
            is PairBy.Qr -> startInitiator(pairBy.token, me)
            null -> startInitiator(ByteArray(0), me)
        }
    }

    private fun startInitiator(binding: ByteArray, me: LocalIdentity) {
        state = State.HANDSHAKE
        initiator = Initiator(c, mode!!, binding, me, e.peers.candidates()).also { send(it.init()) }
    }

    // --- both ---

    fun receive(body: ByteArray) {
        if (closed) return
        lastMessageAt = e.now()
        try {
            when (state) {
                State.PAKE -> pake(body)
                State.HANDSHAKE -> handshake(body)
                State.PAIRING, State.SYNC -> frame(body)
                State.CLOSED -> Unit
            }
        } catch (f: HandshakeFailure) {
            abort(f.kind, f.message ?: f.kind.name)
        } catch (f: CryptoFailure) {
            abort(HandshakeFailure.Kind.NOT_AUTHENTICATED, f.message ?: "J-PAKE")
        } catch (f: ChannelClosed) {
            close(f.message ?: "channel closed")
        }
    }

    /** The transport saw the peer go away. An unfinished pairing counts as a failed attempt. */
    fun transportClosed() {
        if (closed) return
        if (state != State.SYNC) abort(HandshakeFailure.Kind.NOT_AUTHENTICATED, "peer closed during ${state.name.lowercase()}") else close("peer closed")
    }

    /** Timeouts (handshake.md 3.6, pairing.md 1.4) and the 24 h session limit (section 5). */
    fun tick() {
        if (closed) return
        val now = e.now()
        when (state) {
            State.PAKE, State.HANDSHAKE ->
                if (now - lastMessageAt > MESSAGE_TIMEOUT || now - startedAt > HANDSHAKE_TIMEOUT) abort(HandshakeFailure.Kind.TIMEOUT, "handshake timed out")
            State.PAIRING -> if (now - pairingStartedAt > PairingWindows.TTL_MILLIS) close("pairing not confirmed in time")
            State.SYNC -> if (channel!!.expired) close("session limit (24 h): reconnect")
            State.CLOSED -> Unit
        }
    }

    /** Unpairs the peer over this live connection: Revoke, then close (pairing.md section 4). */
    fun revoke() {
        val peer = peerKey ?: return
        if (state != State.SYNC) return
        sendMessage(Revoke)
        e.peers.unpair(peer, revokeDelivered = true)
        close("unpaired")
    }

    private fun pake(body: ByteArray) {
        if (body.size > MAX_PAKE_MESSAGE || body.isEmpty()) throw HandshakeFailure(HandshakeFailure.Kind.MALFORMED, "pake size")
        val jpake = jpake!!
        when {
            initiating && body[0] == PAKE_R1 -> {
                jpake.receiveRound1(body.copyOfRange(1, body.size))
                send(byteArrayOf(PAKE_R2) + jpake.round2())
            }
            initiating && body[0] == PAKE_R2 -> {
                jpake.receiveRound2(body.copyOfRange(1, body.size))
                startInitiator(pakeBinding(jpake), e.peers.identity()!!)
            }
            !initiating && body[0] == PAKE_R2 -> {
                val mine = jpake.round2()
                jpake.receiveRound2(body.copyOfRange(1, body.size))
                send(byteArrayOf(PAKE_R2) + mine)
                pakeKey = pakeBinding(jpake)
                state = State.HANDSHAKE
            }
            else -> throw HandshakeFailure(HandshakeFailure.Kind.MALFORMED, "unexpected pake message ${body[0]}")
        }
    }

    private fun pakeBinding(jpake: Jpake): ByteArray = jpake.keyingMaterial().let { km ->
        c.hkdfSha256(km, ByteArray(0), "clipvault-pake-v1".encodeToByteArray(), 32).also { km.fill(0) }
    }

    private fun handshake(body: ByteArray) {
        // A J-PAKE opener (about 1.6 KB) is recognised before the 256-byte handshake limit applies.
        if (!initiating && responder == null && body.isNotEmpty() && body[0] == PAKE_R1) {
            if (body.size > MAX_PAKE_MESSAGE) throw HandshakeFailure(HandshakeFailure.Kind.MALFORMED, "pake size")
            return startPakeResponder(body)
        }
        if (body.size > MAX_HANDSHAKE_MESSAGE) throw HandshakeFailure(HandshakeFailure.Kind.MALFORMED, "handshake message too large")
        if (initiating) {
            val (fin, keys) = initiator!!.onResp(body)
            send(fin)
            established(keys)
            return
        }
        val responder = responder
        if (responder == null) {
            this.responder = Responder(c, e.peers.identity(), e.peers.candidates(), ::bindingFor).also { send(it.onInit(body)) }
            mode = this.responder!!.mode
            return
        }
        if (mode == HandshakeMode.PAIR_QR && !e.windows.consumeToken()) {
            throw HandshakeFailure(HandshakeFailure.Kind.MODE_NOT_ACCEPTED, "QR token already used")
        }
        established(responder.onFin(body))
    }

    /** Responder: which modes are open right now, and their binding. */
    private fun bindingFor(mode: HandshakeMode): ByteArray? = when (mode) {
        HandshakeMode.SESSION -> ByteArray(0)
        HandshakeMode.PAIR_QR -> e.windows.peekToken()
        HandshakeMode.PAIR_CODE -> pakeKey
    }

    private fun startPakeResponder(body: ByteArray) {
        if (body.size < 6 || !body.copyOfRange(1, 5).contentEquals(PAKE_MAGIC) || body[5] != PAKE_VERSION) {
            throw HandshakeFailure(HandshakeFailure.Kind.MALFORMED, "pake hello")
        }
        val code = e.windows.code?.takeIf { e.windows.codeOpen } ?: throw HandshakeFailure(HandshakeFailure.Kind.MODE_NOT_ACCEPTED, "no code shown")
        if (!e.guard.mayPairByCode(remote)) throw HandshakeFailure(HandshakeFailure.Kind.LOCKED_OUT, "too many code failures from $remote")
        mode = HandshakeMode.PAIR_CODE
        state = State.PAKE
        val jpake = c.jpake(PAKE_B, code.toCharArray()).also { jpake = it }
        val mine = jpake.round1()
        jpake.receiveRound1(body.copyOfRange(6, body.size))
        send(byteArrayOf(PAKE_R1) + mine)
    }

    private fun established(keys: SessionKeys) {
        channel = SecureChannel(c, keys, e.now)
        h3 = keys.h3
        if (initiating) e.guard.dialSucceeded(remote)
        if (!keys.mode.pairing) {
            val peer = keys.peer!!
            peerKey = peer
            if (e.peers.isPendingRevoke(peer)) {
                sendMessage(Revoke)
                e.peers.revokeDelivered(peer)
                return close("revoke delivered")
            }
            e.peers.seen(peer)
            return startSync(peer)
        }
        state = State.PAIRING
        pairingStartedAt = e.now()
        val me = e.peers.identity()!!
        val name = e.name
        val sig = c.ed25519Sign(me.seed, lp("clipvault-id-v1") + lp(keys.h3) + lp(name.encodeToByteArray()))
        sendMessage(Identity(me.publicKey, name, sig))
    }

    private fun frame(body: ByteArray) {
        val (counter, plaintext) = channel!!.open(body)
        val decoded = try {
            WireCodec.decode(plaintext)
        } catch (_: SerializationException) {
            return close("malformed envelope")
        }
        if (decoded.seq != counter) return close("envelope seq ${decoded.seq} is not frame counter $counter")
        when (decoded) {
            is Decoded.Refused -> {
                sendMessage(Bye("unsupported major ${decoded.major}"))
                close("peer speaks major ${decoded.major}")
            }
            is Decoded.Ignored -> Unit
            is Decoded.Ok -> message(decoded.message)
        }
    }

    private fun message(message: Message) {
        if (message is Revoke) {
            peerKey?.let { e.peers.revokedBy(it) }
            return close("revoked by peer")
        }
        if (state == State.SYNC) {
            val session = syncSession!!
            session.receive(message)
            if (session.closed) close(session.closeReason ?: "sync session ended")
            return
        }
        // PAIRING: only identity, pair_confirm and bye until both sides confirmed (handshake.md 3.5).
        when (message) {
            is Identity -> identity(message)
            is PairConfirm -> {
                if (peerKey == null) return close("pair_confirm before identity")
                peerConfirmed = true
                completePairing()
            }
            is Bye -> close("peer: ${message.reason}")
            else -> close("${WireCodec.typeOf(message)} before pairing completed")
        }
    }

    private fun identity(message: Identity) {
        if (peerKey != null) return close("second identity")
        val me = e.peers.identity()!!
        val signed = lp("clipvault-id-v1") + lp(h3!!) + lp(message.name.encodeToByteArray())
        if (message.idPub.size != 32 || message.idPub.contentEquals(me.publicKey) || !c.ed25519Verify(message.idPub, signed, message.sig)) {
            return abort(HandshakeFailure.Kind.NOT_AUTHENTICATED, "identity signature")
        }
        peerKey = message.idPub
        val name = sanitizeName(message.name).also { peerName = it }
        e.confirm(ConfirmRequest(fingerprintWords(c, me.publicKey, message.idPub), name) { yes -> answered(yes) })
    }

    private fun answered(yes: Boolean) {
        if (closed || state != State.PAIRING || localConfirmed) return
        if (!yes) {
            sendMessage(Bye("fingerprint words rejected"))
            return close("words rejected")
        }
        localConfirmed = true
        sendMessage(PairConfirm)
        completePairing()
    }

    private fun completePairing() {
        if (!localConfirmed || !peerConfirmed) return
        val peer = peerKey!!
        e.peers.addPeer(peer, peerName!!)
        if (mode == HandshakeMode.PAIR_CODE && !initiating) e.windows.closeCode()
        startSync(peer)
    }

    private fun startSync(peer: ByteArray) {
        state = State.SYNC
        syncSession = e.node.connect(deviceIdOf(c, peer)) { sendMessage(it) }
    }

    private fun sendMessage(message: Message) {
        val channel = channel ?: return
        if (closed) return
        try {
            send(channel.seal(WireCodec.encode(message, channel.nextCounter)))
        } catch (f: ChannelClosed) {
            close(f.message ?: "channel closed")
        }
    }

    private fun abort(kind: HandshakeFailure.Kind, reason: String) {
        if (closed) return
        failure = kind
        if (kind != HandshakeFailure.Kind.LOCKED_OUT) {
            if (initiating) {
                e.guard.dialFailed(remote)
            } else {
                e.guard.handshakeFailed(remote)
                if (mode == HandshakeMode.PAIR_CODE) {
                    e.guard.codeFailed(remote)
                    e.windows.replaceCode()
                }
            }
        }
        close(reason)
    }

    private fun close(reason: String) {
        if (closed) return
        state = State.CLOSED
        closeReason = reason
        syncSession?.close()
    }

    companion object {
        /** Dials a peer: SESSION when [pairBy] is null, else the pairing mode it names. */
        fun dial(endpoint: SecureEndpoint, remote: String, pairBy: PairBy? = null, send: (ByteArray) -> Unit): SecureConnection =
            SecureConnection(endpoint, remote, send, initiating = true).apply {
                dial(
                    when (pairBy) {
                        null -> HandshakeMode.SESSION
                        is PairBy.Qr -> HandshakeMode.PAIR_QR
                        is PairBy.Code -> HandshakeMode.PAIR_CODE
                    },
                    pairBy,
                )
            }

        /** Accepts a connection; refused at once while [remote] is locked out (never an established one). */
        fun accept(endpoint: SecureEndpoint, remote: String, send: (ByteArray) -> Unit): SecureConnection =
            SecureConnection(endpoint, remote, send, initiating = false).apply {
                if (!endpoint.guard.mayHandshake(remote)) abort(HandshakeFailure.Kind.LOCKED_OUT, "$remote is locked out")
            }

        const val MESSAGE_TIMEOUT = 10_000L
        const val HANDSHAKE_TIMEOUT = 30_000L
        const val MAX_PAKE_MESSAGE = 4096
        private const val PAKE_R1: Byte = 0x10
        private const val PAKE_R2: Byte = 0x11
        private val PAKE_MAGIC = "CVPK".encodeToByteArray()
        private const val PAKE_VERSION: Byte = 1
        private const val PAKE_A = "clipvault-A"
        private const val PAKE_B = "clipvault-B"
    }
}
