package cz.tadeasf.clipvault.sync

import cz.tadeasf.clipvault.detect.SensitiveCategory
import cz.tadeasf.clipvault.sync.node.Discovery
import cz.tadeasf.clipvault.sync.node.Link
import cz.tadeasf.clipvault.sync.node.Listener
import cz.tadeasf.clipvault.sync.node.Network
import cz.tadeasf.clipvault.sync.node.NodeTiming
import cz.tadeasf.clipvault.sync.node.PeerStatus
import cz.tadeasf.clipvault.sync.node.SyncNode
import cz.tadeasf.clipvault.sync.node.TcpNetwork
import kotlin.random.Random
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Real TCP on loopback, with switches to black-hole a node's traffic or corrupt one frame it sends. */
class FaultyNetwork(private val inner: Network = TcpNetwork()) : Network {
    @Volatile var blackhole = false

    /** Flips the last byte of the [corruptWrite]-th write from now (1 = the next one). */
    @Volatile var corruptWrite = 0

    private inner class FaultyLink(private val link: Link) : Link by link {
        override suspend fun read(): ByteArray? {
            while (true) {
                val bytes = link.read() ?: return null
                if (!blackhole) return bytes
            }
        }

        override suspend fun write(bytes: ByteArray) {
            if (blackhole) return
            if (corruptWrite > 0 && --corruptWrite == 0) {
                return link.write(bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
            }
            link.write(bytes)
        }
    }

    override suspend fun listen(addresses: List<String>, port: Int): Listener {
        val l = inner.listen(addresses, port)
        return object : Listener by l {
            override suspend fun accept(): Link = FaultyLink(l.accept())
        }
    }

    override suspend fun connect(address: String): Link = FaultyLink(inner.connect(address))
}

/**
 * Running SyncNodes on 127.0.0.1 over [SecureNet]'s devices (encrypted stores, identities). Pairing is
 * done by storing each other's identity (pairing itself is SecurePairingTest's subject). [SecureNet.clock]
 * drives handshake timeouts, heartbeats, session limits and backoff; tests move it on purpose.
 */
class LiveNet(vararg ids: String, private val pageSize: Long = 100) : AutoCloseable {
    val secure = SecureNet(*ids)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val networks = ids.associateWith { FaultyNetwork() }
    val nodes = mutableMapOf<String, SyncNode>()
    private val discoverable = ids.associateWith { mutableSetOf<String>() }
    val logs = java.util.concurrent.ConcurrentLinkedQueue<String>()
    /** Every advertise call: (node, pairing flag). */
    val advertised = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, Boolean>>()

    init {
        ids.forEach { id ->
            secure[id].enable()
            secure[id].device.node.config = secure[id].device.node.config.copy(pageSize = pageSize, sensitiveOptIn = emptySet())
        }
    }

    operator fun get(id: String) = secure.net[id]

    fun pair(a: String, b: String) {
        secure[a].peers.addPeer(secure[b].peers.identity()!!.publicKey, "node $b")
        secure[b].peers.addPeer(secure[a].peers.identity()!!.publicKey, "node $a")
    }

    /** [a] finds [b] through discovery (one direction). */
    fun sees(a: String, b: String) {
        discoverable.getValue(a) += b
    }

    fun start(id: String): SyncNode {
        val discovery = object : Discovery {
            override fun advertise(port: Int, pairing: Boolean) {
                advertised += id to pairing
            }
            override suspend fun candidates() = discoverable.getValue(id).mapNotNull { other -> nodes[other]?.listeningPort?.let { "127.0.0.1:$it" } }
            override fun close() = Unit
        }
        val timing = NodeTiming(tickMillis = 50, dialIntervalMillis = 100, networkPollMillis = 1_000, handshakeWaitMillis = 5_000)
        return SyncNode(secure[id].endpoint, networks.getValue(id), discovery, { listOf("127.0.0.1") }, scope, timing = timing, random = Random(1), log = { logs += "$id: $it" })
            .also { nodes[id] = it; it.start() }
    }

    suspend fun stop(id: String) {
        nodes.remove(id)?.stop()
    }

    suspend fun status(id: String, peer: String): PeerStatus =
        nodes.getValue(id).status().single { it.name == "node $peer" }

    fun setOptIn(id: String, categories: Set<SensitiveCategory>) =
        secure[id].device.node.config.copy(sensitiveOptIn = categories)

    override fun close() {
        scope.cancel()
        secure.close()
    }
}

/** Polls [condition] until true or [millis] pass; fails with [message]. */
suspend fun eventually(millis: Long = 20_000, message: suspend () -> String, condition: suspend () -> Boolean) {
    withTimeoutOrNull(millis) {
        while (!condition()) delay(25)
    } ?: fail(message())
}
