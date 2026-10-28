package cz.tadeasf.clipvault.detect

import cz.tadeasf.clipvault.model.ClipHints

enum class SensitiveCategory {
    PASSWORD, OTP, CARD_NUMBER, IBAN, API_KEY, PRIVATE_KEY, CREDENTIALS_PAIR, SSN_LIKE_ID
}

/**
 * Local, content-based sensitivity classification. Every rule leans towards missing a secret
 * over flagging ordinary text: phone numbers, dates, Czech bank account numbers and order
 * numbers must not trigger.
 */
object SensitiveDetector {

    fun classify(text: String, hints: ClipHints = ClipHints()): Set<SensitiveCategory> = buildSet {
        if (fromSensitiveSource(hints)) add(SensitiveCategory.PASSWORD)
        if (OtpRule.matches(text)) add(SensitiveCategory.OTP)
        if (CardRule.matches(text)) add(SensitiveCategory.CARD_NUMBER)
        if (IbanRule.matches(text)) add(SensitiveCategory.IBAN)
        if (KeyRules.apiKey(text)) add(SensitiveCategory.API_KEY)
        if (KeyRules.privateKey(text)) add(SensitiveCategory.PRIVATE_KEY)
        if (CredentialsRule.matches(text)) add(SensitiveCategory.CREDENTIALS_PAIR)
        if (BirthNumberRule.matches(text)) add(SensitiveCategory.SSN_LIKE_ID)
    }

    private fun fromSensitiveSource(hints: ClipHints): Boolean {
        val title = hints.sourceWindowTitle
        return hints.isSensitiveFlag ||
            hints.sourcePackage in KnownSensitiveSources.packages ||
            (title != null && KnownSensitiveSources.windowTitles.any { title.contains(it, ignoreCase = true) })
    }
}

/** Unicode-aware word edges that behave the same on the JVM and on Android (no \b, no (?U)). */
private const val NOT_WORD = """(?<![\p{L}\p{N}])"""
private const val NOT_WORD_AFTER = """(?![\p{L}\p{N}])"""

/** 4-8 digits: alone (not a year), or anywhere in a short text that mentions a code. */
internal object OtpRule {
    private val whole = Regex("""^(\d{4,8}|\d{3} \d{3}|\d{4} \d{4})$""")
    // Not part of a longer number, date, time, account or phone number ("12.10.2026", "19-2000/0800", "+420").
    private val token = Regex("""(?<![\d+])(?<!\d[.,/:-])\d{4,8}(?!\d)(?![.,/:-]\d)""")
    // Matched against lowercased text: Android's ICU regex rejects (?U), and without it Java's \b is ASCII-only.
    private val keyword = Regex(
        """$NOT_WORD(code|kód|kod|otp|pin|passcode|verification|ověřovací|overovaci|jednorázový|2fa)$NOT_WORD_AFTER""",
    )
    private const val MAX_KEYWORD_TEXT = 300

    fun matches(text: String): Boolean {
        val trimmed = text.trim()
        if (whole.matches(trimmed)) return !isYear(trimmed)
        if (trimmed.length > MAX_KEYWORD_TEXT || !keyword.containsMatchIn(trimmed.lowercase())) return false
        return token.findAll(trimmed).any { !isYear(it.value) }
    }

    private fun isYear(digits: String) = digits.length == 4 && digits.toInt() in 1900..2099
}

/** 13-19 digits in card-style grouping, a card-network prefix, and a valid Luhn checksum. */
internal object CardRule {
    private val candidate = Regex(
        """(?<![\d/-])(\d{4}(?:[ -]\d{4}){2,3}(?:[ -]\d{1,3})?|\d{4}[ -]\d{6}[ -]\d{5}|\d{13,19})(?![\d/-])""",
    )

    fun matches(text: String): Boolean = candidate.findAll(text).any {
        val digits = it.value.filter(Char::isDigit)
        digits.length in 13..19 && hasNetworkPrefix(digits) && luhnValid(digits)
    }

    /** Visa 4, Mastercard 51-55 / 2221-2720, Amex/JCB/Diners 3x, Discover/Maestro 5x-6x. */
    private fun hasNetworkPrefix(digits: String): Boolean =
        digits[0] in "3456" || digits.take(4).toInt() in 2221..2720

    fun luhnValid(digits: String): Boolean {
        val sum = digits.reversed().mapIndexed { i, c ->
            val d = (c - '0') * (if (i % 2 == 1) 2 else 1)
            if (d > 9) d - 9 else d
        }.sum()
        return sum % 10 == 0
    }
}

/** ISO 13616 IBAN with a valid mod-97 checksum. */
internal object IbanRule {
    private val candidate = Regex("""(?i)\b[A-Z]{2}\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,3})?\b""")

    fun matches(text: String): Boolean = candidate.findAll(text).any {
        val iban = it.value.replace(" ", "").uppercase()
        iban.length in 15..34 && mod97(iban.drop(4) + iban.take(4)) == 1
    }

    private fun mod97(value: String): Int = value.fold(0) { acc, c ->
        val digits = if (c.isDigit()) "$c" else (c - 'A' + 10).toString()
        digits.fold(acc) { rem, d -> (rem * 10 + (d - '0')) % 97 }
    }
}

internal object KeyRules {
    private val apiKeys = listOf(
        Regex("""\bsk-[A-Za-z0-9_-]{20,}"""), // OpenAI, Anthropic
        Regex("""\b(?:sk|rk)_live_[A-Za-z0-9]{16,}"""), // Stripe
        Regex("""\bgh[pousr]_[A-Za-z0-9]{36,}"""), // GitHub tokens
        Regex("""\bgithub_pat_[A-Za-z0-9_]{22,}"""), // GitHub fine-grained PAT
        Regex("""\b(?:AKIA|ASIA)[0-9A-Z]{16}\b"""), // AWS access key id
        Regex("""\bxox[abprs]-[A-Za-z0-9-]{10,}"""), // Slack
        Regex("""\bAIza[0-9A-Za-z_-]{35}\b"""), // Google API key
        Regex("""\beyJ[A-Za-z0-9_-]{5,}\.eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{10,}"""), // JWT
    )
    private val privateKeyBlock = Regex("""-----BEGIN [A-Z0-9 ]*PRIVATE KEY[A-Z ]*-----""")

    fun apiKey(text: String) = apiKeys.any { it.containsMatchIn(text) }

    fun privateKey(text: String) = privateKeyBlock.containsMatchIn(text)
}

/** `user:secret` on one line, or an email line followed by a secret-looking line. */
internal object CredentialsRule {
    private val userPass = Regex("""^([A-Za-z0-9._%+@-]{2,64}):(\S{6,128})$""")
    private val email = Regex("""^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$""")
    private val portOrPath = Regex("""^\d+(/.*)?$""")
    private val schemes = setOf("http", "https", "ftp", "mailto", "tel", "file", "ssh", "git", "data", "javascript")

    fun matches(text: String): Boolean {
        val lines = text.trim().lines().map(String::trim).filter(String::isNotEmpty)
        return when (lines.size) {
            1 -> userPassPair(lines[0])
            2 -> email.matches(lines[0]) && looksLikeSecret(lines[1])
            else -> false
        }
    }

    private fun userPassPair(line: String): Boolean {
        val (user, secret) = userPass.matchEntire(line)?.destructured ?: return false
        if (user.lowercase() in schemes || ':' in secret || portOrPath.matches(secret)) return false
        return looksLikeSecret(secret)
    }

    /** No spaces, a letter, and a digit or a symbol other than word joiners (`-_.`): a password, not a word. */
    fun looksLikeSecret(value: String): Boolean =
        value.length in 6..128 &&
            value.none(Char::isWhitespace) &&
            value.any(Char::isLetter) &&
            value.any { it.isDigit() || (!it.isLetterOrDigit() && it !in "-_.") }
}

/**
 * Czech/Slovak rodné číslo: YYMMDD/XXXX (or /XXX before 1954). The slash form is accepted on its
 * own; the 10-digit form only next to a "rodné číslo"/"RČ" label. Valid date and mod-11 required.
 */
internal object BirthNumberRule {
    private val slashed = Regex("""(?<![\d/])(\d{2})(\d{2})(\d{2})/(\d{3,4})(?![\d/])""")
    private val plain = Regex("""(?<![\d/])(\d{2})(\d{2})(\d{2})(\d{4})(?![\d/])""")
    private val label = Regex("""$NOT_WORD(rodn[éeý] [čc][íi]sl[oa]|rč)$NOT_WORD_AFTER""") // on lowercased text

    fun matches(text: String): Boolean =
        slashed.findAll(text).any { valid(it.groupValues) } ||
            (label.containsMatchIn(text.lowercase()) && plain.findAll(text).any { valid(it.groupValues) })

    private fun valid(groups: List<String>): Boolean {
        val (_, yy, mm, dd, suffix) = groups
        val month = mm.toInt().let { if (it > 70) it - 70 else if (it > 50) it - 50 else if (it > 20) it - 20 else it }
        if (month !in 1..12 || dd.toInt() !in 1..31) return false
        if (suffix.length == 3) return yy.toInt() < 54
        val number = (yy + mm + dd + suffix).toLong()
        return number % 11 == 0L || ((number / 10) % 11 == 10L && number % 10 == 0L)
    }
}
