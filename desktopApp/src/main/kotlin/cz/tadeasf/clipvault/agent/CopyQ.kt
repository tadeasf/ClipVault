package cz.tadeasf.clipvault.agent

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The `copyq` command line. Clip text always goes over stdin (`-`), never argv, so it never shows up
 * in `ps`. [binary] is overridable so tests can point at a fake.
 */
class CopyQ(private val binary: String = "copyq", private val timeoutSeconds: Long = 15) {

    private class Result(val ok: Boolean, val out: String)

    private fun run(args: List<String>, stdin: ByteArray? = null): Result {
        val process = try {
            ProcessBuilder(listOf(binary) + args).redirectErrorStream(true).start()
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

    /** `--version` works without a running CopyQ server. */
    fun installed(): Boolean = run(listOf("--version")).ok

    fun running(): Boolean = run(listOf("eval", "'ok'")).out.trim() == "ok"

    fun clipboardTab(): String? = run(listOf("config", "clipboard_tab")).takeIf { it.ok }?.out?.trim()?.ifEmpty { null }

    /** Null when CopyQ can't say (not running, old version). */
    fun tabsEncrypted(): Boolean? = when (run(listOf("config", "encrypt_tabs")).out.trim()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    fun add(tab: String, text: String): Boolean = run(listOf("tab", tab, "add", "-"), text.encodeToByteArray()).ok

    /** Sets the live clipboard. CopyQ ignores clipboards it set itself, so this is not re-captured. */
    fun copy(text: String): Boolean = run(listOf("copy", "text/plain", "-"), text.encodeToByteArray()).ok

    fun commandInstalled(): Boolean =
        run(listOf("eval", "commands().some(function (c) { return c.name == ${jsString(COMMAND_NAME)}; })")).out.trim() == "true"

    /** Null on success, otherwise what CopyQ said. */
    fun installCommand(agentBinary: String): String? =
        // Via stdin: CopyQ's CLI turns \n, \" etc. in *arguments* into real characters, which breaks JS string literals.
        run(listOf("eval", "-"), installScript(agentBinary).encodeToByteArray()).out.trim().takeUnless { it == "installed" }

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
        execute.apply(this, args);
    """.trimIndent()
    return """
        var name = ${jsString(CopyQ.COMMAND_NAME)};
        var cmds = commands().filter(function (c) { return c.name != name; });
        cmds.push({ name: name, automatic: true, cmd: ${jsString(command)} });
        setCommands(cmds);
        "installed"
    """.trimIndent()
}
