package cz.tadeasf.clipvault.agent

import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

private const val USAGE = """Usage: clipvault-agent [--session NAME] <command>
  run                         start the daemon (normally via the systemd user service)
  status [--dry-run]          what the agent is doing (--dry-run: without opening the store)
  ingest [--title T] [--non-text]
                              store the clip on stdin (called by the CopyQ command)
  push-test TEXT              send TEXT to CopyQ the way a phone clip would travel
  install-copyq               add the "ClipVault ingest" automatic command to CopyQ
  install-copyq --check-encryption
                              is CopyQ's history encrypted on disk? (reports only; exit 1 if not)
  install-service             install and start the systemd user service
  pair                        pair a phone: show a QR code (2 minutes, one use)
  pair --code                 pair another desktop: show a 6-digit code
  pair --join [--addr H:P]    pair with a desktop showing a code (found by mDNS, or at H:P)
                              pair options: --listen ADDR (address to use), --name NAME
  config [KEY [VALUE]]        promote_to_clipboard on|off, scrub_copyq_sensitive on|off,
                              copyq_tab NAME|default, exclude TITLE, unexclude TITLE
  --session NAME              talk to CopyQ session NAME (copyq -s NAME) instead of the default"""

fun main(args: Array<String>) {
    val session = args.indexOf("--session").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val rest = if (session == null) args.toList() else args.toList() - listOf("--session", session)
    val code = try {
        runBlocking { Cli(AgentPaths.fromEnvironment(), CopyQ(session = session)).run(rest) }
    } catch (e: StoreKeyException) {
        System.err.println(e.message)
        StoreKeyException.EXIT_CODE
    }
    exitProcess(code)
}

class Cli(private val paths: AgentPaths, private val copyq: CopyQ) {

    suspend fun run(args: List<String>): Int = when (args.firstOrNull()) {
        "run" -> runDaemon(AgentCore(openStore(paths), copyq), copyq, paths, GnomeFallback()).let { 0 }
        "status" -> if ("--dry-run" in args) dryRunStatus() else status()
        "ingest" -> ingest(args.drop(1), System.`in`.readBytes())
        "push-test" -> args.getOrNull(1)?.let { push(it) } ?: usage()
        "install-copyq" -> when {
            "--check-encryption" in args -> checkEncryption()
            else -> installCopyq()
        }
        "install-service" -> agentBinary()?.let { SystemdUnit.install(it) } ?: noLauncher()
        "config" -> config(args.drop(1))
        "pair" -> PairCommand(paths).run(args.drop(1))
        "--help", "-h", "help" -> println(USAGE).let { 0 }
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
            ?: runCatching { local().let { it.ingest(request.toIngest()).reply(it.state.scrubSensitive) } }
                .getOrElse { "error: ${it.message}" }
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

    /** What status can say without the store or the keyring (works in a bare container). */
    private fun dryRunStatus(): Int {
        println("data dir: ${paths.dataDir} (store ${if (paths.database.exists()) "present" else "not created yet"})")
        println("socket: ${paths.socket} (daemon ${if (sendToDaemon(paths.socket, AgentRequest(Commands.PING, emptyMap(), ByteArray(0))) == "pong") "running" else "not running"})")
        println("capture: ${captureModeOf(copyq, gnomeRegistered = false).label}")
        println("copyq: ${if (!copyq.installed()) "not installed" else if (copyq.running()) "running" else "not running"}")
        println("launcher: ${agentBinary() ?: "not running from the installed launcher"}")
        return 0
    }

    private fun checkEncryption(): Int = when (copyq.tabsEncrypted()) {
        true -> println("copyq: tabs encrypted").let { 0 }
        false -> println("copyq: tabs unencrypted\n\n$ENCRYPTION_ADVICE").let { 1 }
        null -> fail("CopyQ is not running (or too old to report tab encryption).")
    }

    private fun config(args: List<String>): Int {
        val state = local().state
        when (args.getOrNull(0)) {
            null -> Unit
            "promote_to_clipboard" -> state.promoteToClipboard = args.getOrNull(1) == "on"
            "scrub_copyq_sensitive" -> state.scrubSensitive = args.getOrNull(1) != "off"
            "copyq_tab" -> state.copyqTab = args.getOrNull(1)?.takeUnless { it == "default" }
            "exclude" -> state.excludedTitles += args.getOrNull(1) ?: return usage()
            "unexclude" -> state.excludedTitles -= args.getOrNull(1) ?: return usage()
            else -> return usage()
        }
        println("promote_to_clipboard=${if (state.promoteToClipboard) "on" else "off"}")
        println("scrub_copyq_sensitive=${if (state.scrubSensitive) "on" else "off"}")
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
