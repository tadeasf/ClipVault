package cz.tadeasf.clipvault.sync.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import cz.tadeasf.clipvault.db.ClipDatabase
import cz.tadeasf.clipvault.detect.SensitiveCategory
import cz.tadeasf.clipvault.settings.deviceId
import cz.tadeasf.clipvault.sync.SyncConfig
import cz.tadeasf.clipvault.sync.SyncEngine
import cz.tadeasf.clipvault.sync.crypto.crypto
import cz.tadeasf.clipvault.sync.db.SyncDatabase
import cz.tadeasf.clipvault.sync.node.PairingEnd
import cz.tadeasf.clipvault.sync.node.PeerState
import cz.tadeasf.clipvault.sync.node.PeerStatus
import cz.tadeasf.clipvault.sync.node.SyncNode
import cz.tadeasf.clipvault.sync.node.TcpNetwork
import cz.tadeasf.clipvault.sync.pairing.PairingWindows
import cz.tadeasf.clipvault.sync.pairing.PeerStore
import cz.tadeasf.clipvault.sync.pairing.QrPayload
import cz.tadeasf.clipvault.sync.pairing.SyncPrefs
import cz.tadeasf.clipvault.sync.pairing.enableSync
import cz.tadeasf.clipvault.sync.pairing.loadSyncPrefs
import cz.tadeasf.clipvault.sync.pairing.sanitizeName
import cz.tadeasf.clipvault.sync.pairing.saveSyncPort
import cz.tadeasf.clipvault.sync.pairing.saveSyncPrefs
import cz.tadeasf.clipvault.sync.pairing.syncEnabledAt
import cz.tadeasf.clipvault.sync.secure.ConfirmRequest
import cz.tadeasf.clipvault.sync.secure.HandshakeGuard
import cz.tadeasf.clipvault.sync.secure.PairBy
import cz.tadeasf.clipvault.sync.secure.SecureEndpoint
import cz.tadeasf.clipvault.ui.sync.PairMethod
import cz.tadeasf.clipvault.ui.sync.PairingProgress
import cz.tadeasf.clipvault.ui.sync.PeerRow
import cz.tadeasf.clipvault.ui.sync.SyncController
import cz.tadeasf.clipvault.ui.sync.SyncIndicator
import cz.tadeasf.clipvault.ui.sync.SyncSnapshot
import java.net.Inet6Address
import java.net.InetAddress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One thread for the store, the node and the runtime's state, so local writes never interleave with applies (protocol.md 15). */
object SyncDispatcher {
    val db: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
}

/**
 * The sync flavor's process-wide sync side (step 8): settings, the [SyncNode] and who holds it (the service,
 * a catch-up worker, a pairing), the keep-alive policy and the snapshot the UI shows. All state lives on
 * [dispatcher].
 */
class SyncRuntime(
    private val context: Context,
    private val db: ClipDatabase,
    private val syncDb: SyncDatabase,
    /** Settings.excludedPackages: own clips from these apps are never sent. */
    private val excluded: () -> Set<String>,
    private val now: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = SyncDispatcher.db,
) : SyncController {
    private val c = crypto()
    private val peers = PeerStore(syncDb, c, now)
    private val windows = PairingWindows(c, now)
    private val guard = HandshakeGuard(now)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val network = NetworkWatch(context) { scope.launch { networkChanged() } }
    private val keepAlive = KeepAlive(scope, now, ::setServiceRunning)

    private var prefs = SyncPrefs()
    private val holders = mutableSetOf<String>()
    private var engine: SyncEngine? = null
    private var endpoint: SecureEndpoint? = null
    private var discovery: NsdDiscovery? = null
    private var statusLoop: Job? = null
    private var confirm: ((ConfirmRequest) -> Unit)? = null

    @Volatile
    private var node: SyncNode? = null

    private val snapshot = MutableStateFlow(SyncSnapshot())
    override val state: StateFlow<SyncSnapshot> = snapshot.asStateFlow()

    private val loaded = scope.launch {
        prefs = loadSyncPrefs(db)
        keepAlive.screen(context.getSystemService(PowerManager::class.java).isInteractive)
        keepAlive.networkAllowed(networkAllowed())
        keepAlive.enabled(prefs.enabled && peers.identity() != null)
        refresh()
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val on = intent.action == Intent.ACTION_SCREEN_ON
            scope.launch { keepAlive.screen(on) }
        }
    }.also {
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(it, filter, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(it, filter)
    }

    // --- the app's hooks ---

    /** Local writes go through the node so they never interleave with applies, and reach live peers at once. */
    suspend fun <T> write(block: suspend () -> T): T = node?.write(block) ?: withContext(dispatcher) { block() }

    /** A clip was stored here: keep the connection up for 5 more minutes, or start it. */
    fun localClip() {
        scope.launch { keepAlive.clip() }
    }

    /** The app came to the foreground, where Android lets it start the service. */
    fun appVisible() {
        scope.launch {
            loaded.join()
            keepAlive.poke()
            refresh()
        }
    }

    /** [holder] needs the node (true) or is done with it; the node runs while anyone holds it. */
    fun hold(holder: String, on: Boolean) {
        scope.launch {
            loaded.join()
            if (on) {
                holders += holder
                ensureNode()
            } else {
                holders -= holder
                if (holders.isEmpty()) stopNode()
            }
            refresh()
        }
    }

    /** One catch-up while the service is stopped: until every peer is live (and 3 s for its pushes), at most 60 s. */
    suspend fun catchUp() = withContext(dispatcher) {
        loaded.join()
        holders += WORKER
        try {
            if (!ensureNode()) return@withContext
            withTimeoutOrNull(CATCH_UP_MILLIS) {
                while (true) {
                    if (node?.status().orEmpty().all { it.state == PeerState.LIVE }) break // no peers: nothing to wait for
                    delay(250)
                }
                delay(3_000)
            }
        } finally {
            withContext(NonCancellable) {
                holders -= WORKER
                if (holders.isEmpty()) stopNode()
                refresh()
            }
        }
    }

    /** Tests only: stops everything this runtime started. */
    fun close() {
        runCatching { context.unregisterReceiver(screen) }
        network.watch(false)
        kotlinx.coroutines.runBlocking(dispatcher) { stopNode() }
        scope.cancel()
    }

    // --- SyncController ---

    override suspend fun setEnabled(enabled: Boolean) = onRuntime {
        if (enabled) {
            enableSync(db, peers, c) // identity on first use, relabels own rows (design 5.3.1)
            CatchUp.schedulePeriodic(context)
        } else {
            CatchUp.cancel(context)
            holders.clear()
            stopNode()
        }
        update { it.copy(enabled = enabled) }
        keepAlive.enabled(enabled)
    }

    override suspend fun setDeviceName(name: String) = onRuntime {
        update { it.copy(name = sanitizeName(name)) }
        endpoint?.name = deviceName()
        reconfigure()
    }

    override suspend fun setSensitiveOptIn(category: SensitiveCategory, optIn: Boolean) = onRuntime {
        update { it.copy(sensitiveOptIn = if (optIn) it.sensitiveOptIn + category else it.sensitiveOptIn - category) }
        reconfigure()
    }

    override suspend fun setWifiAllowlist(enabled: Boolean) = onRuntime {
        update { it.copy(wifiAllowlist = enabled) }
        networkChanged()
    }

    override suspend fun setSsidAllowed(ssid: String, allowed: Boolean) = onRuntime {
        update { it.copy(ssids = if (allowed) it.ssids + ssid else it.ssids - ssid) }
        networkChanged()
    }

    override suspend fun unpair(id: String) = onRuntime {
        val peer = peers.peers().firstOrNull { it.deviceId == id } ?: return@onRuntime
        node?.unpair(peer.publicKey) ?: peers.unpair(peer.publicKey, revokeDelivered = false)
    }

    override fun pair(method: PairMethod): Flow<PairingProgress> = channelFlow {
        loaded.join()
        holders += PAIRING
        var pending: ConfirmRequest? = null
        var confirmed = false
        try {
            if (!ensureNode()) {
                send(PairingProgress.Failed(if (networkAllowed()) "Sync is off" else "Not on an allowed Wi-Fi network"))
                return@channelFlow
            }
            val node = node!!
            val ends = Channel<PairingEnd>(Channel.UNLIMITED)
            node.onPairingEnded = { ends.trySend(it) }
            confirm = { request ->
                pending = request
                trySend(PairingProgress.Confirm(request.words, request.peerName) { yes ->
                    launch {
                        pending = null
                        confirmed = yes
                        node.run { request.answer(yes) }
                        if (yes) send(PairingProgress.WaitingForPeer)
                    }
                })
            }
            var expiresAt = Long.MAX_VALUE
            var shownCode: String? = null
            when (method) {
                is PairMethod.ScanQr -> {
                    val qr = QrPayload.parse(method.text.trim())
                    when {
                        qr == null -> return@channelFlow send(PairingProgress.Failed("That is not a ClipVault pairing code"))
                        qr.expiresAt * 1000 < now() -> return@channelFlow send(PairingProgress.Failed("That QR code has expired. Show a new one."))
                    }
                    send(PairingProgress.Connecting)
                    val reached = qr!!.addresses.any { node.pair(hostPort(it, qr.port), PairBy.Qr(qr.token)) }
                    if (!reached) return@channelFlow send(PairingProgress.Failed("Couldn't reach the other device. Is it on the same network?"))
                }
                is PairMethod.EnterCode -> {
                    send(PairingProgress.Connecting)
                    val target = discovery?.pairingCandidates()?.firstOrNull()
                        ?: return@channelFlow send(PairingProgress.Failed("No device showing a code was found on this network"))
                    if (!node.pair(target, PairBy.Code(method.code))) return@channelFlow send(PairingProgress.Failed("Couldn't reach the other device"))
                }
                PairMethod.ShowQr -> {
                    val port = node.listeningPort ?: return@channelFlow send(PairingProgress.Failed("Not connected to a network"))
                    val addresses = network.listenAddresses().take(4).map { InetAddress.getByName(it).address }
                    val payload = node.run { windows.openQr(addresses, port) }
                    expiresAt = payload.expiresAt * 1000
                    send(PairingProgress.ShowingQr(qrImage(payload.toText()), expiresAt))
                }
                PairMethod.ShowCode -> {
                    shownCode = node.run { windows.openCode() }
                    expiresAt = now() + PairingWindows.TTL_MILLIS
                    send(PairingProgress.ShowingCode(shownCode, expiresAt))
                }
            }
            while (true) {
                val end = withTimeoutOrNull(500) { ends.receive() }
                if (end?.peerName != null) return@channelFlow send(PairingProgress.Paired(end.peerName!!))
                if (method == PairMethod.ShowCode) {
                    // A failed attempt replaces the code (pairing.md 2.2): show the new one, keep waiting.
                    val code = node.run { windows.code.takeIf { windows.codeOpen } }
                    if (code != null && code != shownCode) {
                        shownCode = code
                        expiresAt = now() + PairingWindows.TTL_MILLIS
                        send(PairingProgress.ShowingCode(code, expiresAt))
                        continue
                    }
                }
                if (end != null) return@channelFlow send(PairingProgress.Failed(reasonText(end.reason)))
                if (pending == null && !confirmed && now() > expiresAt + WINDOW_GRACE_MILLIS) {
                    return@channelFlow send(PairingProgress.Failed("The pairing window closed after 2 minutes"))
                }
            }
        } finally {
            withContext(NonCancellable) {
                pending?.let { request -> node?.run { request.answer(false) } }
                confirm = null
                node?.onPairingEnded = null
                node?.run { windows.consumeToken(); windows.closeCode() }
                holders -= PAIRING
                if (holders.isEmpty()) stopNode()
                refresh()
            }
        }
    }.flowOn(dispatcher)

    // --- node ---

    private suspend fun ensureNode(): Boolean {
        if (node != null) return true
        if (!prefs.enabled || peers.identity() == null || !networkAllowed()) return false
        val engine = SyncEngine(db, syncDb, config()).also { engine = it }
        engine.onRemoteClip = { keepAlive.clip() }
        val endpoint = SecureEndpoint(c, engine, peers, windows, guard, now, deviceName()) { request ->
            confirm?.invoke(request) ?: request.answer(false)
        }.also { endpoint = it }
        val discovery = NsdDiscovery(context, c).also { discovery = it }
        node = SyncNode(endpoint, TcpNetwork(), discovery, network::listenAddresses, scope, port = prefs.port ?: 0, log = { Log.i(TAG, it) }, dispatcher = dispatcher)
            .also {
                it.onListening = { port ->
                    prefs = prefs.copy(port = port)
                    saveSyncPort(db, port)
                }
                it.start()
            }
        network.watch(true)
        statusLoop = scope.launch {
            while (true) {
                refresh()
                delay(1_000)
            }
        }
        return true
    }

    private suspend fun stopNode() {
        statusLoop?.cancel()
        statusLoop = null
        network.watch(false)
        node?.stop()
        node = null
        engine = null
        endpoint = null
        discovery = null
    }

    private suspend fun reconfigure() {
        node?.reconfigure(config())
    }

    private suspend fun networkChanged() {
        val allowed = networkAllowed()
        keepAlive.networkAllowed(allowed)
        if (!allowed) {
            stopNode()
        } else if (holders.isNotEmpty()) {
            ensureNode()
            node?.networkChanged()
        }
        refresh()
    }

    private fun setServiceRunning(run: Boolean): Boolean = try {
        if (run) context.startForegroundService(SyncService.intent(context)) else context.stopService(SyncService.intent(context))
        true
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException (Android 12+): started from the background, e.g. on screen on.
        Log.i(TAG, "sync service not started from the background; catching up with WorkManager")
        CatchUp.expedite(context)
        false
    }

    private fun config() = SyncConfig(
        deviceId = deviceId(db),
        syncEnabledAt = syncEnabledAt(db) ?: now(),
        sensitiveOptIn = prefs.sensitiveOptIn,
        isExcludedSource = { it != null && it in excluded() },
        name = deviceName(),
    )

    private fun deviceName(): String = prefs.name
        ?: Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf(String::isNotBlank)
        ?: Build.MODEL

    private fun networkAllowed(): Boolean = !prefs.wifiAllowlist || network.currentSsid() in prefs.ssids

    private suspend fun <T> onRuntime(block: suspend () -> T): T = withContext(dispatcher) {
        loaded.join()
        block().also { refresh() }
    }

    private fun update(change: (SyncPrefs) -> SyncPrefs) {
        prefs = change(prefs)
        saveSyncPrefs(db, prefs)
    }

    // --- snapshot ---

    private suspend fun refresh() {
        val live = node?.status().orEmpty()
        val paired = peers.peers()
        val rows = paired.map { p ->
            val s = live.firstOrNull { it.deviceId == p.deviceId }
            PeerRow(p.deviceId, p.name, (s?.lastSeen ?: p.lastSeen).takeIf { it > 0 }, stateText(s), s?.state == PeerState.LIVE)
        }
        val allowed = networkAllowed()
        val liveCount = live.count { it.state == PeerState.LIVE }
        val busy = live.any { it.state in BUSY }
        val (indicator, line) = when {
            !prefs.enabled -> SyncIndicator.OFF to "Off"
            !allowed -> SyncIndicator.OFF to "Paused: not on an allowed Wi-Fi network"
            paired.isEmpty() -> SyncIndicator.SYNCED to "On. No paired devices yet."
            node == null -> SyncIndicator.SYNCED to "On. Connects while the screen is on, checks every 15 minutes otherwise."
            liveCount == paired.size -> SyncIndicator.SYNCED to "Synced with ${devices(liveCount)}"
            busy -> SyncIndicator.SYNCING to "Syncing…"
            liveCount > 0 -> SyncIndicator.SYNCED to "Synced with $liveCount of ${devices(paired.size)}"
            else -> SyncIndicator.ERROR to "Can't reach ${if (paired.size == 1) paired.single().name else "your devices"}"
        }
        snapshot.value = SyncSnapshot(
            enabled = prefs.enabled,
            deviceName = deviceName(),
            peers = rows,
            sensitiveOptIn = prefs.sensitiveOptIn,
            wifiAllowlist = prefs.wifiAllowlist,
            allowedSsids = prefs.ssids,
            currentSsid = network.currentSsid(),
            indicator = indicator,
            statusLine = line,
        )
    }

    private fun stateText(s: PeerStatus?): String = when (s?.state) {
        null -> if (node == null) "not connected now" else "looking for it"
        PeerState.LIVE -> "connected"
        PeerState.CATCHING_UP -> "syncing"
        PeerState.DISCOVERING, PeerState.CONNECTING, PeerState.HANDSHAKE -> "connecting"
        PeerState.BACKOFF -> "not reachable, retrying in ${((s.backoffUntil ?: now()) - now()).coerceAtLeast(0) / 1000} s"
    }

    private fun devices(n: Int) = if (n == 1) "1 device" else "$n devices"

    private fun reasonText(reason: String?): String = when {
        reason == null -> "Pairing failed"
        "rejected" in reason -> "The words were rejected"
        "not confirmed in time" in reason -> "The words were not confirmed within 2 minutes"
        else -> "Pairing failed ($reason)"
    }

    private fun hostPort(address: ByteArray, port: Int): String {
        val a = InetAddress.getByAddress(address)
        return if (a is Inet6Address) "[${a.hostAddress}]:$port" else "${a.hostAddress}:$port"
    }

    companion object {
        private const val TAG = "ClipVaultSync"
        private const val WORKER = "worker"
        private const val PAIRING = "pairing"
        private const val CATCH_UP_MILLIS = 60_000L
        private const val WINDOW_GRACE_MILLIS = 5_000L
        private val BUSY = setOf(PeerState.DISCOVERING, PeerState.CONNECTING, PeerState.HANDSHAKE, PeerState.CATCHING_UP)

        @Volatile
        private var instance: SyncRuntime? = null
        private var factory: ((Context) -> SyncRuntime)? = null

        /** Called at app start: how to build the runtime the first time something needs it. */
        fun provide(create: (Context) -> SyncRuntime) {
            factory = create
        }

        fun of(context: Context): SyncRuntime? = instance ?: synchronized(this) {
            instance ?: factory?.invoke(context.applicationContext)?.also { instance = it }
        }

        /** Tests: run the service and worker against a runtime on throwaway databases (null: back to the factory). */
        fun install(runtime: SyncRuntime?) {
            instance = runtime
        }
    }
}
