package cz.tadeasf.clipvault.sync.pairing

import cz.tadeasf.clipvault.db.ClipDatabase
import cz.tadeasf.clipvault.sync.crypto.Crypto
import cz.tadeasf.clipvault.sync.db.SyncDatabase
import cz.tadeasf.clipvault.sync.secure.LocalIdentity

class Peer(val publicKey: ByteArray, val name: String, val firstSeen: Long, val lastSeen: Long, val deviceId: String)

/**
 * This device's identity and its paired peers, in the encrypted SyncDatabase (pairing.md sections 3
 * and 4). The identity is created only by [enableSync].
 */
class PeerStore(private val db: SyncDatabase, private val c: Crypto, private val now: () -> Long) {
    private val q get() = db.syncQueries

    fun identity(): LocalIdentity? = q.identity().executeAsOneOrNull()?.let { LocalIdentity(it, c) }

    fun peers(): List<Peer> = q.peers().executeAsList().map { Peer(it.publicKey, it.name, it.firstSeen, it.lastSeen, deviceIdOf(c, it.publicKey)) }

    fun peer(publicKey: ByteArray): Peer? = peers().firstOrNull { it.publicKey.contentEquals(publicKey) }

    fun pendingRevokes(): List<ByteArray> = q.pendingRevokes(now()).executeAsList()

    fun isPendingRevoke(publicKey: ByteArray) = pendingRevokes().any { it.contentEquals(publicKey) }

    /** Who may complete a SESSION handshake with us: paired peers and pending revokes, never ourselves. */
    fun candidates(): List<ByteArray> {
        val own = identity()?.publicKey
        return (peers().map { it.publicKey } + pendingRevokes()).filterNot { own != null && it.contentEquals(own) }
    }

    /** After both PairConfirms. Re-pairing keeps firstSeen and updates the name. */
    fun addPeer(publicKey: ByteArray, name: String) = db.transaction {
        q.insertPeer(publicKey, name, now())
        q.renamePeer(name, now(), publicKey)
        q.deletePendingRevoke(publicKey)
    }

    fun seen(publicKey: ByteArray) = q.seenPeer(now(), publicKey)

    /** Local and immediate. Without a live session to send Revoke on, it waits 30 days for the peer to call. */
    fun unpair(publicKey: ByteArray, revokeDelivered: Boolean) = db.transaction {
        forget(publicKey)
        if (!revokeDelivered) q.addPendingRevoke(publicKey, now() + PENDING_REVOKE_MILLIS)
    }

    fun revokeDelivered(publicKey: ByteArray) = q.deletePendingRevoke(publicKey)

    /** The peer revoked us. */
    fun revokedBy(publicKey: ByteArray) = db.transaction { forget(publicKey) }

    private fun forget(publicKey: ByteArray) {
        q.deletePeer(publicKey)
        q.forgetCursor(deviceIdOf(c, publicKey))
    }

    internal fun createIdentity(): LocalIdentity = c.randomBytes(32).let { seed ->
        q.createIdentity(seed)
        LocalIdentity(seed, c)
    }

    internal fun syncedAnything(): Boolean = q.countPeers().executeAsOne() > 0 || q.countCursors().executeAsOne() > 0

    companion object {
        const val PENDING_REVOKE_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}

/**
 * Turns sync on (design.md 5.3.1) and returns this device's sync id. Creates the identity if needed;
 * when the stored device id is still the random pre-sync one, relabels this device's own rows to the
 * derived id. Refuses if anything was synced under the old id, which cannot happen through this API:
 * pairing needs the identity, and the identity only comes from here.
 */
fun enableSync(clipDb: ClipDatabase, peers: PeerStore, c: Crypto): String {
    val identity = peers.identity() ?: run {
        check(!peers.syncedAnything()) { "sync state exists without an identity" }
        peers.createIdentity()
    }
    val derived = deviceIdOf(c, identity.publicKey)
    clipDb.transaction {
        val old = clipDb.settingQueries.get(DEVICE_ID_KEY).executeAsOneOrNull()
        if (old == derived) return@transaction
        check(!peers.syncedAnything()) { "refusing to relabel: this device already synced as $old" }
        if (old != null) {
            clipDb.clipQueries.relabelDevice(derived, old)
            clipDb.clipMemberQueries.relabelDevice(derived, old)
            clipDb.metaEventQueries.relabelDevice(derived, old)
            clipDb.settingQueries.put(BEFORE_SYNC_KEY, old)
        }
        clipDb.settingQueries.put(DEVICE_ID_KEY, derived)
    }
    return derived
}

// Same key as cz.tadeasf.clipvault.settings.deviceId(), which keeps returning the stored id.
private const val DEVICE_ID_KEY = "deviceId"
private const val BEFORE_SYNC_KEY = "deviceId.beforeSync"
