package cz.tadeasf.clipvault.agent

import cz.tadeasf.clipvault.security.Keyring
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * Turns on CopyQ 17's built-in tab encryption without its password dialog (design 11.7).
 *
 * CopyQ reads the password from `COPYQ_PASSWORD` in the *server's* environment and, with
 * `use_key_store=true`, saves it to the system keyring (service `com.github.hluk.copyq`). It ignores
 * whether that save worked, so we first keep our own backup of the password in the keyring, then:
 * restart CopyQ with the variable, `config encrypt_tabs true`, restart it again without the variable,
 * and check that every tab still loads with the same number of items: that proves it decrypted them
 * with the password from the keyring. Only counts are read, never items.
 */
class CopyqEncryption(private val copyq: CopyQ, private val keyring: Keyring, private val restarter: CopyqRestarter) {

    fun enable(): String {
        check(copyq.running()) { "CopyQ is not running." }
        if (copyq.tabsEncrypted() == true) return "CopyQ tabs are already encrypted."
        if (copyq.config("use_key_store") != "true") copyq.setConfig("use_key_store", "true")
        val itemsBefore = checkNotNull(countItems()) { "Could not count CopyQ's items; nothing changed." }

        val password = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
        val account = backupAccount(copyq.session)
        keyring.store(account, password.encodeToByteArray())
        check(keyring.load(account)?.decodeToString() == password) { "Could not keep a backup of the password; nothing changed." }

        check(restarter.restart(mapOf(PASSWORD_ENV to password))) { "CopyQ did not come back after restart (with password)." }
        copyq.setConfig("encrypt_tabs", "true")
        check(copyq.tabsEncrypted() == true) { "CopyQ did not enable encryption." }
        check(restarter.restart(emptyMap())) { "CopyQ did not come back after the final restart." }
        val itemsAfter = countItems()
        check(copyq.tabsEncrypted() == true && itemsAfter == itemsBefore) {
            "CopyQ could not reopen its tabs without the password ($itemsBefore items before, $itemsAfter after). " +
                "The password is in your keyring under ClipVault, account $account."
        }
        return "CopyQ tabs are now encrypted. The password is in your keyring twice: CopyQ's own entry " +
            "(org.qt.keychain, server com.github.hluk.copyq) and ClipVault's backup (service cz.tadeasf.clipvault, account $account)."
    }

    /**
     * Total items over all tabs; loading each tab forces CopyQ to decrypt it. No row is read. Null when
     * CopyQ doesn't answer (it times out while waiting for a password).
     */
    private fun countItems(): Int? = copyq.evalScript(
        "var n = 0; var tabs = tab(); for (var i = 0; i < tabs.length; i++) { tab(tabs[i]); n += size(); } n",
    ).toIntOrNull()

    companion object {
        const val PASSWORD_ENV = "COPYQ_PASSWORD"

        fun backupAccount(session: String?) = "copyq-tab-password" + (session?.let { "-$it" } ?: "")
    }
}

/** Stops and starts the CopyQ server with its original environment plus [extra]. */
class CopyqRestarter(private val copyq: CopyQ, baseEnvironment: () -> Map<String, String>) {
    /**
     * Captured once, before the first restart: the server we start with COPYQ_PASSWORD would otherwise
     * hand the password on to the next restart. The variable is stripped in any case.
     */
    private val original: Map<String, String> by lazy { baseEnvironment() - CopyqEncryption.PASSWORD_ENV }

    fun restart(extra: Map<String, String>): Boolean {
        val env = original + extra
        copyq.exitServer()
        if (!waitFor { !copyq.running() }) return false
        ProcessBuilder(listOf("setsid") + copyq.command(emptyList()))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply { environment().clear(); environment().putAll(env) }
            .start()
        return waitFor { copyq.running() }
    }

    private fun waitFor(condition: () -> Boolean): Boolean {
        repeat(ATTEMPTS) {
            if (condition()) return true
            Thread.sleep(POLL_MILLIS)
        }
        return false
    }

    companion object {
        private const val ATTEMPTS = 40
        private const val POLL_MILLIS = 500L

        /**
         * Environment of the running CopyQ server (the `copyq` process with no other arguments than the
         * session switch), so the restarted one gets the same session variables. Falls back to ours.
         */
        fun serverEnvironment(session: String?): Map<String, String> {
            val wanted = session?.let { listOf("-s", it) } ?: emptyList()
            val server = ProcessHandle.allProcesses().filter { p ->
                val info = p.info()
                info.command().map { it.endsWith("/copyq") }.orElse(false) &&
                    info.arguments().map { it.toList() }.orElse(emptyArray<String>().toList()) == wanted
            }.findFirst().orElse(null) ?: return System.getenv()
            return runCatching {
                File("/proc/${server.pid()}/environ").readText().split('\u0000').filter { '=' in it }
                    .associate { it.substringBefore('=') to it.substringAfter('=') }
            }.getOrDefault(System.getenv())
        }
    }
}
