package cz.tadeasf.clipvault.agent

import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/**
 * CLI <-> daemon over a Unix socket in the 0700 runtime dir. Request: one header line
 * (`COMMAND key=base64 ...`), then the raw payload until the client half-closes. Response: text.
 */
data class AgentRequest(val command: String, val params: Map<String, String>, val payload: ByteArray) {

    fun encode(): ByteArray {
        val header = (listOf(command) + params.map { (k, v) -> "$k=" + Base64.getEncoder().encodeToString(v.encodeToByteArray()) })
            .joinToString(" ")
        return (header + "\n").encodeToByteArray() + payload
    }

    companion object {
        fun decode(bytes: ByteArray): AgentRequest {
            val newline = bytes.indexOf('\n'.code.toByte()).let { if (it < 0) bytes.size else it }
            val parts = bytes.copyOfRange(0, newline).decodeToString().split(' ').filter(String::isNotEmpty)
            val params = parts.drop(1).associate {
                it.substringBefore('=') to Base64.getDecoder().decode(it.substringAfter('=')).decodeToString()
            }
            val payload = if (newline < bytes.size) bytes.copyOfRange(newline + 1, bytes.size) else ByteArray(0)
            return AgentRequest(parts.firstOrNull().orEmpty(), params, payload)
        }
    }
}

/** Sends one request; null when no daemon is listening. */
fun sendToDaemon(socket: Path, request: AgentRequest): String? = try {
    SocketChannel.open(UnixDomainSocketAddress.of(socket)).use { channel ->
        channel.write(ByteBuffer.wrap(request.encode()))
        channel.shutdownOutput()
        Channels.newInputStream(channel).readBytes().decodeToString()
    }
} catch (_: IOException) {
    null
}

/** Accepts connections until [scope] is cancelled; each request is answered by [handle]. */
fun serve(socket: Path, scope: CoroutineScope, handle: suspend (AgentRequest) -> String): Job {
    Files.deleteIfExists(socket) // stale socket from a crashed run
    val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(socket))
    return scope.launch(Dispatchers.IO) {
        try {
            while (isActive) {
                // accept() ignores coroutine cancellation; runInterruptible interrupts the thread, which
                // closes this InterruptibleChannel and makes accept() throw.
                val channel = runInterruptible { server.accept() }
                launch { respond(channel, handle) }
            }
        } catch (_: ClosedChannelException) {
            // cancelled
        } finally {
            server.close()
            Files.deleteIfExists(socket)
        }
    }
}

private suspend fun respond(channel: SocketChannel, handle: suspend (AgentRequest) -> String) = channel.use {
    val request = AgentRequest.decode(Channels.newInputStream(channel).readBytes())
    val response = runCatching { handle(request) }.getOrElse { "error: ${it.message}" }
    channel.write(ByteBuffer.wrap(response.encodeToByteArray()))
}
