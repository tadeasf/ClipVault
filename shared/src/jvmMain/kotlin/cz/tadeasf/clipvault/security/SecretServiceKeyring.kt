@file:Suppress("FunctionName") // D-Bus member names are PascalCase; dbus-java uses the Kotlin name verbatim.

package cz.tadeasf.clipvault.security

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant

/**
 * freedesktop Secret Service (GNOME Keyring, KWallet 5.97+, KeePassXC) over the session bus via dbus-java.
 * Uses a "plain" session: the secret crosses only the local session bus, which the spec allows.
 * Items live in the `default` collection; an unlock prompt, if the keyring shows one, is awaited.
 */
class SecretServiceKeyring private constructor(private val service: String) : Keyring {

    override fun load(account: String): ByteArray? = withSession { bus, secrets, session ->
        val found = secrets.SearchItems(attributes(account))
        val items = found.first + bus.unlock(secrets, found.second)
        items.firstOrNull()?.let { bus.remote<SecretItem>(it.path).GetSecret(session).value }
    }

    override fun store(account: String, secret: ByteArray) = withSession { bus, secrets, session ->
        val collectionPath = secrets.ReadAlias("default")
        if (collectionPath.path == NO_OBJECT) throw IOException("Keyring has no default collection")
        bus.unlock(secrets, listOf(collectionPath))
        val properties = mapOf(
            "org.freedesktop.Secret.Item.Label" to Variant("ClipVault database key"),
            "org.freedesktop.Secret.Item.Attributes" to Variant(attributes(account), "a{ss}"),
        )
        val item = Secret(session, ByteArray(0), secret, "application/octet-stream")
        val created = bus.remote<SecretCollection>(collectionPath.path).CreateItem(properties, item, true)
        bus.complete(created.second)
    }

    fun clear(account: String) = withSession { bus, secrets, _ ->
        val found = secrets.SearchItems(attributes(account))
        (found.first + found.second).forEach { bus.complete(bus.remote<SecretItem>(it.path).Delete()) }
    }

    private fun attributes(account: String) = mapOf("service" to service, "account" to account)

    private fun <T> withSession(block: (DBusConnection, SecretService, DBusPath) -> T): T =
        DBusConnectionBuilder.forSessionBus().build().use { bus ->
            val secrets = bus.remote<SecretService>(SERVICE_PATH)
            val session = secrets.OpenSession("plain", Variant("")).second // closed with the connection
            block(bus, secrets, session)
        }

    companion object {
        private const val BUS_NAME = "org.freedesktop.secrets"
        private const val SERVICE_PATH = "/org/freedesktop/secrets"
        private const val NO_OBJECT = "/"
        private const val PROMPT_TIMEOUT_SECONDS = 120L

        /** Null when there is no session bus, no Secret Service, or no default collection. */
        fun detect(service: String = "cz.tadeasf.clipvault"): SecretServiceKeyring? = try {
            val keyring = SecretServiceKeyring(service)
            val hasDefault = keyring.withSession { _, secrets, _ -> secrets.ReadAlias("default").path != NO_OBJECT }
            keyring.takeIf { hasDefault }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            null // DBusException, DBusExecutionException, IOException: all mean "no usable keyring"
        }

        private inline fun <reified T : DBusInterface> DBusConnection.remote(path: String): T =
            getRemoteObject(BUS_NAME, path, T::class.java)

        private fun DBusConnection.unlock(secrets: SecretService, paths: List<DBusPath>): List<DBusPath> {
            if (paths.isEmpty()) return paths
            complete(secrets.Unlock(paths).second)
            return paths
        }

        /** Shows the keyring's prompt (if any) and waits for the user. Dismissal is an error. */
        private fun DBusConnection.complete(prompt: DBusPath) {
            if (prompt.path == NO_OBJECT) return
            val done = CompletableFuture<Boolean>()
            val handler = DBusSigHandler<SecretPrompt.Completed> { if (it.path == prompt.path) done.complete(it.dismissed) }
            addSigHandler(SecretPrompt.Completed::class.java, handler)
            try {
                remote<SecretPrompt>(prompt.path).Prompt("")
                if (done.get(PROMPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw IOException("Keyring prompt dismissed")
            } finally {
                removeSigHandler(SecretPrompt.Completed::class.java, handler)
            }
        }
    }
}

/** dbus-java maps multiple out-arguments onto a Tuple with positioned fields. */
class DBusPair<A, B>(@field:Position(0) @JvmField val first: A, @field:Position(1) @JvmField val second: B) : Tuple()

/** Secret Service `(oayays)` secret struct. */
class Secret(
    @field:Position(0) @JvmField val session: DBusPath,
    @field:Position(1) @JvmField val parameters: ByteArray,
    @field:Position(2) @JvmField val value: ByteArray,
    @field:Position(3) @JvmField val contentType: String,
) : Struct()

@JvmSuppressWildcards
@DBusInterfaceName("org.freedesktop.Secret.Service")
interface SecretService : DBusInterface {
    fun OpenSession(algorithm: String, input: Variant<*>): DBusPair<Variant<*>, DBusPath>
    fun SearchItems(attributes: Map<String, String>): DBusPair<List<DBusPath>, List<DBusPath>>
    fun Unlock(objects: List<DBusPath>): DBusPair<List<DBusPath>, DBusPath>
    fun ReadAlias(name: String): DBusPath
}

@JvmSuppressWildcards
@DBusInterfaceName("org.freedesktop.Secret.Collection")
interface SecretCollection : DBusInterface {
    fun CreateItem(properties: Map<String, Variant<*>>, secret: Secret, replace: Boolean): DBusPair<DBusPath, DBusPath>
}

@DBusInterfaceName("org.freedesktop.Secret.Item")
interface SecretItem : DBusInterface {
    fun GetSecret(session: DBusPath): Secret
    fun Delete(): DBusPath
}

@DBusInterfaceName("org.freedesktop.Secret.Prompt")
interface SecretPrompt : DBusInterface {
    fun Prompt(windowId: String)

    class Completed(path: String, @JvmField val dismissed: Boolean, @JvmField val result: Variant<*>) :
        DBusSignal(path, dismissed, result)
}
