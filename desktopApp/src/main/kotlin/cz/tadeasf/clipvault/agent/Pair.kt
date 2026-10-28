package cz.tadeasf.clipvault.agent

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import cz.tadeasf.clipvault.sync.SyncConfig
import cz.tadeasf.clipvault.sync.SyncNode
import cz.tadeasf.clipvault.sync.crypto.crypto
import cz.tadeasf.clipvault.sync.pairing.PairingWindows
import cz.tadeasf.clipvault.sync.pairing.PeerStore
import cz.tadeasf.clipvault.sync.pairing.enableSync
import cz.tadeasf.clipvault.sync.pairing.syncEnabledAt
import cz.tadeasf.clipvault.sync.secure.ConfirmRequest
import cz.tadeasf.clipvault.sync.secure.HandshakeGuard
import cz.tadeasf.clipvault.sync.secure.LengthFramer
import cz.tadeasf.clipvault.sync.secure.PairBy
import cz.tadeasf.clipvault.sync.secure.SecureConnection
import cz.tadeasf.clipvault.sync.secure.SecureEndpoint
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.PrintStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * `clipvault-agent pair [--code | --join [--addr host:port]] [--listen ADDR] [--name NAME]`
 * (pairing.md section 5). Pairing only, in the terminal; the agent's sync loop is step 7. After pairing,
 * the same connection runs the first sync until it goes quiet, then closes.
 */
class PairCommand(
    private val paths: AgentPaths,
    private val out: PrintStream = System.out,
    private val input: BufferedReader = System.`in`.bufferedReader(),
) {
    private val c = crypto()
    private val now = System::currentTimeMillis

    fun run(args: List<String>): Int {
        val listen = args.valueAfter("--listen")?.let(InetAddress::getByName)
        val name = args.valueAfter("--name") ?: hostname()
        return when {
            "--join" in args -> join(args.valueAfter("--addr"), listen, name)
            else -> show(byCode = "--code" in args, listen, name)
        }
    }

    // --- setup ---

    private class Ctx(val endpoint: SecureEndpoint, val windows: PairingWindows)

    private fun setup(name: String): Ctx {
        val (clips, sync) = openStores(paths)
        val peers = PeerStore(sync!!, c, now)
        val first = peers.identity() == null
        val deviceId = enableSync(clips, peers, c)
        if (first) out.println("Sync enabled on this device (device id $deviceId).")
        val node = SyncNode(clips, sync, SyncConfig(deviceId, syncEnabledAt = syncEnabledAt(clips) ?: now()))
        val windows = PairingWindows(c, now)
        return Ctx(SecureEndpoint(c, node, peers, windows, HandshakeGuard(now), now, name, ::askWords), windows)
    }

    private fun askWords(request: ConfirmRequest) {
        out.println()
        out.println("Pairing with \"${request.peerName}\". Both devices must show the same six words:")
        out.println()
        out.println("    " + request.words.joinToString("  "))
        out.println()
        out.print("Do the words match? [yes/no]: ")
        out.flush()
        request.answer(input.readLine()?.trim()?.lowercase() == "yes")
    }

    // --- responder: QR or code ---

    private fun show(byCode: Boolean, listen: InetAddress?, name: String): Int {
        val addresses = listen?.let(::listOf) ?: siteLocalAddresses()
        if (addresses.isEmpty()) return fail("No site-local network address to listen on (use --listen ADDR).")
        val ctx = setup(name)
        val servers = bind(addresses)
        val port = servers.first().localPort
        var png: File? = null
        var mdns: List<JmDNS> = emptyList()
        try {
            if (byCode) {
                out.println("Pairing code: ${ctx.windows.openCode()}  (valid 2 minutes, one attempt)")
                out.println("On the other device: clipvault-agent pair --join   (or --join --addr ${hostPort(addresses.first(), port)})")
                mdns = advertise(addresses, port)
            } else {
                val payload = ctx.windows.openQr(addresses.take(4).map { it.address }, port)
                val matrix = qr(payload.toText())
                out.println(terminalQr(matrix))
                png = File(paths.runtimeDir, "pair-${now()}.png").also { it.writeBytes(png(matrix)) }
                out.println("Scan this with ClipVault on your phone (valid 2 minutes, one use).")
                out.println("Light terminal background? Open ${png.path} instead.")
            }
            return accept(servers, ctx, byCode)
        } finally {
            mdns.forEach { runCatching { it.unregisterAllServices(); it.close() } }
            servers.forEach { runCatching { it.close() } }
            png?.delete()
        }
    }

    private fun accept(servers: List<ServerSocket>, ctx: Ctx, byCode: Boolean): Int {
        servers.forEach { it.soTimeout = 200 }
        var code = ctx.windows.code
        while (if (byCode) ctx.windows.codeOpen else ctx.windows.qrOpen) {
            val socket = servers.firstNotNullOfOrNull { s -> runCatching { s.accept() }.getOrNull() } ?: continue
            val conn = SecureConnection.accept(ctx.endpoint, socket.inetAddress.hostAddress, sender(socket))
            val paired = drive(conn, socket)
            if (paired) return 0
            out.println("Pairing attempt from ${socket.inetAddress.hostAddress} failed: ${conn.closeReason}")
            if (!byCode) return fail("The QR code is used up. Run `clipvault-agent pair` again.")
            if (ctx.windows.code != code) {
                code = ctx.windows.code
                out.println("New pairing code: $code")
            }
        }
        return fail("Pairing window closed (2 minutes).")
    }

    // --- initiator: join by code ---

    private fun join(addr: String?, listen: InetAddress?, name: String): Int {
        val target = addr?.let(::parseHostPort) ?: discover(listen) ?: return fail(
            "No device showing a pairing code was found on the local network (mDNS, 10 s).\n" +
                "If the other desktop does show a code, mDNS is probably blocked (firewall, UDP 5353): run\n" +
                "  clipvault-agent pair --join --addr HOST:PORT\n" +
                "with the address printed under the code.",
        )
        out.print("Pairing code shown on the other device: ")
        out.flush()
        val code = input.readLine()?.trim().orEmpty()
        if (!code.matches(Regex("\\d{6}"))) return fail("A pairing code is six digits.")
        val ctx = setup(name)
        val socket = Socket().apply { connect(target, 5000) }
        val conn = SecureConnection.dial(ctx.endpoint, target.address.hostAddress, PairBy.Code(code), sender(socket))
        return if (drive(conn, socket)) 0 else fail("Pairing failed: ${conn.closeReason}")
    }

    private fun discover(listen: InetAddress?): InetSocketAddress? {
        val addresses = listen?.let(::listOf) ?: siteLocalAddresses()
        out.println("Looking for devices showing a pairing code...")
        val found = addresses.flatMap { address ->
            JmDNS.create(address, "clipvault-join").use { mdns ->
                // The first list() often returns before anything is resolved (seen on this machine with plain
                // JmDNS: 0 found after 3 s, found on the next call). Rounds keep JmDNS's cache; stop at the first hit.
                var round = emptyList<ServiceInfo>()
                for (attempt in 1..BROWSE_ROUNDS) {
                    round = mdns.list(SERVICE_TYPE, BROWSE_ROUND_MILLIS).filter { it.getPropertyString("pairing") == "1" }
                    if (round.isNotEmpty()) break
                }
                round
            }
        }
            .mapNotNull { info -> info.inetAddresses.firstOrNull()?.let { InetSocketAddress(it, info.port) } }
            .distinct()
        if (found.isEmpty()) return null
        found.forEachIndexed { i, target -> out.println("  ${i + 1}) ${hostPort(target.address, target.port)}") }
        if (found.size == 1) return found.single()
        out.print("Which one? [1-${found.size}]: ")
        out.flush()
        return input.readLine()?.trim()?.toIntOrNull()?.let { found.getOrNull(it - 1) }
    }

    // --- one connection, blocking ---

    private fun sender(socket: Socket): (ByteArray) -> Unit {
        val stream = socket.getOutputStream()
        return { body -> runCatching { stream.write(LengthFramer.frame(body)); stream.flush() } }
    }

    /** Runs [conn] until it closes. True if pairing completed; the first sync then runs until quiet. */
    private fun drive(conn: SecureConnection, socket: Socket): Boolean {
        socket.soTimeout = 200
        val framer = LengthFramer()
        val buffer = ByteArray(64 * 1024)
        var lastTraffic = now()
        var paired = false
        socket.use {
            while (!conn.closed) {
                val n = try {
                    socket.getInputStream().read(buffer)
                } catch (_: SocketTimeoutException) {
                    0
                } catch (_: java.io.IOException) {
                    -1
                }
                if (n < 0) {
                    conn.transportClosed()
                    break
                }
                if (n > 0) {
                    lastTraffic = now()
                    runCatching { framer.push(buffer.copyOf(n)) }.getOrElse { conn.transportClosed(); emptyList() }.forEach(conn::receive)
                }
                if (conn.state == SecureConnection.State.SYNC && !paired) {
                    paired = true
                    out.println("Paired. Running the first sync...")
                }
                if (paired && now() - lastTraffic > QUIET_MILLIS) conn.finish("first sync done")
                conn.tick()
            }
        }
        if (paired) out.println("Done. Paired devices: clipvault-agent status")
        return paired
    }

    // --- network helpers ---

    private fun bind(addresses: List<InetAddress>): List<ServerSocket> {
        val first = ServerSocket(0, 8, addresses.first())
        return listOf(first) + addresses.drop(1).mapNotNull { runCatching { ServerSocket(first.localPort, 8, it) }.getOrNull() }
    }

    /** Design 5.4: only site-local addresses of interfaces that are up; never 0.0.0.0. */
    private fun siteLocalAddresses(): List<InetAddress> = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.toList() }
        .filter { it.isSiteLocalAddress || (it is Inet6Address && (it.address[0].toInt() and 0xfe) == 0xfc) }

    private fun advertise(addresses: List<InetAddress>, port: Int): List<JmDNS> = addresses.mapNotNull { address ->
        runCatching {
            JmDNS.create(address, "clipvault-pair").apply {
                // A random instance name per window (design 5.4): nothing stable to track.
                val instance = "cv-" + c.randomBytes(4).toHexString()
                registerService(ServiceInfo.create(SERVICE_TYPE, instance, port, 0, 0, mapOf("pairing" to "1", "v" to "1")))
            }
        }.getOrNull()
    }

    private fun parseHostPort(text: String): InetSocketAddress? {
        val at = text.lastIndexOf(':').takeIf { it > 0 } ?: return null
        val port = text.substring(at + 1).toIntOrNull() ?: return null
        return InetSocketAddress(text.substring(0, at).removePrefix("[").removeSuffix("]"), port)
    }

    private fun hostPort(address: InetAddress, port: Int) =
        if (address is Inet6Address) "[${address.hostAddress}]:$port" else "${address.hostAddress}:$port"

    private fun hostname(): String =
        System.getenv("HOSTNAME")?.takeIf(String::isNotBlank) ?: runCatching { File("/etc/hostname").readText().trim() }.getOrNull() ?: "desktop"

    private fun List<String>.valueAfter(flag: String) = indexOf(flag).takeIf { it >= 0 }?.let { getOrNull(it + 1) }

    private fun fail(message: String): Int {
        System.err.println(message)
        return 1
    }

    companion object {
        const val SERVICE_TYPE = "_clipvault._tcp.local."
        private const val BROWSE_ROUNDS = 5
        private const val BROWSE_ROUND_MILLIS = 2000L
        private const val QUIET_MILLIS = 2000L

        fun qr(text: String): BitMatrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2),
        )

        /**
         * Two modules per character with half blocks. Light modules are drawn, dark ones left blank, which
         * reads correctly on the usual dark terminal background.
         */
        fun terminalQr(m: BitMatrix): String = buildString {
            for (y in 0 until m.height step 2) {
                for (x in 0 until m.width) {
                    val top = !m[x, y]
                    val bottom = y + 1 < m.height && !m[x, y + 1]
                    append(if (top && bottom) '█' else if (top) '▀' else if (bottom) '▄' else ' ')
                }
                append('\n')
            }
        }

        /** An 8-bit greyscale PNG, [scale] pixels per module, written by hand (no java.desktop module). */
        fun png(m: BitMatrix, scale: Int = 8): ByteArray {
            val (w, h) = m.width * scale to m.height * scale
            val raw = ByteArrayOutputStream()
            for (y in 0 until h) {
                raw.write(0) // filter: none
                for (x in 0 until w) raw.write(if (m[x / scale, y / scale]) 0 else 255)
            }
            val idat = ByteArrayOutputStream().also { DeflaterOutputStream(it).use { z -> z.write(raw.toByteArray()) } }.toByteArray()
            val ihdr = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(w); writeInt(h); write(byteArrayOf(8, 0, 0, 0, 0)) } }.toByteArray()
            return ByteArrayOutputStream().apply {
                write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
                chunk("IHDR", ihdr)
                chunk("IDAT", idat)
                chunk("IEND", ByteArray(0))
            }.toByteArray()
        }

        private fun ByteArrayOutputStream.chunk(type: String, data: ByteArray) {
            val typed = type.encodeToByteArray() + data
            DataOutputStream(this).apply {
                writeInt(data.size)
                write(typed)
                writeInt(CRC32().apply { update(typed) }.value.toInt())
            }
        }
    }
}
