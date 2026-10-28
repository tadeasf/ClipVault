package cz.tadeasf.clipvault.security

import java.io.IOException
import java.util.concurrent.TimeUnit

/** A desktop secret store holding one secret per account name. */
interface Keyring {
    fun load(account: String): ByteArray?
    fun store(account: String, secret: ByteArray)
}

/**
 * Secret Service (GNOME Keyring, KWallet, KeePassXC) through libsecret's `secret-tool`.
 * The secret goes over stdin, never argv. Unlock prompts are handled by the keyring daemon.
 */
class SecretToolKeyring(private val service: String = "cz.tadeasf.clipvault") : Keyring {

    override fun load(account: String): ByteArray? {
        val result = run(listOf("lookup", "service", service, "account", account))
        return result.stdout.trim().takeIf { result.ok && it.isNotEmpty() }?.hexToBytes()
    }

    override fun store(account: String, secret: ByteArray) {
        val args = listOf("store", "--label=ClipVault database key", "service", service, "account", account)
        val result = run(args, stdin = secret.toHex())
        if (!result.ok) throw IOException("secret-tool store failed: ${result.stderr}")
    }

    fun clear(account: String) {
        run(listOf("clear", "service", service, "account", account))
    }

    private class Result(val ok: Boolean, val stdout: String, val stderr: String)

    private fun run(args: List<String>, stdin: String = ""): Result {
        val process = ProcessBuilder(listOf(BINARY) + args).start()
        process.outputStream.use { it.write(stdin.encodeToByteArray()) }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly().waitFor()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        return Result(process.exitValue() == 0, stdout, stderr)
    }

    companion object {
        private const val BINARY = "secret-tool"
        private const val TIMEOUT_SECONDS = 60L // the keyring may show an unlock prompt

        /** Null when secret-tool is missing or no Secret Service answers on the session bus. */
        fun detect(service: String = "cz.tadeasf.clipvault"): SecretToolKeyring? {
            val probe = try {
                ProcessBuilder(BINARY, "search", "service", "$service.probe").start()
            } catch (_: IOException) {
                return null
            }
            val finished = probe.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val error = if (finished) probe.errorStream.bufferedReader().readText() else "timeout"
            return SecretToolKeyring(service).takeIf { finished && probe.exitValue() == 0 && error.isBlank() }
        }
    }
}
