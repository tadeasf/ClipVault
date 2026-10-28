package cz.tadeasf.clipvault.transform

import cz.tadeasf.clipvault.detect.isJson
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** A pure text transformation. Failures (invalid JSON, no URL, binary base64) come back as Result.failure. */
sealed interface Transformation : (String) -> Result<String> {
    data object Trim : Transformation {
        override fun invoke(input: String) = Result.success(input.trim())
    }

    data object UpperCase : Transformation {
        override fun invoke(input: String) = Result.success(input.uppercase())
    }

    data object LowerCase : Transformation {
        override fun invoke(input: String) = Result.success(input.lowercase())
    }

    /** "hello WORLD" -> "Hello World". */
    data object TitleCase : Transformation {
        private val wordStart = Regex("""(?<![\p{L}\p{N}'’])\p{L}""")
        override fun invoke(input: String) =
            Result.success(input.lowercase().replace(wordStart) { it.value.uppercase() })
    }

    /** "HELLO. how ARE you?" -> "Hello. How are you?" */
    data object SentenceCase : Transformation {
        private val sentenceStart = Regex("""(^|[.!?]\s+)(\p{L})""")
        override fun invoke(input: String) = Result.success(
            input.lowercase().replace(sentenceStart) { it.groupValues[1] + it.groupValues[2].uppercase() },
        )
    }

    /** Drops zero-width characters, turns NBSP into spaces, collapses runs of spaces and blank lines. */
    data object StripFormatting : Transformation {
        private val zeroWidth = Regex("[​‌‍⁠﻿]")
        private val horizontalSpace = Regex("[ \t\u000B\u000C ]+")
        private val blankLines = Regex("\n{3,}")
        override fun invoke(input: String): Result<String> {
            val lines = input.replace(zeroWidth, "").replace("\r\n", "\n").lines()
                .map { it.replace(horizontalSpace, " ").trim() }
            return Result.success(lines.joinToString("\n").replace(blankLines, "\n\n").trim())
        }
    }

    /** Removes tracking parameters from every http(s) URL in the text; everything else is preserved. */
    data object CleanUrl : Transformation {
        private val urlInText = Regex("""https?://[^\s<>"']+""")
        override fun invoke(input: String): Result<String> {
            if (!urlInText.containsMatchIn(input)) return Result.failure(IllegalArgumentException("No URL found"))
            return Result.success(input.replace(urlInText) { cleanUrl(it.value) })
        }
    }

    data object JsonPretty : Transformation {
        private val pretty = Json { prettyPrint = true }
        override fun invoke(input: String) = reformatJson(input, pretty)
    }

    data object JsonMinify : Transformation {
        override fun invoke(input: String) = reformatJson(input, Json)
    }

    data object Base64Encode : Transformation {
        override fun invoke(input: String) = Result.success(Base64.encode(input.encodeToByteArray()))
    }

    /** Decodes standard or URL-safe base64, but only when the result is printable UTF-8 text. */
    data object Base64Decode : Transformation {
        override fun invoke(input: String): Result<String> = runCatching {
            val compact = input.filterNot(Char::isWhitespace)
            val text = decodeBase64(compact).decodeToString(throwOnInvalidSequence = true)
            require(text.isNotEmpty() && text.all { !it.isISOControl() || it in "\n\r\t" }) { "Not printable text" }
            text
        }
    }
}

val transformations: List<Transformation> = listOf(
    Transformation.Trim,
    Transformation.UpperCase,
    Transformation.LowerCase,
    Transformation.TitleCase,
    Transformation.SentenceCase,
    Transformation.StripFormatting,
    Transformation.CleanUrl,
    Transformation.JsonPretty,
    Transformation.JsonMinify,
    Transformation.Base64Encode,
    Transformation.Base64Decode,
)

private val trackingParams = setOf("fbclid", "gclid", "igshid", "si")

private fun isTracking(param: String): Boolean {
    val name = param.substringBefore('=').lowercase()
    return name.startsWith("utm_") || name in trackingParams
}

internal fun cleanUrl(url: String): String {
    val fragment = url.substringAfter('#', missingDelimiterValue = "")
    val beforeFragment = url.substringBefore('#')
    if ('?' !in beforeFragment) return url
    val kept = beforeFragment.substringAfter('?').split('&').filter { it.isNotEmpty() && !isTracking(it) }
    val query = if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
    val hash = if ('#' in url) "#$fragment" else ""
    return beforeFragment.substringBefore('?') + query + hash
}

private fun reformatJson(input: String, json: Json): Result<String> {
    if (!isJson(input.trim())) return Result.failure(IllegalArgumentException("Not a JSON object or array"))
    return Result.success(json.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(input)))
}

private fun decodeBase64(compact: String): ByteArray {
    val lenient = Base64.PaddingOption.PRESENT_OPTIONAL
    return runCatching { Base64.Default.withPadding(lenient).decode(compact) }
        .getOrElse { Base64.UrlSafe.withPadding(lenient).decode(compact) }
}
