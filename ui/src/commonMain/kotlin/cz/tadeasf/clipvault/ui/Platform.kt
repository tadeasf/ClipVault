package cz.tadeasf.clipvault.ui

import cz.tadeasf.clipvault.history.ClipIngest
import cz.tadeasf.clipvault.history.ClipRepository
import cz.tadeasf.clipvault.settings.SettingsRepository

data class AppEntry(val packageName: String, val label: String)

/** What only the host app can do. Android-only capabilities are null on desktop. */
interface Platform {
    /** Puts [text] on the system clipboard; [sensitive] marks it so other apps and the OS hide it. */
    fun copyToClipboard(text: String, sensitive: Boolean)

    fun share(text: String)

    /** "ClipVault 1.0 (vault)" or similar, for the About section. */
    val appInfo: String

    val accessibility: AccessibilityControl? get() = null

    /** Asks the system to add the Quick Settings tile; null where unsupported. */
    val requestAddTile: (() -> Unit)? get() = null

    /** Launchable apps for the exclusion picker. */
    fun installedApps(): List<AppEntry> = emptyList()
}

interface AccessibilityControl {
    fun isEnabled(): Boolean
    fun openSystemSettings()
}

/** Everything the shared screens need, built once by the host. */
class AppEnvironment(
    val repository: ClipRepository,
    val ingest: ClipIngest,
    val settings: SettingsRepository,
    val platform: Platform,
)
