package cz.tadeasf.clipvault.sync.node

import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Blocking NIO channels on Dispatchers.IO: unlike java.net sockets, a cancelled read is interrupted. */
class TcpNetwork(private val connectTimeoutMillis: Int = 5_000) : Network {

    override suspend fun listen(addresses: List<String>, port: Int): Listener = withContext(Dispatchers.IO) {
        require(addresses.isNotEmpty()) { "nothing to listen on" }
        val first = ServerSocketChannel.open().bind(InetSocketAddress(InetAddress.getByName(addresses.first()), port))
        val bound = (first.localAddress as InetSocketAddress).port
        val rest = addresses.drop(1).mapNotNull { a ->
            runCatching { ServerSocketChannel.open().bind(InetSocketAddress(InetAddress.getByName(a), bound)) }.getOrNull()
        }
        TcpListener(listOf(first) + rest, bound)
    }

    override suspend fun connect(address: String): Link = withContext(Dispatchers.IO) {
        val port = address.substringAfterLast(':').toInt()
        val channel = SocketChannel.open()
        try {
            channel.socket().connect(InetSocketAddress(InetAddress.getByName(hostOf(address)), port), connectTimeoutMillis)
            channel.socket().tcpNoDelay = true
        } catch (e: IOException) {
            channel.close()
            throw e
        }
        TcpLink(channel)
    }
}

private class TcpListener(private val servers: List<ServerSocketChannel>, override val port: Int) : Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accepted = Channel<Link>(Channel.UNLIMITED)

    init {
        servers.forEach { server ->
            scope.launch {
                while (true) {
                    val channel = try {
                        runInterruptible { server.accept() }
                    } catch (_: IOException) {
                        break
                    }
                    channel.socket().tcpNoDelay = true
                    accepted.trySend(TcpLink(channel))
                }
            }
        }
    }

    override suspend fun accept(): Link = accepted.receive()

    override fun close() {
        servers.forEach { runCatching { it.close() } }
        scope.cancel()
        accepted.close()
    }
}

private class TcpLink(private val channel: SocketChannel) : Link {
    override val remote: String = (channel.remoteAddress as InetSocketAddress).let { a ->
        val host = a.address.hostAddress
        if (a.address is Inet6Address) "[$host]:${a.port}" else "$host:${a.port}"
    }
    private val buffer = ByteBuffer.allocate(64 * 1024)
    private val writeLock = Mutex()

    override suspend fun read(): ByteArray? = try {
        runInterruptible(Dispatchers.IO) {
            buffer.clear()
            val n = channel.read(buffer)
            if (n < 0) null else ByteArray(n).also { buffer.flip(); buffer.get(it) }
        }
    } catch (_: IOException) {
        null
    }

    override suspend fun write(bytes: ByteArray) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            val data = ByteBuffer.wrap(bytes)
            try {
                while (data.hasRemaining()) channel.write(data)
            } catch (_: IOException) {
                // the reader sees the closed stream and ends the connection
            }
        }
    }

    override fun close() {
        runCatching { channel.close() }
    }
}
