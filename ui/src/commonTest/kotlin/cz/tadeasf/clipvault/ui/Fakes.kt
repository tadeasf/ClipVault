package cz.tadeasf.clipvault.ui

import androidx.paging.PagingSource
import androidx.paging.PagingState
import cz.tadeasf.clipvault.history.ClipRepository
import cz.tadeasf.clipvault.model.Clip
import cz.tadeasf.clipvault.model.ClipCounts
import cz.tadeasf.clipvault.model.ClipDraft
import cz.tadeasf.clipvault.model.ClipFilter
import cz.tadeasf.clipvault.model.ContentType
import cz.tadeasf.clipvault.model.InsertResult
import cz.tadeasf.clipvault.model.RetentionPolicy
import cz.tadeasf.clipvault.settings.Settings
import cz.tadeasf.clipvault.settings.SettingsRepository
import cz.tadeasf.clipvault.detect.SensitiveCategory
import cz.tadeasf.clipvault.ui.sync.PairMethod
import cz.tadeasf.clipvault.ui.sync.PairingProgress
import cz.tadeasf.clipvault.ui.sync.PermissionGate
import cz.tadeasf.clipvault.ui.sync.SyncController
import cz.tadeasf.clipvault.ui.sync.SyncPermission
import cz.tadeasf.clipvault.ui.sync.SyncSnapshot
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map

fun clip(id: String, content: String = id, sensitive: Boolean = false, pinned: Boolean = false) = Clip(
    id = id, deviceId = "d", createdAt = 0, contentType = ContentType.PLAIN, contentHash = id, content = content,
    sensitive = sensitive, pinned = pinned, deleted = false, lamportClock = 0, seq = 0, categories = emptySet(),
    sourcePackage = null,
)

class FakeClipRepository(vararg initial: Clip) : ClipRepository {
    val clips = MutableStateFlow(initial.associateBy { it.id })
    val inserted = mutableListOf<ClipDraft>()
    val filters = mutableListOf<ClipFilter>()

    override suspend fun insert(draft: ClipDraft): InsertResult {
        inserted += draft
        val id = "new-${inserted.size}"
        clips.value += id to clip(id, draft.content)
        return InsertResult(id, duplicate = false, sensitive = false)
    }

    override suspend fun get(id: String) = clips.value[id]
    override suspend fun search(text: String, includeSensitive: Boolean, limit: Long) = emptyList<Clip>()

    override fun pagingSource(filter: ClipFilter): PagingSource<Int, Clip> {
        filters += filter
        return object : PagingSource<Int, Clip>() {
            override fun getRefreshKey(state: PagingState<Int, Clip>): Int? = null
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Clip> =
                LoadResult.Page(clips.value.values.toList(), prevKey = null, nextKey = null)
        }
    }

    override fun counts(): Flow<ClipCounts> = clips.map { all ->
        val live = all.values.filterNot { it.deleted }
        ClipCounts(live.size.toLong(), live.count { it.sensitive }.toLong())
    }

    override suspend fun setPinned(id: String, pinned: Boolean) = edit(id) { it.copy(pinned = pinned) }
    override suspend fun delete(id: String) = edit(id) { it.copy(deleted = true, content = "") }
    override suspend fun applyRetention(policy: RetentionPolicy) = Unit

    private fun edit(id: String, change: (Clip) -> Clip) {
        clips.value[id]?.let { clips.value += id to change(it) }
    }
}

class FakeSettingsRepository(initial: Settings = Settings()) : SettingsRepository {
    override val settings = MutableStateFlow(initial)
    override suspend fun update(transform: (Settings) -> Settings) {
        settings.value = transform(settings.value)
    }
}

class FakePlatform(accessibilityOn: Boolean? = false) : Platform {
    val copied = mutableListOf<Pair<String, Boolean>>()
    val shared = mutableListOf<String>()
    var accessibilityEnabled = accessibilityOn

    override fun copyToClipboard(text: String, sensitive: Boolean) {
        copied += text to sensitive
    }

    override fun share(text: String) {
        shared += text
    }

    override val appInfo = "ClipVault test"

    override val accessibility: AccessibilityControl? = if (accessibilityOn == null) null else object : AccessibilityControl {
        override fun isEnabled() = accessibilityEnabled == true
        override fun openSystemSettings() = Unit
    }

    override fun installedApps() = listOf(AppEntry("com.whatsapp", "WhatsApp"), AppEntry("org.mozilla.firefox", "Firefox"))
}

/** Exposes the state as a value so tests don't need casts everywhere. */
inline fun <reified T> StateFlow<*>.valueAs(): T = value as T

class FakeSyncController(initial: SyncSnapshot = SyncSnapshot()) : SyncController {
    override val state = MutableStateFlow(initial)
    val pairings = mutableListOf<PairMethod>()

    /** What the next pairing emits; the flow ends after Paired or Failed, like the real one. */
    val progress = Channel<PairingProgress>(Channel.UNLIMITED)
    var pairingCancelled = false

    override suspend fun setEnabled(enabled: Boolean) = state.update { it.copy(enabled = enabled) }
    override suspend fun setDeviceName(name: String) = state.update { it.copy(deviceName = name) }
    override suspend fun setSensitiveOptIn(category: SensitiveCategory, optIn: Boolean) = state.update {
        it.copy(sensitiveOptIn = if (optIn) it.sensitiveOptIn + category else it.sensitiveOptIn - category)
    }
    override suspend fun setWifiAllowlist(enabled: Boolean) = state.update { it.copy(wifiAllowlist = enabled) }
    override suspend fun setSsidAllowed(ssid: String, allowed: Boolean) = state.update {
        it.copy(allowedSsids = if (allowed) it.allowedSsids + ssid else it.allowedSsids - ssid)
    }
    override suspend fun unpair(id: String) = state.update { s -> s.copy(peers = s.peers.filterNot { it.id == id }) }

    override fun pair(method: PairMethod): Flow<PairingProgress> = flow {
        pairings += method
        try {
            for (p in progress) {
                emit(p)
                if (p is PairingProgress.Paired || p is PairingProgress.Failed) break
            }
        } catch (e: CancellationException) {
            pairingCancelled = true
            throw e
        }
    }
}

class FakePermissionGate(vararg granted: SyncPermission) : PermissionGate {
    val granted = granted.toMutableSet()
    val requested = mutableListOf<SyncPermission>()
    var grantOnRequest = false
    var settingsOpened = 0

    override fun isGranted(permission: SyncPermission) = permission in granted

    override suspend fun request(permission: SyncPermission): Boolean {
        requested += permission
        if (grantOnRequest) granted += permission
        return grantOnRequest
    }

    override fun openAppSettings() {
        settingsOpened++
    }
}
