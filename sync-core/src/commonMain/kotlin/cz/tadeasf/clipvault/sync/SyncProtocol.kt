package cz.tadeasf.clipvault.sync

object SyncProtocol {
    const val VERSION = 1
    const val SERVICE_TYPE = "_clipvault._tcp"

    fun status(): String = "Sync available (protocol v$VERSION)"
}
