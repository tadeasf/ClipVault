package cz.tadeasf.clipvault.sync

/** docs/sync/protocol.md section 4: a different major is refused, a higher minor is read leniently. */
object SyncProtocol {
    const val MAJOR = 1
    const val MINOR = 3 // 1.1: identity, pair_confirm, revoke (pairing.md); 1.2: ping, pong, Hello.syncEnabledAt; 1.3: Hello.name
    const val VERSION = MAJOR
    const val SERVICE_TYPE = "_clipvault._tcp"

    fun status(): String = "Sync available (protocol v$VERSION)"
}
