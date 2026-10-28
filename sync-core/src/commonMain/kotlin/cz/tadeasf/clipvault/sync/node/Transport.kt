package cz.tadeasf.clipvault.sync.node

/** One stream connection. [read] returns null at the end of the stream. */
interface Link {
    /** The peer's address as `host:port`. */
    val remote: String

    suspend fun read(): ByteArray?

    suspend fun write(bytes: ByteArray)

    fun close()
}

interface Listener {
    val port: Int

    suspend fun accept(): Link

    fun close()
}

/** TCP on the real platforms ([TcpNetwork]); tests wrap it to drop or corrupt frames. */
interface Network {
    /** Listens on every address of [addresses] on one port ([port] 0: any free port). */
    suspend fun listen(addresses: List<String>, port: Int = 0): Listener

    suspend fun connect(address: String): Link
}

/** Where paired devices might be. The desktop uses mDNS (JmDNS); tests use fixed lists. */
interface Discovery {
    /**
     * Advertise this node's sync service on [port]; called again after every rebind and whenever a pairing
     * window opens or closes. [pairing]: the TXT record `pairing=1`, which `pair --join` looks for (pairing.md 5).
     */
    fun advertise(port: Int, pairing: Boolean = false)

    /** `host:port` of ClipVault devices on the network right now; identities are learnt in the handshake. */
    suspend fun candidates(): List<String>

    fun close()
}

fun hostOf(address: String): String = address.substringBeforeLast(':').removePrefix("[").removeSuffix("]")

/** `host:port`, with an IPv6 host in brackets. */
fun addressOf(host: String, port: Int): String = if (':' in host) "[$host]:$port" else "$host:$port"
