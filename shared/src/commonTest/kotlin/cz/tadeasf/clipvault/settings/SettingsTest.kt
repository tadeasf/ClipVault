package cz.tadeasf.clipvault.settings

import cz.tadeasf.clipvault.history.newTestDatabase
import cz.tadeasf.clipvault.model.RetentionPolicy
import cz.tadeasf.clipvault.storage.clipDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

class SettingsTest {

    private val custom = Settings(
        maxCount = null,
        maxAgeDays = 30,
        sensitiveTtlSeconds = 45,
        excludedPackages = setOf("com.whatsapp", "org.thoughtcrime.securesms"),
        captureNotification = true,
        theme = ThemeMode.SYSTEM,
        lastCaptureAt = 1_700_000_000_000,
    )

    @Test
    fun codecRoundTrips() {
        assertEquals(Settings(), SettingsCodec.decode(SettingsCodec.encode(Settings())))
        assertEquals(custom, SettingsCodec.decode(SettingsCodec.encode(custom)))
    }

    @Test
    fun missingOrBrokenValuesFallBackToDefaults() {
        assertEquals(Settings(), SettingsCodec.decode(emptyMap()))
        val broken = mapOf("maxCount" to "lots", "theme" to "NEON", "captureNotification" to "maybe")
        assertEquals(Settings(), SettingsCodec.decode(broken))
    }

    @Test
    fun retentionPolicyConvertsUnits() {
        assertEquals(RetentionPolicy(null, 30L * 24 * 60 * 60 * 1000, 45_000), custom.retentionPolicy())
    }

    @Test
    fun settingsPersistInTheEncryptedDatabase() = runTest {
        val store = newTestDatabase()
        val key = ByteArray(32) { 3 }
        val driver = store.open(key)
        SqlSettingsRepository(clipDatabase(driver), Dispatchers.Unconfined).update { custom }
        val firstId = deviceId(clipDatabase(driver))
        driver.close()

        val reopened = store.open(key)
        try {
            assertEquals(custom, SqlSettingsRepository(clipDatabase(reopened), Dispatchers.Unconfined).settings.value)
            assertEquals(firstId, deviceId(clipDatabase(reopened)))
        } finally {
            reopened.close()
        }
    }
}
