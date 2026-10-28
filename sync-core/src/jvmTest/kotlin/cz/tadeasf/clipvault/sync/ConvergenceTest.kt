package cz.tadeasf.clipvault.sync

import cz.tadeasf.clipvault.detect.SensitiveCategory
import cz.tadeasf.clipvault.model.MetaField
import cz.tadeasf.clipvault.security.sha256Hex
import cz.tadeasf.clipvault.sync.wire.Hello
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Table-driven scenarios from the step 5 brief, on three in-process devices (protocol.md section 13). */
class ConvergenceTest {

    private fun assertConverged(net: Net) {
        val states = net.states()
        states.values.zipWithNext().forEach { (a, b) -> assertEquals(a, b, "states differ: $states") }
    }

    @Test
    fun pinOnAAndDeleteOnBGiveADeletedClipInBothArrivalOrders() = runBlocking<Unit> {
        for (pinFirst in listOf(true, false)) Net("A", "B", "C").use { net ->
            net["A"].copy("shared")
            net.syncEveryone()
            net.disconnectAll()
            net["A"].pin("shared")
            net["B"].delete("shared")

            val order = if (pinFirst) listOf("A", "B") else listOf("B", "A")
            order.forEach { net.sync("C", it) }
            assertEquals(emptyList(), net["C"].state(), "pinFirst=$pinFirst")

            net.syncEveryone()
            assertConverged(net)
            assertEquals(emptyList(), net["A"].state(), "delete beats pin, pinFirst=$pinFirst")
        }
    }

    @Test
    fun lamportTiesAreBrokenByDeviceIdWhateverTheArrivalOrder() = runBlocking<Unit> {
        // (A's value, B's value) at the same Lamport clock: B > A, so B's value wins.
        val cases = listOf(true to false, false to true)
        for ((aPins, bPins) in cases) for (aFirst in listOf(true, false)) Net("A", "B", "C").use { net ->
            net["A"].copy("tie")
            net.sync("A", "B")
            net.sync("A", "C") // A's clock is 1, B's and C's 2 (receive rule)
            net["A"].copy("bump A's clock") // A: 2
            net["A"].pin("tie", aPins) // A: 3
            net["B"].pin("tie", bPins) // B: 3
            val clocks = net["A"].db.metaEventQueries.forClip(net["A"].rowOf("tie")!!).executeAsList().map { it.lamportClock } +
                net["B"].db.metaEventQueries.forClip(net["B"].rowOf("tie")!!).executeAsList().map { it.lamportClock }
            assertEquals(listOf(3L, 3L), clocks, "the two pins must tie")

            (if (aFirst) listOf("A", "B") else listOf("B", "A")).forEach { net.sync("C", it) }
            assertEquals(bPins, net["C"].state().single { it.content == "tie" }.pinned, "aPins=$aPins aFirst=$aFirst")
            net.syncEveryone()
            assertConverged(net)
            assertEquals(bPins, net["A"].state().single { it.content == "tie" }.pinned)
        }
    }

    @Test
    fun duplicateAndReorderedDeliveryGiveTheSameState() = runBlocking<Unit> {
        Net("A", "B", "C", "D").use { net ->
            net["A"].copy("one")
            net["B"].copy("one") // the same content from two devices: one row, two members
            net["B"].copy("two")
            net["C"].copy("483920")
            net["C"].copy("three")
            net.syncEveryone()
            net.disconnectAll()
            net["A"].pin("one")
            net["B"].delete("two")
            net["C"].pin("three")
            net["C"].pin("three", false)
            net["A"].copy("one") // re-copy bump
            net.sync("A", "B")
            net.sync("A", "C")
            val expected = net["A"].state()
            val events = net["A"].export()

            val orders = mapOf(
                "as logged" to events,
                "reversed" to events.reversed(),
                "every event twice" to events.flatMap { listOf(it, it) },
                "shuffled with duplicates" to (events + events.shuffled(Random(1)).take(events.size / 2)).shuffled(Random(2)),
            )
            for ((name, order) in orders) Net("D").use { fresh ->
                order.forEach { fresh["D"].node.applier.apply(it, "A") }
                assertEquals(expected, fresh["D"].state(), name)
            }
        }
    }

    @Test
    fun catchUpAfterADisconnectIncludesEventsRelayedFromAThirdDevice() = runBlocking<Unit> {
        Net("A", "B", "C").use { net ->
            net.connect("A", "B")
            net.connect("B", "C")
            net["A"].copy("before")
            net.drain()
            net.disconnect(net.link("A", "B")!!)

            net["C"].copy("from C while A is away")
            net["C"].pin("from C while A is away")
            net["B"].copy("from B while A is away")
            net.drain() // B gets C's clip and pin over the live B-C link

            net.connect("A", "B")
            net.drain()
            assertEquals(setOf("before", "from C while A is away", "from B while A is away"), net["A"].texts())
            assertTrue(net["A"].state().single { it.content.startsWith("from C") }.pinned)
            assertConverged(net)
            // A got C's clip from B, so A never sends it back to B.
            val seq = net["A"].db.clipMemberQueries.selectById(net["A"].rowOf("from C while A is away")!!).executeAsOne().seq
            assertEquals("B", net["A"].syncDb.syncQueries.origin(seq).executeAsOne())
        }
    }

    @Test
    fun anOtpReachesBOnlyAfterBOptsInAndStaysAfterOptingOut() = runBlocking<Unit> {
        Net("A", "B", "C").use { net ->
            net["A"].copy("483920")
            net["A"].copy("not secret")
            net.sync("A", "B")
            assertEquals(setOf("not secret"), net["B"].texts())

            net["B"].node.config = net["B"].node.config.copy(sensitiveOptIn = setOf(SensitiveCategory.OTP))
            net.sync("A", "B")
            assertEquals(setOf("not secret", "483920"), net["B"].texts())
            assertTrue(net["B"].state().single { it.content == "483920" }.sensitive)

            net.sync("B", "C") // C did not opt in: B does not relay it
            assertEquals(setOf("not secret"), net["C"].texts())

            net["B"].node.config = net["B"].node.config.copy(sensitiveOptIn = emptySet())
            net.sync("A", "B")
            assertEquals(setOf("not secret", "483920"), net["B"].texts(), "opting out does not delete")
        }
    }

    @Test
    fun ownHistoryFromBeforeSyncAndExcludedAppsStayLocal() = runBlocking<Unit> {
        val excluded = "com.example.secretchat"
        Net("A", "B", configure = { id ->
            SyncConfig(id, syncEnabledAt = 1_002, isExcludedSource = { it == excluded })
        }).use { net ->
            net["A"].copy("old") // time 1000
            net["A"].copy("older") // 1001
            net["A"].copy("new") // 1002
            net["A"].copy("from an excluded app", sourcePackage = excluded)
            net.sync("A", "B")
            assertEquals(setOf("new"), net["B"].texts())
            net["B"].copy("old") // B's own, later copy of the same text is B's to share
            net.sync("A", "B")
            assertEquals(setOf("new", "old"), net["B"].texts())
            assertEquals(2, net["A"].db.clipMemberQueries.forClip(net["A"].rowOf("old")!!).executeAsList().size, "joined A's row")
        }
    }

    @Test
    fun anOverCapClipArrivesAsMetadataAndAFetchFillsIt() = runBlocking<Unit> {
        Net("A", "B", configure = { if (it == "B") SyncConfig(it, maxPayloadBytes = 32) else SyncConfig(it) }).use { net ->
            val big = "x".repeat(100)
            val id = net["A"].copy(big)
            val link = net.connect("A", "B")
            net.drain()
            val waiting = net["B"].state().single()
            assertEquals("" to sha256Hex(big), waiting.content to waiting.hash)

            link.atB.fetch(id)
            net.drain()
            assertEquals(big, net["B"].state().single().content)
            assertConverged(net)
        }
    }

    @Test
    fun aFetchCannotGetAroundTheFilters() = runBlocking<Unit> {
        Net("A").use { net ->
            val otp = net["A"].copy("483920")
            assertNull(net["A"].node.log.payload(otp, Hello("B")))
            assertNotNull(net["A"].node.log.payload(otp, Hello("B", setOf("OTP"))))
        }
    }

    @Test
    fun metaForAClipNotYetHereIsFoldedInWhenTheClipArrives() = runBlocking<Unit> {
        Net("A", "B").use { net ->
            net["A"].copy("late")
            net["A"].pin("late")
            val events = net["A"].export()
            assertEquals(listOf(MetaField.PINNED.name), events.filterIsInstance<cz.tadeasf.clipvault.sync.wire.MetaChanged>().map { it.field })
            events.reversed().forEach { net["B"].node.applier.apply(it, "A") }
            assertTrue(net["B"].state().single().pinned)
        }
    }
}
