package cz.tadeasf.clipvault.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * The relay bug of protocol.md section 11: catching up by "Lamport clock > last seen" loses events that
 * reached the peer through a third device. The same scenario runs against a naive Lamport-cursor sync and
 * the seq-based one.
 */
class RelayTest {
    private interface Mesh : AutoCloseable {
        suspend fun copy(device: String, text: String)
        fun sync(a: String, b: String)
        fun texts(device: String): Set<String>
    }

    /** Catch-up by "events with lamport > the highest lamport I got from you". */
    private class NaiveLamportMesh : Mesh {
        private class Node {
            var clock = 0L
            val events = mutableSetOf<Pair<Long, String>>() // (author's lamport, text)
            val lastLamportFrom = mutableMapOf<String, Long>()
        }

        private val nodes = mutableMapOf<String, Node>()
        private fun node(id: String) = nodes.getOrPut(id, ::Node)

        override suspend fun copy(device: String, text: String) {
            val node = node(device)
            node.events += ++node.clock to text
        }

        override fun sync(a: String, b: String) {
            pull(a, b)
            pull(b, a)
        }

        private fun pull(into: String, from: String) {
            val (me, peer) = node(into) to node(from)
            val since = me.lastLamportFrom[from] ?: 0
            val new = peer.events.filter { it.first > since }
            new.forEach { me.events += it; me.clock = maxOf(me.clock, it.first) + 1 }
            new.maxOfOrNull { it.first }?.let { me.lastLamportFrom[from] = maxOf(since, it) }
        }

        override fun texts(device: String) = node(device).events.map { it.second }.toSet()

        override fun close() = Unit
    }

    private class SeqMesh : Mesh {
        private val net = Net("A", "B", "C")
        override suspend fun copy(device: String, text: String) {
            net[device].copy(text)
        }

        override fun sync(a: String, b: String) = net.sync(a, b)
        override fun texts(device: String) = net[device].texts()
        override fun close() = net.close()
    }

    @Test
    fun relayFromCThroughBToA() = runBlocking<Unit> {
        val cases = listOf(
            Triple("naive lamport cursor", ::NaiveLamportMesh as () -> Mesh, 10), // reproduces the loss
            Triple("seq cursor", ::SeqMesh as () -> Mesh, 15),
        )
        for ((name, mesh, expected) in cases) mesh().use { m ->
            repeat(10) { m.copy("B", "from B $it") } // B's clock: 10
            m.sync("A", "B") // A has seen B up to clock 10 (seq 10)
            repeat(5) { m.copy("C", "from C $it") } // C's clock: 1..5
            m.sync("C", "B") // B stores C's events: clocks 1..5, B-seqs 11..15
            m.sync("A", "B")
            assertEquals(expected, m.texts("A").size, name)
        }
    }
}
