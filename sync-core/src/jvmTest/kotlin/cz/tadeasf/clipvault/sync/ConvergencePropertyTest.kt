package cz.tadeasf.clipvault.sync

import cz.tadeasf.clipvault.detect.SensitiveCategory
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * Any interleaving converges: random local actions on three devices, random connects and disconnects,
 * and message delivery interleaved at random across connections. Once everyone is connected and the bus
 * is quiet, all three show the same history. Applying one device's log in a random order with
 * duplicates to a fresh device gives that state too.
 */
class ConvergencePropertyTest {
    private sealed interface Op {
        data class Copy(val device: Int, val text: Int) : Op
        data class Pin(val device: Int, val text: Int, val pinned: Boolean) : Op
        data class Delete(val device: Int, val text: Int) : Op
        data class Toggle(val a: Int, val b: Int) : Op
        data class Deliver(val messages: Int) : Op
    }

    private val ids = listOf("A", "B", "C")

    // Few texts, so devices copy the same content independently; one is a sensitive OTP.
    private val texts = listOf("alpha", "beta", "gamma", "483920", "https://example.com/x")

    private val device = Arb.int(0..2)
    private val text = Arb.int(texts.indices)
    private val op: Arb<Op> = Arb.choice(
        Arb.bind(device, text) { d, t -> Op.Copy(d, t) },
        Arb.bind(device, text, Arb.boolean()) { d, t, p -> Op.Pin(d, t, p) },
        Arb.bind(device, text) { d, t -> Op.Delete(d, t) },
        Arb.bind(device, Arb.int(1..2)) { a, offset -> Op.Toggle(a, (a + offset) % 3) },
        Arb.int(1..15).map { Op.Deliver(it) },
    )

    @Test
    fun anyInterleavingConverges() = runBlocking<Unit> {
        checkAll(PropTestConfig(iterations = 150), Arb.list(op, 1..40), Arb.long()) { ops, seed ->
            val random = Random(seed)
            Net(*ids.toTypedArray(), configure = { SyncConfig(it, sensitiveOptIn = SensitiveCategory.entries.toSet()) }).use { net ->
                for (op in ops) when (op) {
                    is Op.Copy -> net[ids[op.device]].copy(texts[op.text])
                    is Op.Pin -> net[ids[op.device]].pin(texts[op.text], op.pinned)
                    is Op.Delete -> net[ids[op.device]].delete(texts[op.text])
                    is Op.Toggle -> net.link(ids[op.a], ids[op.b])?.let(net::disconnect) ?: net.connect(ids[op.a], ids[op.b])
                    is Op.Deliver -> net.step(random, op.messages)
                }
                net.syncEveryone(random)
                val states = net.states()
                assertEquals(states.getValue("A"), states.getValue("B"), "A vs B after $ops")
                assertEquals(states.getValue("A"), states.getValue("C"), "A vs C after $ops")

                val events = net["B"].export()
                Net("D").use { fresh ->
                    (events + events.shuffled(random).take(random.nextInt(events.size + 1))).shuffled(random)
                        .forEach { fresh["D"].node.applier.apply(it, "B") }
                    assertEquals(states.getValue("B"), fresh["D"].state(), "fresh device from B's log after $ops")
                }
            }
        }
    }
}
