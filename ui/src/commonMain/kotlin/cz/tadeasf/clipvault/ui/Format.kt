package cz.tadeasf.clipvault.ui

import cz.tadeasf.clipvault.model.ContentType
import cz.tadeasf.clipvault.transform.Transformation

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

fun relativeTime(now: Long, then: Long): String {
    val ago = (now - then).coerceAtLeast(0)
    return when {
        ago < MINUTE -> "just now"
        ago < HOUR -> "${ago / MINUTE} min ago"
        ago < DAY -> "${ago / HOUR} h ago"
        else -> "${ago / DAY} d ago"
    }
}

/** A short glyph per type, so the list needs no icon font. */
fun ContentType.glyph(): String = when (this) {
    ContentType.URL -> "🔗"
    ContentType.EMAIL -> "@"
    ContentType.PHONE -> "☎"
    ContentType.CODE -> "</>"
    ContentType.JSON -> "{ }"
    ContentType.PLAIN -> "¶"
}

fun ContentType.label(): String = name.lowercase().replaceFirstChar(Char::uppercase)

fun Transformation.label(): String = when (this) {
    Transformation.Trim -> "Trim"
    Transformation.UpperCase -> "UPPER CASE"
    Transformation.LowerCase -> "lower case"
    Transformation.TitleCase -> "Title Case"
    Transformation.SentenceCase -> "Sentence case"
    Transformation.StripFormatting -> "Strip formatting"
    Transformation.CleanUrl -> "Clean URL (remove tracking)"
    Transformation.JsonPretty -> "JSON: pretty"
    Transformation.JsonMinify -> "JSON: minify"
    Transformation.Base64Encode -> "Base64: encode"
    Transformation.Base64Decode -> "Base64: decode"
}

/** First [lines] lines, for list rows. */
fun preview(content: String, lines: Int = 2): String = content.lineSequence().take(lines).joinToString("\n")
