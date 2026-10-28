package cz.tadeasf.clipvault.settings

import cz.tadeasf.clipvault.model.RetentionPolicy
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { DARK, LIGHT, SYSTEM }

data class Settings(
    /** Keep at most this many unpinned clips; null keeps everything. */
    val maxCount: Long? = 1000,
    /** Delete unpinned clips older than this; null keeps them. */
    val maxAgeDays: Long? = null,
    val sensitiveTtlSeconds: Long = RetentionPolicy.DEFAULT_SENSITIVE_TTL_MILLIS / 1000,
    /** Packages whose clips are never stored. Only effective on the Accessibility capture path. */
    val excludedPackages: Set<String> = emptySet(),
    val captureNotification: Boolean = false,
    val theme: ThemeMode = ThemeMode.DARK,
    val lastCaptureAt: Long? = null,
) {
    fun retentionPolicy() = RetentionPolicy(
        maxCount = maxCount,
        maxAgeMillis = maxAgeDays?.let { it * DAY_MILLIS },
        sensitiveTtlMillis = sensitiveTtlSeconds * 1000,
    )

    private companion object {
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    }
}

/** Process-wide settings. Every capture path and screen reads the same instance. */
interface SettingsRepository {
    val settings: StateFlow<Settings>

    suspend fun update(transform: (Settings) -> Settings)
}

/** Key/value form stored in the `Setting` table. Absent or unparsable keys fall back to the default. */
internal object SettingsCodec {
    fun encode(settings: Settings): Map<String, String> = with(settings) {
        mapOf(
            "maxCount" to (maxCount?.toString() ?: ""),
            "maxAgeDays" to (maxAgeDays?.toString() ?: ""),
            "sensitiveTtlSeconds" to sensitiveTtlSeconds.toString(),
            "excludedPackages" to excludedPackages.sorted().joinToString(","),
            "captureNotification" to captureNotification.toString(),
            "theme" to theme.name,
            "lastCaptureAt" to (lastCaptureAt?.toString() ?: ""),
        )
    }

    fun decode(values: Map<String, String>): Settings {
        val defaults = Settings()
        // Absent key: default. Empty value: explicitly "off" (null). Garbage: default.
        fun optionalLong(key: String, default: Long?): Long? = when (val value = values[key]) {
            null -> default
            "" -> null
            else -> value.toLongOrNull() ?: default
        }
        return Settings(
            maxCount = optionalLong("maxCount", defaults.maxCount),
            maxAgeDays = optionalLong("maxAgeDays", defaults.maxAgeDays),
            sensitiveTtlSeconds = values["sensitiveTtlSeconds"]?.toLongOrNull() ?: defaults.sensitiveTtlSeconds,
            excludedPackages = values["excludedPackages"]?.split(',')?.filter(String::isNotEmpty)?.toSet() ?: emptySet(),
            captureNotification = values["captureNotification"]?.toBooleanStrictOrNull() ?: defaults.captureNotification,
            theme = values["theme"]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: defaults.theme,
            lastCaptureAt = optionalLong("lastCaptureAt", null),
        )
    }
}
