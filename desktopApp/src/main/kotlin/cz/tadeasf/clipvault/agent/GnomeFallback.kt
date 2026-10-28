@file:Suppress("FunctionName") // D-Bus member names are PascalCase; dbus-java uses the Kotlin name verbatim.

package cz.tadeasf.clipvault.agent

import java.util.concurrent.Executors
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.Variant

/**
 * Fallback capture when the CopyQ command is not installed: registers as a client of CopyQ's GNOME
 * extension (design 6.3), which calls ClipboardChanged on every owner change; we then read text/plain.
 * The extension carries no window information, so these clips have no source.
 */
class GnomeFallback {
    private var connection: DBusConnection? = null
    private val reader = Executors.newSingleThreadExecutor { Thread(it, "gnome-clipboard").apply { isDaemon = true } }

    /** True when the extension accepted us; [onClip] then receives every new text clip. */
    fun start(onClip: (IngestRequest) -> Unit): Boolean = runCatching {
        val bus = DBusConnectionBuilder.forSessionBus().build()
        val extension = bus.getRemoteObject(SERVICE, OBJECT_PATH, GnomeClipboard::class.java)
        bus.exportObject(Client { reader.execute { read(extension)?.let(onClip) } })
        extension.RegisterClipboardClient(CLIPBOARD)
        connection = bus
        true
    }.getOrDefault(false)

    private fun read(extension: GnomeClipboard): IngestRequest? {
        val data = runCatching { extension.GetClipboardData(CLIPBOARD, listOf(TEXT)) }.getOrNull() ?: return null
        val bytes = data[TEXT]?.value as? ByteArray
        return if (bytes == null) IngestRequest(ByteArray(0), nonText = true) else IngestRequest(bytes)
    }

    /** The object the extension calls back. */
    private class Client(private val changed: () -> Unit) : GnomeClipboardClient {
        override fun ClipboardChanged(clipboardType: Int) {
            if (clipboardType == CLIPBOARD) changed()
        }

        override fun getObjectPath() = CLIENT_PATH
    }

    private companion object {
        const val SERVICE = "com.github.hluk.copyq.GnomeClipboard"
        const val OBJECT_PATH = "/com/github/hluk/copyq/GnomeClipboard"
        const val CLIENT_PATH = "/com/github/hluk/copyq/GnomeClipboardClient"
        const val CLIPBOARD = 0
        const val TEXT = "text/plain"
    }
}

@JvmSuppressWildcards
@DBusInterfaceName("com.github.hluk.CopyQ.GnomeClipboard1")
interface GnomeClipboard : DBusInterface {
    fun RegisterClipboardClient(clipboardTypes: Int)
    fun GetClipboardData(clipboardType: Int, formats: List<String>): Map<String, Variant<*>>
}

@DBusInterfaceName("com.github.hluk.CopyQ.GnomeClipboardClient1")
interface GnomeClipboardClient : DBusInterface {
    fun ClipboardChanged(clipboardType: Int)
}
