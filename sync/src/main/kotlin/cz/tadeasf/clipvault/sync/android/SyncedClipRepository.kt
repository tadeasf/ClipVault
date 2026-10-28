package cz.tadeasf.clipvault.sync.android

import cz.tadeasf.clipvault.model.ClipDraft
import cz.tadeasf.clipvault.history.ClipRepository
import cz.tadeasf.clipvault.model.RetentionPolicy

/** The app's writes through [SyncRuntime.write]: one thread with the node's applies, and live peers get them at once. */
class SyncedClipRepository(private val inner: ClipRepository, private val runtime: () -> SyncRuntime?) : ClipRepository by inner {
    override suspend fun insert(draft: ClipDraft) = gate { inner.insert(draft) }

    override suspend fun setPinned(id: String, pinned: Boolean) = gate { inner.setPinned(id, pinned) }

    override suspend fun delete(id: String) = gate { inner.delete(id) }

    override suspend fun applyRetention(policy: RetentionPolicy) = gate { inner.applyRetention(policy) }

    private suspend fun <T> gate(block: suspend () -> T): T = runtime()?.write(block) ?: block()
}
