package cz.tadeasf.clipvault.model

import kotlin.test.Test
import kotlin.test.assertEquals

class MetaFoldTest {

    private fun pin(clock: Long, device: String, value: Boolean = true) =
        MetaEvent("c", MetaField.PINNED, value, clock, device)

    private fun delete(clock: Long, device: String, value: Boolean = true) =
        MetaEvent("c", MetaField.DELETED, value, clock, device)

    private val cases = listOf(
        "no events" to (emptyList<MetaEvent>() to ClipFlags(pinned = false, deleted = false)),
        "single pin" to (listOf(pin(1, "a")) to ClipFlags(pinned = true, deleted = false)),
        "later unpin wins" to (listOf(pin(1, "a"), pin(2, "a", false)) to ClipFlags(false, false)),
        "higher clock wins regardless of order" to (listOf(pin(2, "a", false), pin(1, "b")) to ClipFlags(false, false)),
        "clock tie: higher deviceId wins (unpin)" to (listOf(pin(5, "a"), pin(5, "b", false)) to ClipFlags(false, false)),
        "clock tie: higher deviceId wins (pin)" to (listOf(pin(5, "a", false), pin(5, "b")) to ClipFlags(true, false)),
        "delete beats a later pin" to (listOf(delete(1, "a"), pin(9, "b")) to ClipFlags(false, true)),
        "delete beats an earlier pin" to (listOf(pin(9, "b"), delete(1, "a")) to ClipFlags(false, true)),
        "delete=false is not a delete" to (listOf(delete(3, "a", false), pin(1, "a")) to ClipFlags(true, false)),
    )

    @Test
    fun foldMatchesTable() {
        for ((name, case) in cases) {
            val (events, expected) = case
            assertEquals(expected, foldMeta(events), name)
            assertEquals(expected, foldMeta(events.reversed()), "$name (reversed)")
        }
    }
}
