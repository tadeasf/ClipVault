package cz.tadeasf.clipvault.history

private val whitespace = Regex("\\s+")

/**
 * Turns free text into an FTS5 MATCH expression: every token is quoted, so FTS operators in user input
 * are literal, and prefix-matched. Returns null for blank input.
 * shortcut: prefix match only ("hub" does not find "github"), upgrade to a trigram tokenizer if users need infix search.
 */
fun ftsQuery(input: String): String? =
    input.split(whitespace)
        .filter { it.isNotEmpty() }
        .joinToString(" ") { "\"" + it.replace("\"", "\"\"") + "\"*" }
        .ifEmpty { null }
