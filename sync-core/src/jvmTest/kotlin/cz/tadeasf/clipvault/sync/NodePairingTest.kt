package cz.tadeasf.clipvault.sync

import cz.tadeasf.clipvault.sync.node.PairingEnd
import cz.tadeasf.clipvault.sync.secure.PairBy
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Step 8: pairing through running nodes, the way the phone pairs (no separate pairing process). */
class NodePairingTest {
    private val ends = ConcurrentHashMap<String, PairingEnd>()

    private fun LiveNet.startRecording(id: String) = start(id).also { node -> node.onPairingEnded = { ends[id] = it } }

    @Test
    fun qrPairingOverRunningNodesThenSyncs() = runBlocking<Unit> {
        LiveNet("A", "B").use { net ->
            val a = net.startRecording("A")
            val b = net.startRecording("B")
            eventually(message = { "A never listened" }) { a.listeningPort != null }
            val qr = a.run { net.secure["A"].windows.openQr(listOf(byteArrayOf(127, 0, 0, 1)), a.listeningPort!!) }

            assertTrue(b.pair("127.0.0.1:${qr.port}", PairBy.Qr(qr.token)))
            eventually(message = { "pairing did not end on both sides: $ends" }) { ends.size == 2 }
            assertEquals("Device B", ends.getValue("A").peerName)
            assertEquals("Device A", ends.getValue("B").peerName)
            assertEquals(net.secure.shownWords["A"], net.secure.shownWords["B"])

            // Hello.port (1.3): A was dialled, yet knows where B listens, without any discovery.
            eventually(message = { "A has no address for B" }) { net.secure["A"].peers.peers().single().lastAddress == "127.0.0.1:${b.listeningPort}" }

            // The pairing connection carries on as the first sync session.
            b.write { net["B"].add("after pairing") }
            eventually(message = { "A never got the clip" }) { net["A"].state().any { it.content == "after pairing" } }
            println("node pairing (QR): paired both ways, first clip synced over the same connection")
        }
    }

    @Test
    fun aWrongTokenEndsThePairingOnBothSides() = runBlocking<Unit> {
        LiveNet("A", "B").use { net ->
            val a = net.startRecording("A")
            val b = net.startRecording("B")
            eventually(message = { "A never listened" }) { a.listeningPort != null }
            a.run { net.secure["A"].windows.openQr(listOf(byteArrayOf(127, 0, 0, 1)), a.listeningPort!!) }

            assertTrue(b.pair("127.0.0.1:${a.listeningPort}", PairBy.Qr(ByteArray(16))))
            eventually(message = { "no failure reported on B: $ends" }) { ends["B"] != null }
            assertNull(ends.getValue("B").peerName)
            assertTrue(net.secure["A"].peers.peers().isEmpty())
            assertTrue(net.secure["B"].peers.peers().isEmpty())
        }
    }

    @Test
    fun aShownCodeIsAdvertisedWithThePairingFlagUntilItCloses() = runBlocking<Unit> {
        LiveNet("A").use { net ->
            val a = net.start("A")
            eventually(message = { "A never advertised" }) { net.advertised.isNotEmpty() }
            assertFalse(net.advertised.last().second)

            a.run { net.secure["A"].windows.openCode() }
            eventually(message = { "pairing=1 never advertised" }) { net.advertised.last() == ("A" to true) }
            a.run { net.secure["A"].windows.closeCode() }
            eventually(message = { "pairing=1 never withdrawn" }) { net.advertised.last() == ("A" to false) }
        }
    }

    @Test
    fun aNetworkChangeDuringStartUpBindsOnce() = runBlocking<Unit> {
        LiveNet("A").use { net ->
            val a = net.start("A")
            repeat(5) { a.networkChanged() } // Android's NetworkCallback fires at once on registration
            eventually(message = { "A never listened" }) { a.listeningPort != null }
            Thread.sleep(300)
            assertEquals(1, net.logs.count { "listening on port" in it }, net.logs.joinToString("\n"))
        }
    }

    @Test
    fun aNetworkChangeDialsAtOnceInsteadOfWaitingOutTheBackoff() = runBlocking<Unit> {
        LiveNet("A", "B").use { net ->
            net.pair("A", "B")
            net.sees("B", "A")
            val b = net.start("B") // A isn't running: B fails and backs off; the frozen clock never ends the backoff
            eventually(message = { "B never backed off" }) { net.status("B", "A").backoffUntil != null }
            net.start("A")
            eventually(message = { "A never listened" }) { net.nodes.getValue("A").listeningPort != null }
            Thread.sleep(500)
            assertTrue(net.status("B", "A").state != cz.tadeasf.clipvault.sync.node.PeerState.LIVE, "still in backoff")

            b.networkChanged()
            eventually(message = { "B did not reconnect after networkChanged" }) { net.status("B", "A").state == cz.tadeasf.clipvault.sync.node.PeerState.LIVE }
        }
    }
}
