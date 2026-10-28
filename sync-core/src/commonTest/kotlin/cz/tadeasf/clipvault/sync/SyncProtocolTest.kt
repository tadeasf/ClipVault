package cz.tadeasf.clipvault.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncProtocolTest {

    @Test
    fun statusReportsProtocolVersion() {
        assertEquals("Sync available (protocol v1)", SyncProtocol.status())
    }
}
