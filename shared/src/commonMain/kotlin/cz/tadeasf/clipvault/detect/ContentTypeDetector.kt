package cz.tadeasf.clipvault.detect

import cz.tadeasf.clipvault.model.ContentType
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

private val url = Regex("""^(?i)(https?|ftp)://\S+$|^www\.\S+\.\S+$""")
private val email = Regex("""^(?i)(mailto:)?[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$""")
private val e164 = Regex("""^\+[1-9](?:[ -]?\d){6,14}$""")
private val czechPhone = Regex("""^(?:(?:\+|00)420 ?)?\d{3} ?\d{3} ?\d{3}$""")
private val codeLine = Regex("""[;{}]\s*$|^\s*[}\])]|^(\t| {2,})\S|^\s*(fun|def|class|import|return|const|let|var|val|if|for|while|public|private|#include)\b|=>|->""")
private const val CODE_MIN_LINES = 3
private const val CODE_LINE_SHARE = 0.4

/** Detection order: JSON (parses) -> URL -> EMAIL -> PHONE -> CODE -> PLAIN. */
fun detectContentType(text: String): ContentType {
    val trimmed = text.trim()
    return when {
        isJson(trimmed) -> ContentType.JSON
        url.matches(trimmed) -> ContentType.URL
        email.matches(trimmed) -> ContentType.EMAIL
        e164.matches(trimmed) || czechPhone.matches(trimmed) -> ContentType.PHONE
        looksLikeCode(trimmed) -> ContentType.CODE
        else -> ContentType.PLAIN
    }
}

/** Only objects and arrays count: `42` or `"x"` are valid JSON but not worth the label. */
internal fun isJson(text: String): Boolean {
    if (text.firstOrNull() != '{' && text.firstOrNull() != '[') return false
    return try {
        Json.parseToJsonElement(text).let { it is JsonObject || it is JsonArray }
    } catch (_: SerializationException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
}

private fun looksLikeCode(text: String): Boolean {
    val lines = text.lines().filter(String::isNotBlank)
    if (lines.size < CODE_MIN_LINES) return false
    return lines.count(codeLine::containsMatchIn) >= lines.size * CODE_LINE_SHARE
}
