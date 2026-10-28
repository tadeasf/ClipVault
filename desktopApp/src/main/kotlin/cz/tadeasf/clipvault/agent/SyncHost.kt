package cz.tadeasf.clipvault.agent

import cz.tadeasf.clipvault.db.ClipDatabase
import cz.tadeasf.clipvault.settings.deviceId
import cz.tadeasf.clipvault.sync.SyncConfig
import cz.tadeasf.clipvault.sync.SyncEngine
import cz.tadeasf.clipvault.sync.crypto.crypto
import cz.tadeasf.clipvault.sync.db.SyncDatabase
import cz.tadeasf.clipvault.sync.node.Discovery
import cz.tadeasf.clipvault.sync.node.PeerStatus
import cz.tadeasf.clipvault.sync.node.SyncNode
import cz.tadeasf.clipvault.sync.node.TcpNetwork
import cz.tadeasf.clipvault.sync.pairing.Peer
import cz.tadeasf.clipvault.sync.pairing.PairingWindows
import cz.tadeasf.clipvault.sync.pairing.PeerStore
import cz.tadeasf.clipvault.sync.pairing.syncEnabledAt
import cz.tadeasf.clipvault.sync.secure.HandshakeGuard
import cz.tadeasf.clipvault.sync.secure.SecureEndpoint
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.time.Instant
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlinx.coroutines.CoroutineScope

/**
 * The daemon's sync side (step 7). Starts a [SyncNode] once sync is enabled and a peer is paired (`pair`
 * may do both while the daemon runs), and routes local writes through it so they never interleave with
 * applies. Received clips are queued for CopyQ like phone clips.
 */
class SyncHost(private val clips: ClipDatabase, private val sync: SyncDatabase, private val core: AgentCore, private val scope: CoroutineScope) {
    private val c = crypto()
    private val now = System::currentTimeMillis
    val peers = PeerStore(sync, c, now)
    private var node: SyncNode? = null
    private var engine: SyncEngine? = null

    val running: Boolean get() = node != null

    /** Called at start and from housekeeping. */
    fun startIfReady() {
        if (node != null || peers.identity() == null || peers.peers().isEmpty()) return
        val config = SyncConfig(deviceId(clips), syncEnabledAt = syncEnabledAt(clips) ?: now())
        val engine = SyncEngine(clips, sync, config).also { engine = it }
        engine.onRemoteClip = { core.state.enqueue(it) } // flushed to CopyQ by housekeeping
        val endpoint = SecureEndpoint(c, engine, peers, PairingWindows(c, now), HandshakeGuard(now), now, hostname()) { it.answer(false) }
        node = SyncNode(endpoint, TcpNetwork(), JmdnsDiscovery(c), ::siteLocalAddresses, scope, log = ::println).also { it.start() }
    }

    suspend fun <T> write(block: suspend () -> T): T = node?.write(block) ?: block()

    suspend fun status(): List<PeerStatus> = node?.status().orEmpty()

    val port: Int? get() = node?.listeningPort

    /** By exact name or a device id prefix (4+ characters). */
    fun find(nameOrId: String): List<Peer> = peers.peers().filter {
        it.name == nameOrId || (nameOrId.length >= 4 && it.deviceId.startsWith(nameOrId.lowercase()))
    }

    suspend fun unpair(peer: Peer) {
        val node = node
        if (node != null) node.unpair(peer.publicKey) else peers.unpair(peer.publicKey, revokeDelivered = false)
    }
}

/** `status` / `peers` lines; never content, keys or transcript hashes. */
fun peerLines(paired: List<Peer>, live: List<PeerStatus>): List<String> = paired.map { p ->
    val s = live.firstOrNull { it.deviceId == p.deviceId }
    buildString {
        append("peer ${p.name} [${p.deviceId.take(8)}]: ${s?.state?.name?.lowercase()?.replace('_', '-') ?: "not running"}")
        append(", last seen ${Instant.ofEpochMilli(s?.lastSeen ?: p.lastSeen)}")
        if (s != null) {
            append(", cursor ${s.cursor}, frames in/out ${s.framesIn}/${s.framesOut}")
            s.backoffUntil?.let { append(", backoff until ${Instant.ofEpochMilli(it)}") }
            s.lastError?.let { append(", last error $it") }
        }
        (s?.address ?: p.lastAddress)?.let { append(", address $it") }
    }
}

/** Design 5.4: only site-local addresses of interfaces that are up (IPv4 private, IPv6 ULA); never 0.0.0.0. */
fun siteLocalAddresses(): List<String> = NetworkInterface.getNetworkInterfaces().toList()
    .filter { it.isUp && !it.isLoopback && !it.isVirtual }
    .flatMap { it.inetAddresses.toList() }
    .filter { it.isSiteLocalAddress || (it is Inet6Address && (it.address[0].toInt() and 0xfe) == 0xfc) }
    .map { it.hostAddress.substringBefore('%') }
    .sorted()

fun hostname(): String =
    System.getenv("HOSTNAME")?.takeIf(String::isNotBlank) ?: runCatching { File("/etc/hostname").readText().trim() }.getOrNull() ?: "desktop"

/**
 * mDNS for the sync service (design 5.4): a random instance name per start, nothing that identifies the
 * device; identities are learnt in the handshake. One JmDNS per address, kept open, so its cache stays
 * warm between browses (a fresh instance's first browse often finds nothing).
 */
class JmdnsDiscovery(private val c: cz.tadeasf.clipvault.sync.crypto.Crypto) : Discovery {
    private var instances: List<JmDNS> = emptyList()
    private val instance = "cv-" + c.randomBytes(4).toHexString()

    override fun advertise(port: Int) {
        close()
        instances = siteLocalAddresses().mapNotNull { address ->
            runCatching {
                JmDNS.create(InetAddress.getByName(address), "clipvault-sync").apply {
                    registerService(ServiceInfo.create(SERVICE_TYPE, instance, port, 0, 0, mapOf("v" to "1")))
                }
            }.getOrNull()
        }
    }

    override suspend fun candidates(): List<String> = instances.flatMap { mdns ->
        mdns.list(SERVICE_TYPE, BROWSE_MILLIS).filter { it.name != instance }.flatMap { info ->
            info.inetAddresses.map { a -> if (a is Inet6Address) "[${a.hostAddress.substringBefore('%')}]:${info.port}" else "${a.hostAddress}:${info.port}" }
        }
    }.distinct()

    override fun close() {
        instances.forEach { runCatching { it.unregisterAllServices(); it.close() } }
        instances = emptyList()
    }

    companion object {
        const val SERVICE_TYPE = "_clipvault._tcp.local."
        private const val BROWSE_MILLIS = 1_500L
    }
}
