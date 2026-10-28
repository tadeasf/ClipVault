package cz.tadeasf.clipvault

import kotlin.test.Test
import kotlin.test.assertEquals

class AppInfoTest {

    @Test
    fun titleNamesThePlatform() {
        val platform = object : Platform {
            override val name = "Test OS"
        }
        assertEquals("ClipVault on Test OS", appTitle(platform))
    }
}
