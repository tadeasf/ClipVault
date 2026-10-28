package cz.tadeasf.clipvault.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

sealed interface PairingState {
    data object ChooseMethod : PairingState

    data object Scanning : PairingState

    data object EnteringCode : PairingState

    data class Running(val progress: PairingProgress) : PairingState
}

class PairingViewModel(private val controller: SyncController, permissions: PermissionGate) : ViewModel() {
    private val asker = PermissionAsker(permissions, viewModelScope)
    private val current = MutableStateFlow<PairingState>(PairingState.ChooseMethod)
    private var pairing: Job? = null

    val state: StateFlow<PairingState> = current
    val prompt: StateFlow<PermissionPrompt?> = asker.prompt

    fun scan() = asker.ask(SyncPermission.CAMERA) { current.value = PairingState.Scanning }

    fun enterCode() {
        current.value = PairingState.EnteringCode
    }

    fun showQr() = start(PairMethod.ShowQr)

    fun showCode() = start(PairMethod.ShowCode)

    /** The scanner reports every frame it decodes; only the first one pairs. */
    fun scanned(text: String) {
        if (current.value == PairingState.Scanning) start(PairMethod.ScanQr(text))
    }

    fun submitCode(code: String) {
        if (isCode(code)) start(PairMethod.EnterCode(code))
    }

    fun answer(match: Boolean) {
        ((current.value as? PairingState.Running)?.progress as? PairingProgress.Confirm)?.answer(match)
    }

    fun continuePermission() = asker.proceed()

    fun dismissPermission() = asker.dismiss()

    fun openAppSettings() = asker.openAppSettings()

    /** Leaving the screen or trying again: cancelling the collection closes the pairing window or connection. */
    fun reset() {
        pairing?.cancel()
        pairing = null
        asker.dismiss()
        current.value = PairingState.ChooseMethod
    }

    private fun start(method: PairMethod) {
        pairing?.cancel()
        current.value = PairingState.Running(PairingProgress.Connecting)
        pairing = viewModelScope.launch {
            controller.pair(method)
                .catch { emit(PairingProgress.Failed(it.message ?: "Pairing failed")) }
                .collect { current.value = PairingState.Running(it) }
        }
    }

    companion object {
        const val CODE_LENGTH = 6

        fun isCode(text: String) = text.length == CODE_LENGTH && text.all(Char::isDigit)
    }
}
