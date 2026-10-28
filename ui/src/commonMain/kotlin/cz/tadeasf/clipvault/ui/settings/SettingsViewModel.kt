package cz.tadeasf.clipvault.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.tadeasf.clipvault.settings.Settings
import cz.tadeasf.clipvault.settings.SettingsRepository
import cz.tadeasf.clipvault.settings.ThemeMode
import cz.tadeasf.clipvault.ui.AppEntry
import cz.tadeasf.clipvault.ui.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SettingsState {
    data object Loading : SettingsState

    data class Loaded(
        val settings: Settings,
        /** Null on platforms without an Accessibility capture path (desktop). */
        val accessibilityEnabled: Boolean?,
        /** Filled only while Accessibility is on: exclusions need it to know the source app. */
        val apps: List<AppEntry>,
    ) : SettingsState {
        val exclusionsAvailable: Boolean get() = accessibilityEnabled == true
    }
}

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val platform: Platform,
) : ViewModel() {
    private val accessibility = MutableStateFlow(platform.accessibility?.isEnabled())

    val state: StateFlow<SettingsState> = combine(repository.settings, accessibility) { settings, enabled ->
        SettingsState.Loaded(settings, enabled, if (enabled == true) platform.installedApps() else emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsState.Loading)

    /** Call on resume: the user may have just toggled Accessibility in system settings. */
    fun refresh() {
        accessibility.value = platform.accessibility?.isEnabled()
    }

    fun openAccessibilitySettings() = platform.accessibility?.openSystemSettings()

    fun setMaxCount(count: Long?) = update { it.copy(maxCount = count?.takeIf { n -> n > 0 }) }

    fun setMaxAgeDays(days: Long?) = update { it.copy(maxAgeDays = days?.takeIf { n -> n > 0 }) }

    /** Out-of-range values are ignored, not clamped, so typing "1" on the way to "120" doesn't snap to 10. */
    fun setSensitiveTtlSeconds(seconds: Long) {
        if (seconds in TTL_RANGE) update { it.copy(sensitiveTtlSeconds = seconds) }
    }

    fun setExcluded(packageName: String, excluded: Boolean) = update {
        it.copy(excludedPackages = if (excluded) it.excludedPackages + packageName else it.excludedPackages - packageName)
    }

    fun setCaptureNotification(enabled: Boolean) = update { it.copy(captureNotification = enabled) }

    fun setTheme(theme: ThemeMode) = update { it.copy(theme = theme) }

    private fun update(transform: (Settings) -> Settings) {
        viewModelScope.launch { repository.update(transform) }
    }

    companion object {
        val TTL_RANGE = 10L..3600L
    }
}
