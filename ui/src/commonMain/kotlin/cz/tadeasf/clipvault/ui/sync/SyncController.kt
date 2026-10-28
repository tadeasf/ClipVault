package cz.tadeasf.clipvault.ui.sync

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cz.tadeasf.clipvault.detect.SensitiveCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// The sync UI's contract with the host (step 8). :ui holds the screens and ViewModels but no sync code: the
// sync flavor implements these in :sync, the vault flavor passes no SyncUi at all.

/** The history list header's indicator. */
enum class SyncIndicator { OFF, SYNCING, SYNCED, ERROR }

data class PeerRow(
    /** The peer's device id (hex); the key for [SyncController.unpair]. */
    val id: String,
    val name: String,
    /** Epoch millis; null when never connected since pairing. */
    val lastSeen: Long?,
    /** Short human state: "connected", "connecting", "retrying in 30 s", "not running". */
    val state: String,
    val connected: Boolean,
)

data class SyncSnapshot(
    val enabled: Boolean = false,
    val deviceName: String = "",
    val peers: List<PeerRow> = emptyList(),
    val sensitiveOptIn: Set<SensitiveCategory> = emptySet(),
    val wifiAllowlist: Boolean = false,
    val allowedSsids: Set<String> = emptySet(),
    /** The Wi-Fi network the phone is on; null when unknown (no location permission, not on Wi-Fi). */
    val currentSsid: String? = null,
    val indicator: SyncIndicator = SyncIndicator.OFF,
    /** One line for settings: "Synced with 1 device", "Not on an allowed Wi-Fi network", ... */
    val statusLine: String = "Off",
)

/** A QR code as modules; true is dark. */
class QrImage(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]
}

/** How this phone pairs: as the initiator (scan, type a code) or the responder (show a QR, show a code). */
sealed interface PairMethod {
    data class ScanQr(val text: String) : PairMethod

    data class EnterCode(val code: String) : PairMethod

    data object ShowQr : PairMethod

    data object ShowCode : PairMethod
}

/** One pairing attempt as it unfolds (pairing.md). The flow completes after [Paired] or [Failed]. */
sealed interface PairingProgress {
    /** Looking for the device showing a code (mDNS), or connecting and running the handshake. */
    data object Connecting : PairingProgress

    data class ShowingQr(val qr: QrImage, val expiresAt: Long) : PairingProgress

    data class ShowingCode(val code: String, val expiresAt: Long) : PairingProgress

    /** Both devices show the same six words; [answer] confirms (true) or rejects. */
    class Confirm(val words: List<String>, val peerName: String, val answer: (Boolean) -> Unit) : PairingProgress

    /** This side confirmed; waiting for the other device's user. */
    data object WaitingForPeer : PairingProgress

    data class Paired(val peerName: String) : PairingProgress

    data class Failed(val reason: String) : PairingProgress
}

interface SyncController {
    val state: StateFlow<SyncSnapshot>

    /** Turning on creates the identity on first use (design 5.3.1) and starts the node; off stops it. */
    suspend fun setEnabled(enabled: Boolean)

    suspend fun setDeviceName(name: String)

    suspend fun setSensitiveOptIn(category: SensitiveCategory, optIn: Boolean)

    suspend fun setWifiAllowlist(enabled: Boolean)

    suspend fun setSsidAllowed(ssid: String, allowed: Boolean)

    suspend fun unpair(id: String)

    /** Runs one pairing; cancelling the collection closes the window or the connection. */
    fun pair(method: PairMethod): Flow<PairingProgress>
}

enum class SyncPermission { LOCAL_NETWORK, CAMERA, LOCATION }

/** Android runtime permissions, asked by the activity. [isGranted] is true where the OS doesn't need one. */
interface PermissionGate {
    fun isGranted(permission: SyncPermission): Boolean

    /** Shows the system dialog; true when granted. */
    suspend fun request(permission: SyncPermission): Boolean

    /** Opens the app's system settings page, for a permission the user denied for good. */
    fun openAppSettings()
}

/** What the sync flavor hands the shared UI. [scanner] shows the camera and reports each decoded QR text. */
class SyncUi(
    val controller: SyncController,
    val permissions: PermissionGate,
    val scanner: @Composable (modifier: Modifier, onText: (String) -> Unit) -> Unit,
)
