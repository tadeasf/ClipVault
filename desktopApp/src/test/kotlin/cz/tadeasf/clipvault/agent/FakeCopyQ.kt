package cz.tadeasf.clipvault.agent

import java.io.File

/**
 * A fake `copyq` executable: records every call and its stdin, answers the queries the agent makes,
 * and pretends the server is down while [running] is false.
 */
class FakeCopyQ(private val dir: File) {
    val binary = File(dir, "copyq")

    init {
        binary.writeText(
            """
            |#!/usr/bin/env bash
            |d="${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd)"
            |if [ "${'$'}1" = "--version" ]; then echo "CopyQ fake"; exit 0; fi
            |if [ -f "${'$'}d/down" ]; then echo "Cannot connect to server!"; exit 1; fi
            |printf '%s\n' "${'$'}*" >> "${'$'}d/calls"
            |case " ${'$'}* " in *" - "*) { cat; printf '\n<END>\n'; } >> "${'$'}d/stdin";; esac
            |case "${'$'}1 ${'$'}2" in
            |  "config clipboard_tab") echo "&clipboard";;
            |  "config encrypt_tabs") echo false;;
            |esac
            |if [ "${'$'}1" = eval ]; then
            |  script="${'$'}2"; [ "${'$'}2" = "-" ] && script="${'$'}(tail -c 2000 "${'$'}d/stdin")"
            |  case "${'$'}script" in
            |    "'ok'") echo ok;;
            |    *setCommands*) echo installed;;
            |    *commands*) echo false;;
            |  esac
            |fi
            |exit 0
            |""".trimMargin(),
        )
        binary.setExecutable(true)
    }

    var running: Boolean
        get() = !File(dir, "down").exists()
        set(value) {
            if (value) File(dir, "down").delete() else File(dir, "down").writeText("")
        }

    fun copyq() = CopyQ(binary.absolutePath, timeoutSeconds = 10)

    /** Arguments of every call except the agent's read-only probes. */
    fun writes(): List<String> = File(dir, "calls").takeIf { it.exists() }?.readLines()
        ?.filter { it.startsWith("tab ") || it.startsWith("copy ") } ?: emptyList()

    fun stdins(): List<String> = File(dir, "stdin").takeIf { it.exists() }?.readText()
        ?.split("\n<END>\n")?.filter(String::isNotEmpty) ?: emptyList()
}
