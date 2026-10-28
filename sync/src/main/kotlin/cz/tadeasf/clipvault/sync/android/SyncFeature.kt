package cz.tadeasf.clipvault.sync.android

import cz.tadeasf.clipvault.sync.SyncProtocol

/** Entry point the `sync` flavor of :androidApp wires in. */
object SyncFeature {
    fun status(): String = SyncProtocol.status()
}
