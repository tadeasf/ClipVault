package cz.tadeasf.clipvault.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.tadeasf.clipvault.detect.SensitiveCategory
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SyncSettingsViewModel(private val controller: SyncController, permissions: PermissionGate) : ViewModel() {
    private val asker = PermissionAsker(permissions, viewModelScope)

    val state: StateFlow<SyncSnapshot> = controller.state
    val prompt: StateFlow<PermissionPrompt?> = asker.prompt

    fun setEnabled(enabled: Boolean) {
        if (enabled) asker.ask(SyncPermission.LOCAL_NETWORK) { controller.setEnabled(true) } else launch { controller.setEnabled(false) }
    }

    fun setWifiAllowlist(enabled: Boolean) {
        if (enabled) asker.ask(SyncPermission.LOCATION) { controller.setWifiAllowlist(true) } else launch { controller.setWifiAllowlist(false) }
    }

    fun continuePermission() = asker.proceed()

    fun dismissPermission() = asker.dismiss()

    fun openAppSettings() = asker.openAppSettings()

    fun setDeviceName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty() && trimmed != state.value.deviceName) launch { controller.setDeviceName(trimmed) }
    }

    fun setSensitiveOptIn(category: SensitiveCategory, optIn: Boolean) = launch { controller.setSensitiveOptIn(category, optIn) }

    fun addSsid(ssid: String) {
        val trimmed = ssid.trim()
        if (trimmed.isNotEmpty()) launch { controller.setSsidAllowed(trimmed, true) }
    }

    fun removeSsid(ssid: String) = launch { controller.setSsidAllowed(ssid, false) }

    fun unpair(id: String) = launch { controller.unpair(id) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
