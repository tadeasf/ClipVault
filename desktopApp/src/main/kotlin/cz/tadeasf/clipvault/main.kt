package cz.tadeasf.clipvault

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import cz.tadeasf.clipvault.sync.SyncProtocol

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "ClipVault",
    ) {
        App(SyncProtocol.status())
    }
}