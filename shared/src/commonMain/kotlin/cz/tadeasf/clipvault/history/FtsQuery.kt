package cz.tadeasf.clipvault.history

private val whitespace = Regex("\\s+")
private const val TRIGRAM_MIN_TOKEN = 3

/** A MATCH expression plus the index it is meant for. Every token is quoted, so FTS operators in user input are literal. */
sealed interface FtsQuery {
    val match: String

    /** Trigram index: each token matches anywhere inside a word ("hub" finds "github"). */
    data class Substring(override val match: String) : FtsQuery

    /** Word-prefix index, used when a token is too short for trigrams. */
    data class Prefix(override val match: String) : FtsQuery
}

/** Null for blank input. Trigram search only when every token has at least 3 characters. */
fun ftsQuery(input: String): FtsQuery? {
    val tokens = input.split(whitespace).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return null
    val quoted = tokens.map { "\"" + it.replace("\"", "\"\"") + "\"" }
    return if (tokens.all { it.length >= TRIGRAM_MIN_TOKEN }) {
        FtsQuery.Substring(quoted.joinToString(" "))
    } else {
        FtsQuery.Prefix(quoted.joinToString(" ") { "$it*" })
    }
}
