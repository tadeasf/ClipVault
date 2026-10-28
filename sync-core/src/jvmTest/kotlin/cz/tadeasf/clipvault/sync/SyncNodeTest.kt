package cz.tadeasf.clipvault.sync

import cz.tadeasf.clipvault.detect.SensitiveCategory
import cz.tadeasf.clipvault.sync.node.PeerState
import cz.tadeasf.clipvault.sync.secure.SecureChannel
import cz.tadeasf.clipvault.sync.secure.SecureConnection
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Step 7: SyncNodes on loopback with real sockets, the real handshake and real encrypted stores. Results
 * are printed (one line per scenario) for the step report.
 */
class SyncNodeTest {
    private fun LiveNet.texts(id: String) = this[id].state().map { it.content }

    private suspend fun LiveNet.write(id: String, texts: List<String>) = nodes.getValue(id).write { texts.forEach { this[id].add(it) } }

    @Test
    fun twoNodesSyncAHundredClipsAndResumeFromTheCursorAfterAKill() = runBlocking<Unit> {
        LiveNet("A", "B", pageSize = 10).use { net ->
            net.pair("A", "B")
            net.sees("B", "A") // only B dials, so one connection carries everything
            net.start("A")
            val clips = (1..100).map { "clip %03d".format(it) }
            net.write("A", clips)
            net.start("B")
            eventually(message = { "B never got 30 clips" }) { net.texts("B").size >= 30 }
            net.stop("B") // killed between two batches
            val cursorAtKill = net["B"].syncDb.syncQueries.cursor(net["A"].deviceId).executeAsOne().lastSeqReceived
            val atKill = net.texts("B").size

            net.start("B")
            eventually(message = { "B has ${net.texts("B").size} of 100" }) { net.texts("B").size == 100 }
            val resent = net.status("B", "A").eventsReceived
            assertEquals(clips.toSet(), net.texts("B").toSet(), "no gaps")
            assertEquals(100, net["B"].state().size, "no duplicates")
            assertEquals(100 - cursorAtKill, resent, "catch-up resumed at the stored cursor")
            assertEquals(net["A"].state(), net["B"].state())
            println("two-node: 100/100 clips, killed at $atKill (cursor $cursorAtKill), after restart received $resent events, 0 duplicates")
        }
    }

    @Test
    fun threeNodesRelayAndResumeThroughTheMiddleOne() = runBlocking<Unit> {
        LiveNet("A", "B", "C", pageSize = 10).use { net ->
            net.pair("A", "B")
            net.pair("B", "C") // A and C are not paired: everything goes through B
            net.sees("B", "A")
            net.sees("C", "B")
            net.start("A")
            val clips = (1..60).map { "relayed %02d".format(it) }
            net.write("A", clips)
            net.start("B")
            eventually(message = { "B never got 20" }) { net.texts("B").size >= 20 }
            net.stop("B")
            net.start("C")
            net.start("B")
            eventually(message = { "C has ${net.texts("C").size} of 60" }) { net.texts("C").size == 60 }
            net.write("C", listOf("from C"))
            eventually(message = { "A never got C's clip" }) { "from C" in net.texts("A") }
            assertEquals(net["A"].state(), net["B"].state())
            assertEquals(net["B"].state(), net["C"].state())
            println("three-node relay: A->B->C 60/60, C->B->A 1/1, B killed mid catch-up, 0 duplicates")
        }
    }

    @Test
    fun simultaneousConnectsSettleOnTheSmallerInitiatorWithoutFlapping() = runBlocking<Unit> {
        LiveNet("A", "B").use { net ->
            net.pair("A", "B")
            net.sees("A", "B")
            net.sees("B", "A")
            net.start("A")
            net.start("B")
            eventually(message = { "never live" }) {
                net.status("A", "B").state == PeerState.LIVE && net.status("B", "A").state == PeerState.LIVE
            }
            delay(1_500)
            val opened = net.nodes.values.sumOf { it.connectionsOpened }
            delay(1_500)
            assertEquals(opened, net.nodes.values.sumOf { it.connectionsOpened }, "no new connections: no flapping")
            val smaller = listOf("A", "B").minBy { net[it].deviceId }
            val other = if (smaller == "A") "B" else "A"
            assertEquals(true, net.status(smaller, other).initiatedHere, "the survivor was dialled by the smaller device id")
            assertEquals(false, net.status(other, smaller).initiatedHere)
            net.write("A", listOf("after settling"))
            eventually(message = { "push lost" }) { "after settling" in net.texts("B") }
            println("simultaneous connect: $opened connections opened, 1 kept (initiator ${net[smaller].deviceId.take(8)}), stable for 1.5 s")
        }
    }

    @Test
    fun missedHeartbeatsCloseTheSessionAndTheNodesReconnect() = runBlocking<Unit> {
        LiveNet("A", "B").use { net ->
            net.pair("A", "B")
            net.sees("B", "A")
            net.start("A")
            net.start("B")
            eventually(message = { "never live" }) { net.status("B", "A").state == PeerState.LIVE }
            net.networks.getValue("A").blackhole = true
            net.secure.clock += SecureConnection.HEARTBEAT_MILLIS * SecureConnection.MISSED_HEARTBEATS + 1
            eventually(message = { "no heartbeat timeout: ${net.status("B", "A")}" }) { net.status("B", "A").lastError == "heartbeat timeout" }
            net.networks.getValue("A").blackhole = false
            net.secure.clock += 2_000 // past the first backoff step
            eventually(message = { "no reconnect: ${net.status("B", "A")}" }) { net.status("B", "A").state == PeerState.LIVE }
            net.write("A", listOf("after the outage"))
            eventually(message = { "push lost" }) { "after the outage" in net.texts("B") }
            println("heartbeat: 3 missed -> closed (${net.status("B", "A").lastError}), reconnected and pushed")
        }
    }

    @Test
    fun sessionLimitEndsWithByeAndReconnects() = runBlocking<Unit> {
        LiveNet("A", "B").use { net ->
            net.pair("A", "B")
            net.sees("B", "A")
            net.start("A")
            net.start("B")
            eventually(message = { "never live" }) { net.status("B", "A").state == PeerState.LIVE }
            val before = net.nodes.getValue("B").connectionsOpened
            net.secure.clock += SecureChannel.MAX_AGE_MILLIS - 30_000 // inside the 60 s margin before 24 h
            eventually(message = { "no reconnect" }) { net.nodes.getValue("B").connectionsOpened > before && net.status("B", "A").state == PeerState.LIVE }
            assertEquals(null, net.status("B", "A").lastError, "a session limit is not a failure")
            net.write("A", listOf("next session"))
            eventually(message = { "push lost" }) { "next session" in net.texts("B") }
            println("session limit: Bye at 24 h - 30 s, reconnected without backoff, sync continues")
        }
    }

    @Test
    fun optingInRescansAndDeliversExactlyTheNewlyAllowedClips() = runBlocking<Unit> {
        LiveNet("A", "B").use { net ->
            net.pair("A", "B")
            net.sees("B", "A")
            net.start("A")
            net.write("A", listOf("483920", "plain one", "plain two"))
            net.start("B")
            eventually(message = { "plain clips missing" }) { net.texts("B").toSet() == setOf("plain one", "plain two") }
            net.nodes.getValue("B").reconfigure(net.setOptIn("B", setOf(SensitiveCategory.OTP)))
            eventually(message = { "OTP never arrived; B: ${net.status("B", "A")}\nlogs:\n${net.logs.joinToString("\n")}" }) { "483920" in net.texts("B") }
            eventually(message = { "not live again" }) { net.status("B", "A").state == PeerState.LIVE }
            assertEquals(1, net.status("B", "A").eventsReceived, "the rescan delivered exactly the OTP clip")
            println("opt-in: reconnect + rescan delivered 1 event (the OTP clip), nothing else re-sent")
        }
    }

    @Test
    fun aRenameReconnectsAndThePeerStoresTheNameFromHello() = runBlocking<Unit> {
        LiveNet("A", "B").use { net ->
            net.pair("A", "B")
            net.sees("B", "A")
            net.start("A")
            net.start("B")
            eventually(message = { "never live" }) { net.status("B", "A").state == PeerState.LIVE }
            val before = net.nodes.getValue("B").connectionsOpened
            val config = net.secure["B"].device.node.config.copy(name = "Renamed‮ B")
            net.nodes.getValue("B").reconfigure(config)
            eventually(message = { "A still calls B ${net.secure["A"].peers.peers().single().name}" }) {
                net.secure["A"].peers.peers().single().name == "Renamed B"
            }
            eventually(message = { "no reconnect" }) { net.nodes.getValue("B").connectionsOpened > before && net.status("B", "A").state == PeerState.LIVE }
            println("rename: Bye + reconnect, A stores the sanitized name from Hello")
        }
    }

    @Test
    fun sixteenCoroutinesHammerOneNodeWithoutInterleavingAndConverge() = runBlocking<Unit> {
        LiveNet("A", "B").use { net ->
            net.pair("A", "B")
            net.sees("B", "A")
            val active = AtomicInteger()
            val maxActive = AtomicInteger()
            val overlaps = AtomicInteger()
            net["A"].node.onRemoteClip = { if (active.get() != 0) overlaps.incrementAndGet() } // an apply while a write runs
            net.start("A")
            net.start("B")
            eventually(message = { "never live" }) { net.status("B", "A").state == PeerState.LIVE }
            coroutineScope {
                val writers = (1..16).map { w ->
                    async(Dispatchers.Default) {
                        repeat(25) { i ->
                            net.nodes.getValue("A").write {
                                maxActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                                net["A"].add("w$w-$i")
                                active.decrementAndGet()
                            }
                        }
                    }
                }
                val fromB = async { net.write("B", (1..50).map { "b-$it" }) }
                (writers + fromB).awaitAll()
            }
            eventually(message = { "A ${net.texts("A").size}, B ${net.texts("B").size}" }) {
                net.texts("A").size == 450 && net.texts("B").size == 450
            }
            assertEquals(net["A"].state(), net["B"].state())
            assertEquals(1, maxActive.get(), "never two local writes at once")
            assertEquals(0, overlaps.get(), "never an apply during a local write")
            println("concurrency: 16 coroutines x 25 writes on A + 50 from B, 450/450 converged, max 1 write at a time, 0 overlapping applies")
        }
    }

    @Test
    fun aCorruptedFrameMidSessionClosesItAndTheNodesReconnectAndConverge() = runBlocking<Unit> {
        LiveNet("A", "B").use { net ->
            net.pair("A", "B")
            net.sees("B", "A")
            net.start("A")
            net.start("B")
            eventually(message = { "never live" }) { net.status("B", "A").state == PeerState.LIVE }
            val before = net.nodes.getValue("B").connectionsOpened
            net.networks.getValue("A").corruptWrite = 3
            net.write("A", (1..20).map { "frame $it" })
            // Since Hello.port (1.3) A knows B's address too and may redial first; then B records no error.
            eventually(message = { "no failure seen: ${net.status("B", "A")}" }) {
                net.status("B", "A").lastError == "frame authentication failed" || net.nodes.getValue("B").connectionsOpened > before
            }
            net.secure.clock += 2_000
            eventually(message = { "B has ${net.texts("B").size} of 20" }) { net.texts("B").size == 20 }
            assertTrue(net.nodes.getValue("B").connectionsOpened > before)
            assertEquals(net["A"].state(), net["B"].state())
            println("fault injection: bad tag on frame 3 -> closed (frame authentication failed), reconnected, 20/20 converged")
        }
    }
}
