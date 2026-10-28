package cz.tadeasf.clipvault.agent

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The `copyq` command line. Clip text always goes over stdin (`-`), never argv, so it never shows up
 * in `ps`. [binary] is overridable so tests can point at a fake; [session] targets a separate CopyQ
 * session (`copyq -s NAME`), which is how the encryption setup is rehearsed without touching the real one.
 */
class CopyQ(
    private val binary: String = "copyq",
    private val timeoutSeconds: Long = 15,
    val session: String? = null,
) {

    private class Result(val ok: Boolean, val out: String)

    private fun run(args: List<String>, stdin: ByteArray? = null): Result {
        val process = try {
            ProcessBuilder(command(args)).redirectErrorStream(true).start()
        } catch (_: IOException) {
            return Result(false, "") // not installed
        }
        // Read output off-thread: a CopyQ waiting on e.g. its encryption password prompt must not hang us.
        val out = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
        process.outputStream.use { if (stdin != null) it.write(stdin) }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
            return Result(false, "")
        }
        return Result(process.exitValue() == 0, out.get(1, TimeUnit.SECONDS))
    }

    /** The full command line for [args], including the session switch. */
    fun command(args: List<String>): List<String> = listOf(binary) + (session?.let { listOf("-s", it) } ?: emptyList()) + args

    /** `--version` works without a running CopyQ server. */
    fun installed(): Boolean = run(listOf("--version")).ok

    fun running(): Boolean = run(listOf("eval", "'ok'")).out.trim() == "ok"

    fun config(option: String): String? = run(listOf("config", option)).takeIf { it.ok }?.out?.trim()

    fun setConfig(option: String, value: String): String? = run(listOf("config", option, value)).takeIf { it.ok }?.out?.trim()

    fun clipboardTab(): String? = config("clipboard_tab")?.ifEmpty { null }

    /** Null when CopyQ can't say (not running, old version). */
    fun tabsEncrypted(): Boolean? = when (config("encrypt_tabs")) {
        "true" -> true
        "false" -> false
        else -> null
    }

    fun add(tab: String, text: String): Boolean = run(listOf("tab", tab, "add", "-"), text.encodeToByteArray()).ok

    /** Sets the live clipboard. CopyQ ignores clipboards it set itself, so this is not re-captured. */
    fun copy(text: String): Boolean = run(listOf("copy", "text/plain", "-"), text.encodeToByteArray()).ok

    /**
     * Removes the newest item of [tab] if, and only if, its text hashes to [sha256Hex]. The comparison
     * runs inside CopyQ: the agent never reads the row.
     */
    fun removeNewestIfHash(tab: String, sha256Hex: String): Boolean {
        val script = """
            tab(${jsString(tab)});
            if (size() > 0 && str(sha256sum(read(0))) == ${jsString(sha256Hex)}) { remove(0); "removed" } else "absent"
        """.trimIndent()
        return evalScript(script) == "removed"
    }

    fun exitServer(): Boolean = run(listOf("exit")).ok

    fun commandInstalled(): Boolean =
        run(listOf("eval", "commands().some(function (c) { return c.name == ${jsString(COMMAND_NAME)}; })")).out.trim() == "true"

    /** Null on success, otherwise what CopyQ said. */
    fun installCommand(agentBinary: String): String? = evalScript(installScript(agentBinary)).takeUnless { it == "installed" }

    /** Via stdin: CopyQ's CLI turns \n, \" etc. in *arguments* into real characters, which breaks JS string literals. */
    fun evalScript(script: String): String = run(listOf("eval", "-"), script.encodeToByteArray()).out.trim()

    companion object {
        const val COMMAND_NAME = "ClipVault ingest"
        const val ORIGIN_MIME = "application/x-clipvault-origin"
    }
}

/** JS string literal (JSON escaping is valid JS). */
internal fun jsString(value: String): String = buildString {
    append('"')
    value.forEach { c ->
        when {
            c == '"' || c == '\\' -> append('\\').append(c)
            c == '\n' -> append("\\n")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
    append('"')
}

/**
 * Replaces (or adds) only the "ClipVault ingest" automatic command; the user's other commands are kept.
 * The command hands every new clipboard item to `clipvault-agent ingest`: text on stdin, window title
 * when CopyQ knows it (it doesn't on GNOME Wayland), `--non-text` for items without text.
 * It goes first, so that when the agent answers "scrub" (sensitive clip, scrub_copyq_sensitive on),
 * `ignore()` keeps the clip out of CopyQ's history and skips the user's later automatic commands
 * (e.g. an archive command) for it.
 */
internal fun installScript(agentBinary: String): String {
    val command = """
        copyq:
        if (str(data(${jsString(CopyQ.ORIGIN_MIME)})) == "remote") abort();
        var text = data(mimeText);
        var args = [${jsString(agentBinary)}, "ingest"];
        var title = str(data(mimeWindowTitle));
        if (title) { args.push("--title"); args.push(title); }
        if (text.length == 0) args.push("--non-text");
        args.push(null);
        args.push(text);
        var result = execute.apply(this, args);
        if (result && str(result.stdout).indexOf("scrub") >= 0) ignore();
    """.trimIndent()
    return """
        var name = ${jsString(CopyQ.COMMAND_NAME)};
        var cmds = commands().filter(function (c) { return c.name != name; });
        cmds.unshift({ name: name, automatic: true, cmd: ${jsString(command)} });
        setCommands(cmds);
        "installed"
    """.trimIndent()
}
