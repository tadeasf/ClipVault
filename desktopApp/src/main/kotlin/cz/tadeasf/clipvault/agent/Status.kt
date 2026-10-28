package cz.tadeasf.clipvault.agent

import java.time.Instant
import kotlinx.coroutines.flow.first

/** How desktop clips reach the agent right now. */
enum class CaptureMode(val label: String) {
    COPYQ_COMMAND("copyq automatic command"),
    GNOME_DBUS("GNOME extension over D-Bus (CopyQ command not installed)"),
    NONE_NO_COPYQ("none: CopyQ not installed, sync-only"),
    NONE("none: run `clipvault-agent install-copyq`"),
}

fun captureModeOf(copyq: CopyQ, gnomeRegistered: Boolean): CaptureMode = when {
    !copyq.installed() -> CaptureMode.NONE_NO_COPYQ
    copyq.commandInstalled() -> CaptureMode.COPYQ_COMMAND
    gnomeRegistered -> CaptureMode.GNOME_DBUS
    else -> CaptureMode.NONE
}

/** The `clipvault-agent status` text. One "key: value" per line so it is easy to grep. */
suspend fun AgentCore.statusReport(daemon: String, capture: CaptureMode, copyq: CopyQ): String = buildString {
    appendLine("daemon: $daemon")
    appendLine("capture: ${capture.label}")
    val counts = repository.counts().first()
    appendLine("store: ${counts.total} clips (${counts.sensitive} sensitive, hidden)")
    state.lastIngest()?.let { (at, outcome, source) ->
        appendLine("last ingest: ${Instant.ofEpochMilli(at)} ${outcome.lowercase()} source=${source.ifEmpty { "unknown" }}")
    }
    appendLine("ingested: " + Outcome.entries.joinToString(" ") { "${it.name.lowercase()}=${state.count(it)}" })
    appendCopyq(this@statusReport, copyq)
    appendLine("exclusions: ${state.excludedTitles.ifEmpty { setOf("none") }.joinToString(", ")}")
}

private fun StringBuilder.appendCopyq(core: AgentCore, copyq: CopyQ) {
    val state = core.state
    if (!copyq.installed()) {
        appendLine("copyq: not installed")
    } else {
        val running = copyq.running()
        appendLine("copyq: ${if (running) "running" else "not running"}")
        if (running) {
            appendLine("copyq tab: ${state.copyqTab ?: copyq.clipboardTab() ?: AgentCore.DEFAULT_TAB}")
            appendLine("copyq: " + when (copyq.tabsEncrypted()) {
                true -> "tabs encrypted"
                false -> "tabs unencrypted (history is plaintext on disk; see `clipvault-agent install-copyq`)"
                null -> "tab encryption unknown"
            })
        }
    }
    appendLine("promote_to_clipboard: ${if (state.promoteToClipboard) "on" else "off"}")
    appendLine("copyq outbox: ${state.outbox().size} waiting")
}
