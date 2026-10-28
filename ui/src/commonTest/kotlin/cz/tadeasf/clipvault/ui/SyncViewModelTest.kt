package cz.tadeasf.clipvault.ui

import cz.tadeasf.clipvault.detect.SensitiveCategory
import cz.tadeasf.clipvault.ui.sync.PairMethod
import cz.tadeasf.clipvault.ui.sync.PairingProgress
import cz.tadeasf.clipvault.ui.sync.PairingState
import cz.tadeasf.clipvault.ui.sync.PairingViewModel
import cz.tadeasf.clipvault.ui.sync.PeerRow
import cz.tadeasf.clipvault.ui.sync.PermissionPrompt
import cz.tadeasf.clipvault.ui.sync.SyncPermission
import cz.tadeasf.clipvault.ui.sync.SyncSettingsViewModel
import cz.tadeasf.clipvault.ui.sync.SyncSnapshot
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class SyncViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    // Settings

    @Test
    fun enableWithPermissionAlreadyGranted() = test {
        val controller = FakeSyncController()
        val vm = SyncSettingsViewModel(controller, FakePermissionGate(SyncPermission.LOCAL_NETWORK))
        vm.setEnabled(true)
        runCurrent()
        assertTrue(vm.state.value.enabled)
        assertNull(vm.prompt.value)
    }

    @Test
    fun enableExplainsThenRequestsAndEnablesWhenGranted() = test {
        val gate = FakePermissionGate().apply { grantOnRequest = true }
        val vm = SyncSettingsViewModel(FakeSyncController(), gate)
        vm.setEnabled(true)
        runCurrent()
        assertEquals(PermissionPrompt(SyncPermission.LOCAL_NETWORK), vm.prompt.value)
        assertTrue(gate.requested.isEmpty())
        assertFalse(vm.state.value.enabled)
        vm.continuePermission()
        runCurrent()
        assertEquals(listOf(SyncPermission.LOCAL_NETWORK), gate.requested)
        assertTrue(vm.state.value.enabled)
        assertNull(vm.prompt.value)
    }

    @Test
    fun enableDeniedStaysOffAndOffersAppSettings() = test {
        val gate = FakePermissionGate()
        val vm = SyncSettingsViewModel(FakeSyncController(), gate)
        vm.setEnabled(true)
        vm.continuePermission()
        runCurrent()
        assertFalse(vm.state.value.enabled)
        assertEquals(PermissionPrompt(SyncPermission.LOCAL_NETWORK, denied = true), vm.prompt.value)
        vm.openAppSettings()
        assertEquals(1, gate.settingsOpened)
    }

    @Test
    fun notNowCancelsTheExplanation() = test {
        val gate = FakePermissionGate().apply { grantOnRequest = true }
        val vm = SyncSettingsViewModel(FakeSyncController(), gate)
        vm.setEnabled(true)
        vm.dismissPermission()
        vm.continuePermission()
        runCurrent()
        assertNull(vm.prompt.value)
        assertTrue(gate.requested.isEmpty())
        assertFalse(vm.state.value.enabled)
    }

    @Test
    fun allowlistStaysOffWhenLocationDenied() = test {
        val gate = FakePermissionGate(SyncPermission.LOCAL_NETWORK)
        val vm = SyncSettingsViewModel(FakeSyncController(SyncSnapshot(enabled = true)), gate)
        vm.setWifiAllowlist(true)
        assertEquals(PermissionPrompt(SyncPermission.LOCATION), vm.prompt.value)
        vm.continuePermission()
        runCurrent()
        assertFalse(vm.state.value.wifiAllowlist)
        assertEquals(PermissionPrompt(SyncPermission.LOCATION, denied = true), vm.prompt.value)
    }

    @Test
    fun allowlistTurnsOnWhenLocationGranted() = test {
        val gate = FakePermissionGate().apply { grantOnRequest = true }
        val vm = SyncSettingsViewModel(FakeSyncController(SyncSnapshot(enabled = true)), gate)
        vm.setWifiAllowlist(true)
        vm.continuePermission()
        runCurrent()
        assertTrue(vm.state.value.wifiAllowlist)
        assertEquals(listOf(SyncPermission.LOCATION), gate.requested)
        vm.setWifiAllowlist(false)
        runCurrent()
        assertFalse(vm.state.value.wifiAllowlist)
    }

    @Test
    fun addAndRemoveSsids() = test {
        val vm = SyncSettingsViewModel(FakeSyncController(SyncSnapshot(wifiAllowlist = true)), FakePermissionGate())
        vm.addSsid("  home  ")
        vm.addSsid("   ")
        vm.addSsid("office")
        runCurrent()
        assertEquals(setOf("home", "office"), vm.state.value.allowedSsids)
        vm.removeSsid("home")
        runCurrent()
        assertEquals(setOf("office"), vm.state.value.allowedSsids)
    }

    @Test
    fun sensitiveOptInToggles() = test {
        val vm = SyncSettingsViewModel(FakeSyncController(), FakePermissionGate())
        assertTrue(vm.state.value.sensitiveOptIn.isEmpty())
        vm.setSensitiveOptIn(SensitiveCategory.OTP, true)
        runCurrent()
        assertEquals(setOf(SensitiveCategory.OTP), vm.state.value.sensitiveOptIn)
        vm.setSensitiveOptIn(SensitiveCategory.OTP, false)
        runCurrent()
        assertTrue(vm.state.value.sensitiveOptIn.isEmpty())
    }

    @Test
    fun renameIgnoresBlankAndTrims() = test {
        val vm = SyncSettingsViewModel(FakeSyncController(SyncSnapshot(deviceName = "Pixel")), FakePermissionGate())
        vm.setDeviceName("   ")
        runCurrent()
        assertEquals("Pixel", vm.state.value.deviceName)
        vm.setDeviceName(" Work phone ")
        runCurrent()
        assertEquals("Work phone", vm.state.value.deviceName)
    }

    @Test
    fun unpairRemovesThePeer() = test {
        val peers = listOf(PeerRow("aa", "Laptop", null, "connected", true), PeerRow("bb", "Desktop", 0, "not running", false))
        val vm = SyncSettingsViewModel(FakeSyncController(SyncSnapshot(enabled = true, peers = peers)), FakePermissionGate())
        vm.unpair("aa")
        runCurrent()
        assertEquals(listOf("bb"), vm.state.value.peers.map { it.id })
    }

    // Pairing

    private fun running(vm: PairingViewModel) = (vm.state.value as PairingState.Running).progress

    @Test
    fun scanPairsOnTheFirstTextOnly() = test {
        val controller = FakeSyncController()
        val vm = PairingViewModel(controller, FakePermissionGate(SyncPermission.CAMERA))
        val answers = mutableListOf<Boolean>()
        vm.scan()
        runCurrent()
        assertEquals(PairingState.Scanning, vm.state.value)
        vm.scanned("clipvault:pair?first")
        vm.scanned("clipvault:pair?second")
        runCurrent()
        assertEquals(listOf<PairMethod>(PairMethod.ScanQr("clipvault:pair?first")), controller.pairings)
        assertEquals(PairingProgress.Connecting, running(vm))

        val words = listOf("apple", "brick", "cloud", "delta", "eagle", "flame")
        controller.progress.trySend(PairingProgress.Confirm(words, "Laptop") { answers += it; controller.progress.trySend(PairingProgress.WaitingForPeer) })
        runCurrent()
        assertEquals(words, (running(vm) as PairingProgress.Confirm).words)
        vm.answer(true)
        runCurrent()
        assertEquals(listOf(true), answers)
        assertEquals(PairingProgress.WaitingForPeer, running(vm))
        controller.progress.trySend(PairingProgress.Paired("Laptop"))
        runCurrent()
        assertEquals(PairingProgress.Paired("Laptop"), running(vm))
    }

    @Test
    fun rejectingTheWordsFails() = test {
        val controller = FakeSyncController()
        val vm = PairingViewModel(controller, FakePermissionGate())
        val answers = mutableListOf<Boolean>()
        vm.showQr()
        controller.progress.trySend(PairingProgress.Confirm(List(6) { "w$it" }, "Laptop") {
            answers += it
            controller.progress.trySend(PairingProgress.Failed("Pairing rejected"))
        })
        runCurrent()
        vm.answer(false)
        runCurrent()
        assertEquals(listOf(false), answers)
        assertEquals(PairingProgress.Failed("Pairing rejected"), running(vm))
        vm.reset()
        assertEquals(PairingState.ChooseMethod, vm.state.value)
    }

    @Test
    fun failedShowsTheReason() = test {
        val controller = FakeSyncController()
        val vm = PairingViewModel(controller, FakePermissionGate())
        vm.enterCode()
        vm.submitCode("123456")
        controller.progress.trySend(PairingProgress.Failed("Wrong code"))
        runCurrent()
        assertEquals(PairingProgress.Failed("Wrong code"), running(vm))
    }

    @Test
    fun cameraDeniedStaysOnTheMethods() = test {
        val gate = FakePermissionGate()
        val vm = PairingViewModel(FakeSyncController(), gate)
        vm.scan()
        assertEquals(PermissionPrompt(SyncPermission.CAMERA), vm.prompt.value)
        vm.continuePermission()
        runCurrent()
        assertEquals(PairingState.ChooseMethod, vm.state.value)
        assertEquals(PermissionPrompt(SyncPermission.CAMERA, denied = true), vm.prompt.value)
        vm.scanned("ignored")
        runCurrent()
        assertEquals(PairingState.ChooseMethod, vm.state.value)
    }

    @Test
    fun codeEntryAcceptsExactlySixDigits() = test {
        val controller = FakeSyncController()
        val vm = PairingViewModel(controller, FakePermissionGate())
        vm.enterCode()
        listOf("12345", "1234567", "12a456", "12 456", "").forEach(vm::submitCode)
        runCurrent()
        assertEquals(PairingState.EnteringCode, vm.state.value)
        assertTrue(controller.pairings.isEmpty())
        vm.submitCode("048213")
        runCurrent()
        assertEquals(listOf<PairMethod>(PairMethod.EnterCode("048213")), controller.pairings)
    }

    @Test
    fun showCodeShowsTheCode() = test {
        val controller = FakeSyncController()
        val vm = PairingViewModel(controller, FakePermissionGate())
        vm.showCode()
        controller.progress.trySend(PairingProgress.ShowingCode("731904", expiresAt = 120_000))
        runCurrent()
        assertEquals(listOf<PairMethod>(PairMethod.ShowCode), controller.pairings)
        assertEquals(PairingProgress.ShowingCode("731904", 120_000), running(vm))
    }

    @Test
    fun leavingCancelsThePairing() = test {
        val controller = FakeSyncController()
        val vm = PairingViewModel(controller, FakePermissionGate())
        vm.showCode()
        runCurrent()
        assertFalse(controller.pairingCancelled)
        vm.reset()
        runCurrent()
        assertTrue(controller.pairingCancelled)
        assertEquals(PairingState.ChooseMethod, vm.state.value)
    }
}
