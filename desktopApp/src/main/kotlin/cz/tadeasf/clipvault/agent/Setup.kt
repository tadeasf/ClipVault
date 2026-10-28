package cz.tadeasf.clipvault.agent

import cz.tadeasf.clipvault.db.ClipDatabase
import cz.tadeasf.clipvault.security.DesktopDbKeyProvider
import cz.tadeasf.clipvault.security.PassphraseKeyFile
import cz.tadeasf.clipvault.security.SecretServiceKeyring
import cz.tadeasf.clipvault.security.keyFor
import cz.tadeasf.clipvault.storage.JvmDriverFactory
import cz.tadeasf.clipvault.storage.clipDatabase
import java.io.File

/**
 * Opens the encrypted store. Key from the Secret Service; without one, the passphrase file, which needs
 * a terminal to ask in. A headless daemon (systemd) without a keyring fails with a clear message.
 */
fun openStore(paths: AgentPaths): ClipDatabase {
    paths.prepare()
    val keyFile = PassphraseKeyFile(paths.keyFile, ::askPassphrase)
    val provider = DesktopDbKeyProvider(SecretServiceKeyring.detect(), keyFile)
    val drivers = JvmDriverFactory(paths.database)
    return clipDatabase(drivers.open(provider.keyFor(drivers.exists())))
}

private fun askPassphrase(): CharArray = System.console()?.readPassword("ClipVault passphrase: ")
    ?: throw IllegalStateException(
        "No Secret Service (GNOME Keyring, KWallet, KeePassXC) and no terminal to ask for a passphrase. " +
            "Unlock or install a keyring, or run `clipvault-agent run` once in a terminal.",
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
To encrypt CopyQ's tabs: CopyQ > Preferences > History (or General, depending on version) >
check "Encrypt tabs", set a password, and keep "Use external key store" on so GNOME Keyring /
KWallet unlocks it at login. Then `clipvault-agent status` shows "copyq: tabs encrypted"."""
