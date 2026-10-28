package cz.tadeasf.clipvault.sync.pairing

import cz.tadeasf.clipvault.db.ClipDatabase
import cz.tadeasf.clipvault.detect.SensitiveCategory

/**
 * The user's sync settings (step 8), in the encrypted `Setting` table under `sync.*` keys. The same keys on
 * phone and desktop; the desktop ignores [enabled] (sync is on once it has an identity) and the Wi-Fi fields.
 */
data class SyncPrefs(
    /** The user's toggle on Android. Turning it off stops the node; pairing and history stay. */
    val enabled: Boolean = false,
    /** Sent in Identity and Hello; null means the platform default (hostname, phone model). */
    val name: String? = null,
    /** Sensitive categories this device accepts from peers (protocol.md section 10). */
    val sensitiveOptIn: Set<SensitiveCategory> = emptySet(),
    /** Android: sync only on [ssids]. Off by default (design 6.5). */
    val wifiAllowlist: Boolean = false,
    val ssids: Set<String> = emptySet(),
    /** The port the node last listened on, tried first on the next start so peers' stored addresses stay valid. */
    val port: Int? = null,
)

fun loadSyncPrefs(db: ClipDatabase): SyncPrefs {
    fun get(key: String) = db.settingQueries.get(key).executeAsOneOrNull()
    return SyncPrefs(
        enabled = get(ENABLED) == "true",
        name = get(NAME)?.ifBlank { null },
        sensitiveOptIn = get(OPT_INS).orEmpty().split(',').mapNotNull { n -> SensitiveCategory.entries.find { it.name == n } }.toSet(),
        wifiAllowlist = get(WIFI_ALLOWLIST) == "true",
        // SSIDs may contain commas, so they are stored one per line.
        ssids = get(SSIDS).orEmpty().split('\n').filter(String::isNotEmpty).toSet(),
        port = get(PORT)?.toIntOrNull(),
    )
}

fun saveSyncPrefs(db: ClipDatabase, prefs: SyncPrefs) = db.transaction {
    db.settingQueries.put(ENABLED, prefs.enabled.toString())
    db.settingQueries.put(NAME, prefs.name.orEmpty())
    db.settingQueries.put(OPT_INS, prefs.sensitiveOptIn.map { it.name }.sorted().joinToString(","))
    db.settingQueries.put(WIFI_ALLOWLIST, prefs.wifiAllowlist.toString())
    db.settingQueries.put(SSIDS, prefs.ssids.sorted().joinToString("\n"))
    db.settingQueries.put(PORT, prefs.port?.toString().orEmpty())
}

/** Remembers the node's listening port (only that key; the rest of the prefs may be edited elsewhere meanwhile). */
fun saveSyncPort(db: ClipDatabase, port: Int) = db.settingQueries.put(PORT, port.toString())

private const val ENABLED = "sync.enabled"
private const val NAME = "sync.name"
private const val OPT_INS = "sync.sensitiveOptIn"
private const val WIFI_ALLOWLIST = "sync.wifiAllowlist"
private const val SSIDS = "sync.ssids"
private const val PORT = "sync.port"
