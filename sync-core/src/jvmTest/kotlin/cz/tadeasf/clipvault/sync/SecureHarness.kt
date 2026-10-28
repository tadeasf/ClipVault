package cz.tadeasf.clipvault.sync

import cz.tadeasf.clipvault.sync.crypto.crypto
import cz.tadeasf.clipvault.sync.pairing.PairingWindows
import cz.tadeasf.clipvault.sync.pairing.PeerStore
import cz.tadeasf.clipvault.sync.pairing.enableSync
import cz.tadeasf.clipvault.sync.secure.ConfirmRequest
import cz.tadeasf.clipvault.sync.secure.HandshakeGuard
import cz.tadeasf.clipvault.sync.secure.LengthFramer
import cz.tadeasf.clipvault.sync.secure.PairBy
import cz.tadeasf.clipvault.sync.secure.SecureConnection
import cz.tadeasf.clipvault.sync.secure.SecureEndpoint

/**
 * The step 5 three-device harness with the real security layer: every connection runs J-PAKE (when
 * pairing by code), the handshake and sealed frames, and every byte crosses the `u32be(len)` stream
 * framing. [clock] is the endpoints' time, so timeouts and the 24 h limit can be simulated.
 */
class SecureNet(vararg ids: String) : AutoCloseable {
    val c = crypto()
    var clock = 1_000_000_000L
    val net = Net(*ids)

    /** What each device's user answers to the fingerprint words; records what was shown. */
    val answers = ids.associateWith { true }.toMutableMap()
    val shownWords = mutableMapOf<String, List<String>>()

    inner class Side(val device: Device) {
        val peers = PeerStore(device.syncDb, c) { clock }
        val windows = PairingWindows(c) { clock }
        val guard = HandshakeGuard { clock }
        val endpoint = SecureEndpoint(c, device.node, peers, windows, guard, { clock }, "Device ${device.id}") { request: ConfirmRequest ->
            shownWords[device.id] = request.words
            request.answer(answers.getValue(device.id))
        }

        /** Turns sync on: identity, derived device id, local relabel (design.md 5.3.1). */
        fun enable(): String = enableSync(device.db, peers, c).also { id ->
            device.deviceId = id
            device.node.config = device.node.config.copy(deviceId = id)
        }
    }

    val sides = net.devices.mapValues { Side(it.value) }

    operator fun get(id: String) = sides.getValue(id)

    /** One TCP connection: [from] dialled [to]. Queues hold raw stream bytes. */
    inner class Link(val from: String, val to: String, fromAddress: String, toAddress: String, pairBy: PairBy?) {
        val toAcceptor = ArrayDeque<ByteArray>()
        val toDialer = ArrayDeque<ByteArray>()
        private val dialerFramer = LengthFramer()
        private val acceptorFramer = LengthFramer()

        // accept() first: the dialler sends its first message immediately.
        val acceptor: SecureConnection = SecureConnection.accept(this@SecureNet[to].endpoint, fromAddress) { toDialer.addLast(LengthFramer.frame(it)) }
        val dialer: SecureConnection = SecureConnection.dial(this@SecureNet[from].endpoint, toAddress, pairBy) { toAcceptor.addLast(LengthFramer.frame(it)) }

        private var notified = false

        fun deliverOne(): Boolean {
            when {
                toAcceptor.isNotEmpty() && !acceptor.closed -> acceptorFramer.push(toAcceptor.removeFirst()).forEach(acceptor::receive)
                toDialer.isNotEmpty() && !dialer.closed -> dialerFramer.push(toDialer.removeFirst()).forEach(dialer::receive)
                else -> return hangUpIfClosed()
            }
            return true
        }

        /** TCP: once one side closed and its last bytes are read, the other side sees the close. */
        private fun hangUpIfClosed(): Boolean {
            if (notified || (!dialer.closed && !acceptor.closed)) return false
            notified = true
            toAcceptor.clear()
            toDialer.clear()
            dialer.transportClosed()
            acceptor.transportClosed()
            return true
        }

        val established: Boolean get() = dialer.state == SecureConnection.State.SYNC && acceptor.state == SecureConnection.State.SYNC
    }

    val links = mutableListOf<Link>()

    fun dial(from: String, to: String, pairBy: PairBy? = null, fromAddress: String = "addr-$from"): Link =
        Link(from, to, fromAddress, "addr-$to", pairBy).also { links += it }

    fun drain() {
        var steps = 0
        while (links.any { it.deliverOne() }) check(++steps < 200_000) { "no quiescence" }
    }

    fun tick() = links.forEach { it.dialer.tick(); it.acceptor.tick() }

    override fun close() = net.close()
}
