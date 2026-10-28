package cz.tadeasf.clipvault.sync

/** docs/sync/protocol.md section 4: a different major is refused, a higher minor is read leniently. */
object SyncProtocol {
    const val MAJOR = 1
    const val MINOR = 0
    const val VERSION = MAJOR
    const val SERVICE_TYPE = "_clipvault._tcp"

    fun status(): String = "Sync available (protocol v$VERSION)"
}
