package cz.tadeasf.clipvault.agent

import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

private const val USAGE = """Usage: clipvault-agent <command>
  run                         start the daemon (normally via the systemd user service)
  status                      what the agent is doing
  ingest [--title T] [--non-text]
                              store the clip on stdin (called by the CopyQ command)
  push-test TEXT              send TEXT to CopyQ the way a phone clip would travel
  install-copyq               add the "ClipVault ingest" automatic command to CopyQ
  install-service             install and start the systemd user service
  config [KEY [VALUE]]        promote_to_clipboard on|off, copyq_tab NAME|default,
                              exclude TITLE, unexclude TITLE"""

fun main(args: Array<String>) {
    val code = runBlocking { Cli(AgentPaths.fromEnvironment(), CopyQ()).run(args.toList()) }
    exitProcess(code)
}

class Cli(private val paths: AgentPaths, private val copyq: CopyQ) {

    suspend fun run(args: List<String>): Int = when (args.firstOrNull()) {
        "run" -> runDaemon(AgentCore(openStore(paths), copyq), copyq, paths, GnomeFallback()).let { 0 }
        "status" -> status()
        "ingest" -> ingest(args.drop(1), System.`in`.readBytes())
        "push-test" -> args.getOrNull(1)?.let { push(it) } ?: usage()
        "install-copyq" -> installCopyq()
        "install-service" -> agentBinary()?.let { SystemdUnit.install(it) } ?: noLauncher()
        "config" -> config(args.drop(1))
        else -> usage()
    }

    /** Never fails towards CopyQ: errors are printed, exit code stays 0. */
    suspend fun ingest(args: List<String>, stdin: ByteArray): Int {
        val title = args.indexOf("--title").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        val params = buildMap {
            title?.let { put("title", it) }
            if ("--non-text" in args) put("nonText", "1")
        }
        val request = AgentRequest(Commands.INGEST, params, stdin)
        val answer = sendToDaemon(paths.socket, request)
            ?: runCatching { local().ingest(request.toIngest()).name.lowercase() }.getOrElse { "error: ${it.message}" }
        println(answer)
        return 0
    }

    private suspend fun status(): Int {
        val answer = sendToDaemon(paths.socket, AgentRequest(Commands.STATUS, emptyMap(), ByteArray(0)))
            ?: local().statusReport("not running (`systemctl --user start clipvault-agent`)", captureModeOf(copyq, false), copyq)
        print(answer)
        return 0
    }

    private suspend fun push(text: String): Int {
        val answer = sendToDaemon(paths.socket, AgentRequest(Commands.PUSH, emptyMap(), text.encodeToByteArray()))
            ?: pushReport(local(), text)
        println(answer)
        return 0
    }

    private fun installCopyq(): Int {
        if (!copyq.installed()) return fail("CopyQ is not installed; the agent runs sync-only.")
        if (!copyq.running()) return fail("CopyQ is not running; start it and try again.")
        val binary = agentBinary() ?: return noLauncher()
        copyq.installCommand(binary)?.let { return fail("CopyQ refused the command:\n$it") }
        println("Installed CopyQ automatic command \"${CopyQ.COMMAND_NAME}\" -> $binary ingest")
        if (copyq.tabsEncrypted() != true) println("\n$ENCRYPTION_ADVICE")
        return 0
    }

    private fun config(args: List<String>): Int {
        val state = local().state
        when (args.getOrNull(0)) {
            null -> Unit
            "promote_to_clipboard" -> state.promoteToClipboard = args.getOrNull(1) == "on"
            "copyq_tab" -> state.copyqTab = args.getOrNull(1)?.takeUnless { it == "default" }
            "exclude" -> state.excludedTitles += args.getOrNull(1) ?: return usage()
            "unexclude" -> state.excludedTitles -= args.getOrNull(1) ?: return usage()
            else -> return usage()
        }
        println("promote_to_clipboard=${if (state.promoteToClipboard) "on" else "off"}")
        println("copyq_tab=${state.copyqTab ?: "default (CopyQ clipboard tab)"}")
        println("exclusions=${state.excludedTitles.joinToString(", ").ifEmpty { "none" }}")
        return 0
    }

    private var core: AgentCore? = null

    /** The store opened in this process, for when no daemon answers. */
    private fun local(): AgentCore = core ?: AgentCore(openStore(paths), copyq).also { core = it }

    private fun usage(): Int = fail(USAGE, code = 2)

    private fun noLauncher(): Int = fail("Run this from the installed `clipvault-agent` launcher (or set CLIPVAULT_AGENT_BIN).")

    private fun fail(message: String, code: Int = 1): Int {
        System.err.println(message)
        return code
    }
}
