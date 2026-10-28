package cz.tadeasf.clipvault.sync

import cz.tadeasf.clipvault.sync.pairing.QrPayload
import cz.tadeasf.clipvault.sync.pairing.deviceIdOf
import cz.tadeasf.clipvault.sync.secure.HandshakeFailure
import cz.tadeasf.clipvault.sync.secure.Initiator
import cz.tadeasf.clipvault.sync.secure.HandshakeMode
import cz.tadeasf.clipvault.sync.secure.PairBy
import cz.tadeasf.clipvault.sync.secure.SecureChannel
import cz.tadeasf.clipvault.sync.secure.SecureConnection
import cz.tadeasf.clipvault.sync.secure.LengthFramer
import cz.tadeasf.clipvault.sync.wire.Hello
import cz.tadeasf.clipvault.sync.wire.WireCodec
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** pairing.md and handshake.md end to end, on the secure three-device harness. */
class SecurePairingTest {

    private fun SecureNet.pairByQr(scanner: String, shower: String): SecureNet.Link {
        val qr = QrPayload.parse(this[shower].windows.openQr(listOf(byteArrayOf(10, 0, 0, 2)), 47321).toText())!!
        return dial(scanner, shower, PairBy.Qr(qr.token)).also { drain() }
    }

    private fun SecureNet.paired(a: String, b: String) =
        this[a].peers.peers().map { it.deviceId } == listOf(deviceIdOf(c, this[b].peers.identity()!!.publicKey))

    @Test
    fun qrPairingThenSyncOnTheSameConnection() = runBlocking<Unit> {
        SecureNet("A", "B").use { net ->
            net["A"].enable()
            net["B"].enable()
            val link = net.pairByQr("A", "B")
            assertTrue(link.established, "dialer ${link.dialer.closeReason} / acceptor ${link.acceptor.closeReason}")
            assertTrue(net.paired("A", "B") && net.paired("B", "A"))
            assertEquals(net.shownWords["A"], net.shownWords["B"], "both users see the same six words")
            assertEquals(6, net.shownWords.getValue("A").size)
            assertEquals(listOf("Device B"), net["A"].peers.peers().map { it.name })

            net.net["A"].copy("over the secure channel")
            net.drain()
            assertEquals(setOf("over the secure channel"), net.net["B"].texts())
        }
    }

    @Test
    fun aQrTokenWorksOnce() = runBlocking<Unit> {
        SecureNet("A", "B", "C").use { net ->
            listOf("A", "B", "C").forEach { net[it].enable() }
            val token = QrPayload.parse(net["B"].windows.openQr(listOf(byteArrayOf(10, 0, 0, 2)), 47321).toText())!!.token
            assertTrue(net.dial("A", "B", PairBy.Qr(token)).also { net.drain() }.established)
            val again = net.dial("C", "B", PairBy.Qr(token)).also { net.drain() }
            assertTrue(again.dialer.closed && again.acceptor.closed)
            assertEquals(HandshakeFailure.Kind.MODE_NOT_ACCEPTED, again.acceptor.failure)
            assertEquals(1, net["B"].peers.peers().size)
        }
    }

    @Test
    fun aWrongCodeFailsAndIssuesANewCodeThenTheRightOnePairs() = runBlocking<Unit> {
        SecureNet("A", "B").use { net ->
            net["A"].enable()
            net["B"].enable()
            val first = net["B"].windows.openCode()
            val wrong = net.dial("A", "B", PairBy.Code(if (first == "000000") "000001" else "000000")).also { net.drain() }
            assertTrue(wrong.dialer.closed && wrong.acceptor.closed)
            assertEquals(HandshakeFailure.Kind.NOT_AUTHENTICATED, wrong.dialer.failure, "A finds out at Resp: the keys differ")
            val second = net["B"].windows.code!!
            assertNotEquals(first, second, "a failed attempt replaces the code")
            assertTrue(net["B"].peers.peers().isEmpty())

            assertTrue(net.dial("A", "B", PairBy.Code(second)).also { net.drain() }.established)
            assertTrue(net.paired("A", "B") && net.paired("B", "A"))
            assertNull(net["B"].windows.code, "the code window closes after pairing")
        }
    }

    @Test
    fun threeWrongCodesFromOneAddressLockItOut() = runBlocking<Unit> {
        SecureNet("A", "B", "C").use { net ->
            listOf("A", "B", "C").forEach { net[it].enable() }
            net["B"].windows.openCode()
            repeat(3) {
                val code = net["B"].windows.code!!
                net.dial("A", "B", PairBy.Code(if (code == "999999") "999998" else "999999"), fromAddress = "10.0.0.66")
                net.drain()
            }
            val right = net.dial("A", "B", PairBy.Code(net["B"].windows.code!!), fromAddress = "10.0.0.66").also { net.drain() }
            assertEquals(HandshakeFailure.Kind.LOCKED_OUT, right.acceptor.failure)
            assertTrue(net["B"].peers.peers().isEmpty())
            // Another address isn't affected.
            assertTrue(net.dial("C", "B", PairBy.Code(net["B"].windows.code!!), fromAddress = "10.0.0.67").also { net.drain() }.established)
        }
    }

    @Test
    fun revokeOverALiveConnectionUnpairsBothSides() = runBlocking<Unit> {
        SecureNet("A", "B").use { net ->
            net["A"].enable()
            net["B"].enable()
            val link = net.pairByQr("A", "B")
            link.dialer.revoke()
            net.drain()
            assertTrue(net["A"].peers.peers().isEmpty() && net["B"].peers.peers().isEmpty())
            assertTrue(net["A"].peers.pendingRevokes().isEmpty(), "delivered, nothing pending")
            assertEquals("revoked by peer", link.acceptor.closeReason)

            val again = net.dial("A", "B").also { net.drain() }
            assertTrue(again.dialer.closed && again.acceptor.closed, "no longer paired: the handshake fails")
            assertEquals(HandshakeFailure.Kind.NOT_AUTHENTICATED, again.acceptor.failure)
        }
    }

    @Test
    fun aPendingRevokeConnectionOnlyDeliversRevoke() = runBlocking<Unit> {
        SecureNet("A", "B").use { net ->
            net["A"].enable()
            net["B"].enable()
            net.pairByQr("A", "B")
            net.links.clear() // both go offline
            val bKey = net["B"].peers.identity()!!.publicKey
            net["A"].peers.unpair(bKey, revokeDelivered = false)
            assertEquals(1, net["A"].peers.pendingRevokes().size)

            net.net["A"].copy("must not reach B")
            val link = net.dial("B", "A").also { net.drain() }
            assertEquals("revoke delivered", link.acceptor.closeReason)
            assertNull(link.acceptor.syncSession, "no sync on a revoke-only connection")
            assertEquals("revoked by peer", link.dialer.closeReason)
            assertTrue(net["B"].peers.peers().isEmpty())
            assertTrue(net["A"].peers.pendingRevokes().isEmpty())
            assertFalse("must not reach B" in net.net["B"].texts())
        }
    }

    @Test
    fun aSyncFrameBeforeTheIdentityExchangeAborts() = runBlocking<Unit> {
        SecureNet("A", "B").use { net ->
            net["B"].enable()
            val token = net["B"].windows.openQr(listOf(byteArrayOf(10, 0, 0, 2)), 47321).token
            val toRogue = ArrayDeque<ByteArray>()
            val acceptor = SecureConnection.accept(net["B"].endpoint, "rogue") { toRogue.addLast(it) }
            // A hand-rolled initiator that completes the handshake, then sends Hello instead of Identity.
            val rogue = Initiator(net.c, HandshakeMode.PAIR_QR, token, null, emptyList())
            acceptor.receive(rogue.init())
            val (fin, keys) = rogue.onResp(toRogue.removeFirst())
            acceptor.receive(fin)
            val channel = SecureChannel(net.c, keys) { net.clock }
            acceptor.receive(channel.seal(WireCodec.encode(Hello("rogue"), channel.nextCounter)))
            assertTrue(acceptor.closed)
            assertContains(acceptor.closeReason!!, "hello before pairing completed")
            assertTrue(net["B"].peers.peers().isEmpty())
        }
    }

    @Test
    fun aDroppedFrameClosesTheConnection() = runBlocking<Unit> {
        SecureNet("A", "B").use { net ->
            net["A"].enable()
            net["B"].enable()
            val link = net.pairByQr("A", "B")
            net.net["A"].copy("one")
            net.net["A"].copy("two")
            assertTrue(link.toAcceptor.size >= 2)
            link.toAcceptor.removeFirst() // lost on the way: the next counter is a gap
            net.drain()
            assertContains(link.acceptor.closeReason!!, "counter")
        }
    }

    @Test
    fun aTamperedTagIsRejected() = runBlocking<Unit> {
        SecureNet("A", "B").use { net ->
            net["A"].enable()
            net["B"].enable()
            val link = net.pairByQr("A", "B")
            net.net["A"].copy("tamper with me")
            val frame = link.toAcceptor.removeFirst()
            frame[frame.size - 1] = (frame[frame.size - 1].toInt() xor 1).toByte()
            link.toAcceptor.addFirst(frame)
            net.drain()
            assertEquals("frame authentication failed", link.acceptor.closeReason)
            assertFalse("tamper with me" in net.net["B"].texts())
        }
    }

    @Test
    fun sessionsEndAfterTwentyFourHoursAndHandshakesTimeOut() = runBlocking<Unit> {
        SecureNet("A", "B").use { net ->
            net["A"].enable()
            net["B"].enable()
            val link = net.pairByQr("A", "B")
            net.clock += SecureChannel.MAX_AGE_MILLIS
            net.tick()
            assertEquals("session limit: reconnect", link.dialer.closeReason)

            // A reconnect is a fresh handshake.
            net.clock += 1
            val again = net.dial("A", "B").also { net.drain() }
            assertTrue(again.established)

            val silent = net.dial("A", "B")
            silent.toAcceptor.clear() // B never hears the Init
            net.clock += SecureConnection.MESSAGE_TIMEOUT + 1
            net.tick()
            assertEquals(HandshakeFailure.Kind.TIMEOUT, silent.dialer.failure)
            assertTrue(net["A"].guard.backoffMillis("addr-B") > 0, "a failed dial backs off")
        }
    }

    @Test
    fun aRejectedFingerprintStoresNothing() = runBlocking<Unit> {
        SecureNet("A", "B").use { net ->
            net["A"].enable()
            net["B"].enable()
            net.answers["B"] = false
            val link = net.pairByQr("A", "B")
            assertTrue(link.dialer.closed && link.acceptor.closed)
            assertTrue(net["A"].peers.peers().isEmpty() && net["B"].peers.peers().isEmpty())
        }
    }

    @Test
    fun theStreamFramerCarriesEveryMessage() {
        // Sanity: the harness frames every message; a byte-by-byte feed still yields whole bodies.
        val framer = LengthFramer()
        val bodies = (LengthFramer.frame(byteArrayOf(1, 2)) + LengthFramer.frame(byteArrayOf(3))).map { framer.push(byteArrayOf(it)) }.flatten()
        assertEquals(listOf(listOf<Byte>(1, 2), listOf<Byte>(3)), bodies.map { it.toList() })
    }
}
