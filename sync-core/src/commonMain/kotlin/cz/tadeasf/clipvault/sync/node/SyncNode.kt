package cz.tadeasf.clipvault.sync.node

import cz.tadeasf.clipvault.sync.SyncConfig
import cz.tadeasf.clipvault.sync.SyncEngine
import cz.tadeasf.clipvault.sync.pairing.Peer
import cz.tadeasf.clipvault.sync.pairing.PeerStore
import cz.tadeasf.clipvault.sync.pairing.deviceIdOf
import cz.tadeasf.clipvault.sync.secure.ChannelClosed
import cz.tadeasf.clipvault.sync.secure.LengthFramer
import cz.tadeasf.clipvault.sync.secure.PairBy
import cz.tadeasf.clipvault.sync.secure.SecureConnection
import cz.tadeasf.clipvault.sync.secure.SecureEndpoint
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class PeerState { DISCOVERING, CONNECTING, HANDSHAKE, CATCHING_UP, LIVE, BACKOFF }

/** One row of `clipvault-agent status` / `peers`. Never content, keys or transcript hashes. */
data class PeerStatus(
    val deviceId: String,
    val name: String,
    val state: PeerState,
    val lastSeen: Long,
    val cursor: Long,
    val backoffUntil: Long?,
    val framesIn: Long,
    val framesOut: Long,
    val lastError: String?,
    val address: String?,
    /** Events received on the current connection (batches and pushes, duplicates included). */
    val eventsReceived: Long = 0,
    /** Whether this side dialled the current connection; null when not connected. */
    val initiatedHere: Boolean? = null,
)

/** A pairing connection ended (step 8): [peerName] once paired, otherwise why it failed. */
class PairingEnd(val peerName: String?, val reason: String?)

/** Timing knobs; tests shorten them. */
data class NodeTiming(
    val tickMillis: Long = 1_000,
    val dialIntervalMillis: Long = 2_000,
    val networkPollMillis: Long = 10_000,
    val handshakeWaitMillis: Long = 15_000,
)

/**
 * A running sync node (step 7): the listener, the peer table and one [SecureConnection] + sync session per
 * paired peer. Every state change, apply and send runs on [dispatcher], one task at a time; socket reads
 * and writes suspend onto I/O threads. Local writes enter through [write] so they never interleave with
 * applies.
 *
 * Connect policy: dial paired peers that are not connected (mDNS candidates plus each peer's last-known
 * address), accept paired peers inbound. If both sides connect at once, the connection whose initiator
 * has the lexically smaller device id survives on both sides.
 */
class SyncNode(
    private val endpoint: SecureEndpoint,
    private val network: Network,
    private val discovery: Discovery,
    /** The addresses to listen on, polled every [NodeTiming.networkPollMillis] (design.md 5.4: site-local only). */
    private val listenAddresses: () -> List<String>,
    parent: CoroutineScope,
    private val port: Int = 0,
    private val timing: NodeTiming = NodeTiming(),
    private val random: Random = Random.Default,
    private val log: (String) -> Unit = {},
    val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    private val engine: SyncEngine get() = endpoint.engine
    private val peers: PeerStore get() = endpoint.peers
    private val now: () -> Long get() = endpoint.now
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job + dispatcher)

    private inner class PeerSession(var peer: Peer) {
        var dialState = PeerState.DISCOVERING
        var failures = 0
        var successes = 0
        var backoffUntil = 0L
        var lastError: String? = null

        val live: Conn? get() = connections.firstOrNull { it.peerId == peer.deviceId && it.established && !it.secure.closed }

        fun failed(error: String) {
            failures++
            lastError = error
            val base = minOf(MAX_BACKOFF_MILLIS, 1000L shl minOf(failures - 1, 20))
            backoffUntil = now() + (base * (0.8 + 0.4 * random.nextDouble())).toLong() // ±20 % jitter
            dialState = PeerState.BACKOFF
            log("peer ${peer.name}: $error, retry in ${(backoffUntil - now()) / 1000} s")
        }

        fun succeeded() {
            successes++
            failures = 0
            backoffUntil = 0
            dialState = PeerState.DISCOVERING
        }
    }

    private inner class Conn(val link: Link, val outbound: Boolean) {
        val outbox = Channel<ByteArray>(Channel.UNLIMITED)
        lateinit var secure: SecureConnection
        var peerId: String? = null
        var established = false
        var ended = false
        var duplicate = false
    }

    private val sessions = mutableMapOf<String, PeerSession>()
    private val connections = mutableListOf<Conn>()
    private val addressOwner = mutableMapOf<String, String>() // dialled address -> device id, to skip live peers
    private var listener: Listener? = null
    private var bound: List<String> = emptyList()
    private var boundPort: Int? = null
    private var advertisedPairing = false

    // listen() suspends: without this, the start-up bind and a network callback's bind both open a listener.
    private val rebinding = Mutex()

    /** Called with the port after every (re)bind; hosts persist it and pass it back as `port` on the next start. */
    var onListening: ((Int) -> Unit)? = null

    /** Called on the node's dispatcher when a pairing connection (either role) pairs or fails. */
    var onPairingEnded: ((PairingEnd) -> Unit)? = null

    /** Total connections ever opened (simultaneous-connect and flapping tests). */
    var connectionsOpened = 0
        private set

    val listeningPort: Int? get() = boundPort

    fun start() {
        scope.launch {
            refreshPeers()
            rebindIfChanged()
            launch { loop(timing.tickMillis) { tick() } }
            launch { loop(timing.dialIntervalMillis) { dialRound() } }
            launch { loop(timing.networkPollMillis) { rebindIfChanged() } }
        }
    }

    /** Stops at once: connections are dropped, not closed politely (a crash looks the same to peers). */
    suspend fun stop() {
        withContext(dispatcher) {
            listener?.close()
            connections.toList().forEach { it.link.close() }
        }
        job.cancelAndJoin()
        discovery.close()
    }

    /** Runs a local change on the node's dispatcher, then pushes it to live peers. */
    suspend fun <T> write(block: suspend () -> T): T = withContext(dispatcher) { block().also { engine.changed() } }

    suspend fun status(): List<PeerStatus> = withContext(dispatcher) {
        refreshPeers()
        sessions.values.map { s ->
            val conn = s.live ?: connections.firstOrNull { it.peerId == s.peer.deviceId && !it.ended }
            val state = when {
                s.live != null -> if (s.live!!.secure.syncSession?.caughtUp == true) PeerState.LIVE else PeerState.CATCHING_UP
                s.backoffUntil > now() -> PeerState.BACKOFF
                else -> s.dialState
            }
            PeerStatus(
                deviceId = s.peer.deviceId,
                name = s.peer.name,
                state = state,
                lastSeen = s.peer.lastSeen,
                cursor = engine.cursor(s.peer.deviceId).first,
                backoffUntil = s.backoffUntil.takeIf { it > now() },
                framesIn = conn?.secure?.framesIn ?: 0,
                framesOut = conn?.secure?.framesOut ?: 0,
                lastError = s.lastError,
                address = s.peer.lastAddress,
                eventsReceived = s.live?.secure?.syncSession?.eventsReceived ?: 0,
                initiatedHere = s.live?.secure?.initiatedHere,
            )
        }
    }

    /** New settings (opt-ins, syncEnabledAt...): live sessions end with Bye and reconnect at once. */
    suspend fun reconfigure(config: SyncConfig) = withContext(dispatcher) {
        engine.config = config.copy(listenPort = boundPort)
        // progress() at once: a dial round must not see the closing connection as still busy.
        connections.filter { it.established }.toList().forEach { it.secure.finish("settings changed"); progress(it) }
        sessions.values.forEach { it.backoffUntil = 0 }
    }

    /**
     * Runs [block] on the node's dispatcher, then lets connections move on. For whatever touches the endpoint
     * from outside: pairing windows, the answer to the fingerprint words.
     */
    suspend fun <T> run(block: () -> T): T = withContext(dispatcher) {
        block().also { connections.toList().forEach(::progress) }
    }

    /** Dials [address] in a pairing mode; the outcome arrives through [onPairingEnded]. False if the connect fails. */
    suspend fun pair(address: String, by: PairBy): Boolean = withContext(dispatcher) {
        val link = try {
            network.connect(address)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("sync: pairing connect to $address failed (${e::class.simpleName})")
            return@withContext false
        }
        startConnection(link, outbound = true, pairBy = by)
        true
    }

    /**
     * The device's network changed (Android's NetworkCallback): rebind now rather than at the next poll. If the
     * addresses changed, open connections ran over the old network, so they are dropped. Either way every
     * peer is dialled again without waiting out its backoff.
     */
    suspend fun networkChanged() = withContext(dispatcher) {
        val before = bound
        rebindIfChanged()
        if (bound != before) connections.toList().forEach { it.link.close() }
        sessions.values.forEach { it.backoffUntil = 0 }
    }

    /** Asks the peer a clip came from for its content (over-cap clips); false when that peer isn't live. */
    suspend fun fetch(clipRowId: String): Boolean = withContext(dispatcher) {
        engine.originsOf(clipRowId).firstNotNullOfOrNull { (memberId, peerId) ->
            sessions[peerId]?.live?.secure?.syncSession?.let { it.fetch(memberId); true }
        } ?: false
    }

    /** Unpairs: Revoke over a live connection, otherwise a pending revoke (pairing.md section 4). */
    suspend fun unpair(publicKey: ByteArray) = withContext(dispatcher) {
        val id = deviceIdOf(endpoint.crypto, publicKey)
        val live = sessions[id]?.live
        if (live != null) {
            live.secure.revoke()
            progress(live)
        } else {
            peers.unpair(publicKey, revokeDelivered = false)
        }
        sessions.remove(id)
        Unit
    }

    // --- loops ---

    private suspend fun loop(every: Long, body: suspend () -> Unit) {
        while (true) {
            try {
                body()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("sync node: ${e::class.simpleName}") // never a message that could carry content
            }
            delay(every)
        }
    }

    private fun tick() {
        // pairing.md 5: `pairing=1` in the TXT record exactly while a code is shown.
        val pairing = endpoint.windows.codeOpen
        if (pairing != advertisedPairing) boundPort?.let {
            advertisedPairing = pairing
            discovery.advertise(it, pairing)
        }
        connections.toList().forEach {
            it.secure.tick()
            progress(it)
        }
    }

    private fun refreshPeers() {
        val paired = peers.peers().associateBy { it.deviceId }
        sessions.keys.retainAll(paired.keys)
        paired.forEach { (id, peer) -> sessions.getOrPut(id) { PeerSession(peer) }.peer = peer }
    }

    private suspend fun rebindIfChanged(): Unit = rebinding.withLock {
        val addresses = listenAddresses()
        if (addresses == bound && listener != null) return@withLock
        listener?.close()
        listener = null
        bound = addresses
        if (addresses.isEmpty()) return@withLock
        val l = try {
            network.listen(addresses, boundPort ?: port)
        } catch (_: Exception) {
            network.listen(addresses, 0)
        }
        listener = l
        boundPort = l.port
        engine.config = engine.config.copy(listenPort = l.port)
        onListening?.invoke(l.port)
        discovery.advertise(l.port, advertisedPairing)
        log("sync: listening on port ${l.port} (${addresses.size} address(es))")
        scope.launch {
            while (true) {
                val link = try {
                    l.accept()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    break // closed by a rebind
                }
                startConnection(link, outbound = false)
            }
        }
    }

    private suspend fun dialRound() {
        refreshPeers()
        val waiting = sessions.values.filter { it.live == null && now() >= it.backoffUntil }
        if (waiting.isEmpty()) return
        waiting.forEach { it.dialState = PeerState.DISCOVERING }
        val connectedBefore = waiting.associateWith { it.successes }
        val own = bound.map { "$it:$boundPort" }.toSet()
        val livePeers = sessions.values.filter { it.live != null }.map { it.peer.deviceId }.toSet()
        val candidates = (runCatching { discovery.candidates() }.getOrDefault(emptyList()) + waiting.mapNotNull { it.peer.lastAddress })
            .distinct()
            .filterNot { it in own || addressOwner[it] in livePeers || connections.any { c -> c.outbound && c.link.remote == it && !c.ended } }
        if (candidates.isEmpty()) log("sync: no address to try for ${waiting.size} peer(s)")
        for (address in candidates) {
            if (waiting.all { it.live != null }) break
            waiting.filter { it.live == null }.forEach { it.dialState = PeerState.CONNECTING }
            val link = try {
                network.connect(address)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("sync: connect to $address failed (${e::class.simpleName})")
                continue
            }
            waiting.filter { it.live == null }.forEach { it.dialState = PeerState.HANDSHAKE }
            val conn = startConnection(link, outbound = true)
            withTimeoutOrNull(timing.handshakeWaitMillis) { while (!conn.established && !conn.ended) delay(20) }
        }
        // A peer that connected during this round and already ended (settings change, limit) is not a failure.
        waiting.filter { it.live == null && it.successes == connectedBefore[it] && now() >= it.backoffUntil }
            .forEach { it.failed(it.lastError ?: "not reachable") }
    }

    // --- connections ---

    private fun startConnection(link: Link, outbound: Boolean, pairBy: PairBy? = null): Conn {
        val conn = Conn(link, outbound)
        connectionsOpened++
        connections += conn
        conn.secure = if (outbound) {
            SecureConnection.dial(endpoint, link.remote, pairBy) { conn.outbox.trySend(it) }
        } else {
            SecureConnection.accept(endpoint, hostOf(link.remote)) { conn.outbox.trySend(it) }
        }
        scope.launch {
            for (body in conn.outbox) link.write(LengthFramer.frame(body))
            link.close() // after the last frame (Bye) went out
        }
        scope.launch {
            val framer = LengthFramer()
            while (!conn.secure.closed) {
                val chunk = link.read() ?: break
                val bodies = try {
                    framer.push(chunk)
                } catch (_: ChannelClosed) {
                    break
                }
                bodies.forEach(conn.secure::receive)
                progress(conn)
            }
            conn.secure.transportClosed()
            progress(conn)
        }
        progress(conn)
        return conn
    }

    private fun progress(conn: Conn) {
        if (!conn.established && conn.secure.state == SecureConnection.State.SYNC) established(conn)
        if (conn.secure.closed && !conn.ended) ended(conn)
    }

    private fun established(conn: Conn) {
        conn.established = true
        val key = conn.secure.peerKey!!
        val id = deviceIdOf(endpoint.crypto, key)
        conn.peerId = id
        if (conn.secure.pairing) onPairingEnded?.invoke(PairingEnd(peers.peer(key)?.name ?: "unnamed device", null))
        refreshPeers()
        peers.seen(key)
        if (conn.outbound) {
            peers.reachedAt(key, conn.link.remote)
            addressOwner[conn.link.remote] = id
        }
        val others = connections.filter { it !== conn && it.peerId == id && it.established && !it.secure.closed }
        for (other in others) {
            val (mine, theirs) = initiator(conn) to initiator(other)
            val loser = if (mine < theirs) other else conn // same initiator: keep the older one
            loser.duplicate = true
            loser.secure.finish("duplicate connection")
            if (loser === conn) return
            progress(loser)
        }
        sessions[id]?.let {
            it.succeeded()
            log("peer ${it.peer.name}: connected (${if (conn.outbound) "outbound" else "inbound"})")
        }
    }

    private fun initiator(conn: Conn): String = if (conn.secure.initiatedHere) engine.config.deviceId else conn.peerId!!

    private fun ended(conn: Conn) {
        conn.ended = true
        connections -= conn
        conn.outbox.close()
        if (!conn.established && conn.secure.pairing) onPairingEnded?.invoke(PairingEnd(null, conn.secure.closeReason ?: "connection closed"))
        if (!conn.established) log("sync: ${if (conn.outbound) "outbound" else "inbound"} handshake with ${hostOf(conn.link.remote)} ended: ${conn.secure.failure ?: conn.secure.closeReason}")
        val session = conn.peerId?.let(sessions::get) ?: return
        conn.secure.peerKey?.let(peers::seen)
        if (conn.duplicate || session.live != null) return
        val reason = conn.secure.closeReason.orEmpty()
        val failure = conn.secure.failure?.name?.lowercase()
        if (conn.established && (reason in CLEAN_REASONS || reason.startsWith("peer: "))) {
            session.dialState = PeerState.DISCOVERING // reconnect at once
            log("peer ${session.peer.name}: session ended ($reason), reconnecting")
        } else {
            session.failed(failure ?: reason.substringBefore(':').take(40).ifEmpty { "connection lost" })
        }
    }

    companion object {
        const val MAX_BACKOFF_MILLIS = 5L * 60 * 1000
        private val CLEAN_REASONS = setOf("session limit: reconnect", "settings changed", "duplicate connection", "sync session ended", "peer closed")
    }
}
