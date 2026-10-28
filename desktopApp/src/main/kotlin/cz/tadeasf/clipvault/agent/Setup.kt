package cz.tadeasf.clipvault.agent

import cz.tadeasf.clipvault.db.ClipDatabase
import cz.tadeasf.clipvault.security.DesktopDbKeyProvider
import cz.tadeasf.clipvault.security.PassphraseKeyFile
import cz.tadeasf.clipvault.security.KeyUnavailableException
import cz.tadeasf.clipvault.security.SecretServiceKeyring
import cz.tadeasf.clipvault.security.keyFor
import cz.tadeasf.clipvault.storage.JvmDriverFactory
import cz.tadeasf.clipvault.storage.clipDatabase
import java.io.File
import java.sql.SQLException

/**
 * Opens the encrypted store. Key from the Secret Service; without one, the passphrase file, which needs
 * a terminal to ask in. A headless daemon (systemd) without a keyring fails with a clear message.
 */
fun openStore(paths: AgentPaths): ClipDatabase {
    paths.prepare()
    val keyFile = PassphraseKeyFile(paths.keyFile, ::askPassphrase)
    val provider = DesktopDbKeyProvider(SecretServiceKeyring.detect(), keyFile)
    val drivers = JvmDriverFactory(paths.database)
    val key = try {
        provider.keyFor(drivers.exists())
    } catch (_: KeyUnavailableException) {
        throw StoreKeyException.missing(paths)
    }
    return try {
        clipDatabase(drivers.open(key))
    } catch (e: SQLException) {
        if (e.message?.contains("SQLITE_NOTADB") == true) throw StoreKeyException.wrong(paths) else throw e
    }
}

/**
 * The store's key is missing or does not open it. Restarting cannot fix that, so the daemon exits with
 * [EXIT_CODE] and the unit's RestartPreventExitStatus stops systemd from looping.
 */
class StoreKeyException(message: String) : Exception(message) {
    companion object {
        /** EX_CONFIG from sysexits.h. */
        const val EXIT_CODE = 78

        private fun advice(paths: AgentPaths) =
            "Move ${paths.database} aside to start a new, empty history, then `systemctl --user restart clipvault-agent`. Not restarting on my own."

        fun missing(paths: AgentPaths) = StoreKeyException(
            "clipvault-agent: the history store exists but its key is not in the keyring (keyring replaced or reset?). ${advice(paths)}",
        )

        fun wrong(paths: AgentPaths) = StoreKeyException(
            "clipvault-agent: the key in the keyring does not open the history store. ${advice(paths)}",
        )
    }
}

private fun askPassphrase(): CharArray = System.console()?.readPassword("ClipVault passphrase: ")
    ?: throw StoreKeyException(
        "clipvault-agent: no Secret Service (GNOME Keyring, KWallet, KeePassXC) and no terminal to ask for a passphrase. " +
            "Unlock or install a keyring, or run `clipvault-agent run` once in a terminal. Not restarting on my own.",
    )

/** The launcher path, for commands that must call us back (CopyQ command, systemd unit). */
fun agentBinary(): String? = System.getProperty("jpackage.app-path") ?: System.getenv("CLIPVAULT_AGENT_BIN")

object SystemdUnit {
    const val NAME = "clipvault-agent.service"

    fun text(agentBinary: String) = """
        |[Unit]
        |Description=ClipVault agent (encrypted clipboard history, CopyQ bridge)
        |PartOf=graphical-session.target
        |After=graphical-session.target
        |
        |[Service]
        |ExecStart="$agentBinary" run
        |Restart=on-failure
        |RestartSec=5
        |# SIGTERM ends the JVM with 143; that is a clean stop, not a failure.
        |SuccessExitStatus=143
        |# 78: the store's key is missing or wrong; restarting cannot fix that (see the journal line).
        |RestartPreventExitStatus=${StoreKeyException.EXIT_CODE}
        |
        |[Install]
        |WantedBy=graphical-session.target
        |
    """.trimMargin()

    fun file(): File {
        val config = System.getenv("XDG_CONFIG_HOME")?.takeIf(String::isNotBlank) ?: "${System.getProperty("user.home")}/.config"
        return File(config, "systemd/user/$NAME")
    }

    /** Writes the unit and enables it now; returns systemctl's exit code. */
    fun install(agentBinary: String): Int {
        file().apply { parentFile.mkdirs() }.writeText(text(agentBinary))
        val reload = ProcessBuilder("systemctl", "--user", "daemon-reload").inheritIO().start().waitFor()
        if (reload != 0) return reload
        return ProcessBuilder("systemctl", "--user", "enable", "--now", NAME).inheritIO().start().waitFor()
    }
}

/** Shown by `install-copyq` while CopyQ keeps its tabs in plaintext. */
const val ENCRYPTION_ADVICE = """CopyQ stores its tabs unencrypted, so clips in its history (including phone clips the
agent writes there) are plaintext on disk. ClipVault's own store is encrypted either way.
To encrypt CopyQ's tabs yourself:
  1. CopyQ > Preferences > General: check "Encrypt tabs".
  2. Set a password when CopyQ asks.
  3. Keep "Use external key store" checked, so GNOME Keyring / KWallet unlocks it at login.
Then `clipvault-agent status` shows "copyq: tabs encrypted"."""
