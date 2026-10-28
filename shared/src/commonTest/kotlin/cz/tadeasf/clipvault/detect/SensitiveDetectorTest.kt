package cz.tadeasf.clipvault.detect

import cz.tadeasf.clipvault.detect.SensitiveCategory.API_KEY
import cz.tadeasf.clipvault.detect.SensitiveCategory.CARD_NUMBER
import cz.tadeasf.clipvault.detect.SensitiveCategory.CREDENTIALS_PAIR
import cz.tadeasf.clipvault.detect.SensitiveCategory.IBAN
import cz.tadeasf.clipvault.detect.SensitiveCategory.OTP
import cz.tadeasf.clipvault.detect.SensitiveCategory.PASSWORD
import cz.tadeasf.clipvault.detect.SensitiveCategory.PRIVATE_KEY
import cz.tadeasf.clipvault.detect.SensitiveCategory.SSN_LIKE_ID
import cz.tadeasf.clipvault.model.ClipHints
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SensitiveDetectorTest {

    /** Built at runtime so no token-shaped literal sits in the repo (secret scanners flag those). */
    private val fakeJwt = listOf("""{"alg":"none"}""", """{"sub":"clipvault-test"}""", "not-a-real-signature")
        .joinToString(".") { Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(it.encodeToByteArray()) }

    private val english = listOf(
        "4111 1111 1111 1111" to setOf(CARD_NUMBER),
        "My card: 5500-0000-0000-0004 exp 12/27" to setOf(CARD_NUMBER),
        "378282246310005" to setOf(CARD_NUMBER),
        "Your verification code is 482913" to setOf(OTP),
        "482913" to setOf(OTP),
        "Use PIN 4821 at the door" to setOf(OTP),
        "GB82 WEST 1234 5698 7654 32" to setOf(IBAN),
        "export OPENAI_API_KEY=sk-proj-abcdefghijklmnopqrstuvwxyz123456" to setOf(API_KEY),
        "ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789" to setOf(API_KEY),
        "AKIAIOSFODNN7EXAMPLE" to setOf(API_KEY),
        "xoxb-1234567890-abcdefghij" to setOf(API_KEY),
        "AIzaSyA1234567890abcdefghijklmnopqrstuv" to setOf(API_KEY),
        "Bearer $fakeJwt" to setOf(API_KEY),
        "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n-----END OPENSSH PRIVATE KEY-----" to setOf(PRIVATE_KEY),
        "-----BEGIN RSA PRIVATE KEY-----" to setOf(PRIVATE_KEY),
        "admin:S3cr3t!pass" to setOf(CREDENTIALS_PAIR),
        "john.doe@example.com\nTr0ub4dor&3" to setOf(CREDENTIALS_PAIR),
    )

    private val czech = listOf(
        "Váš ověřovací kód je 123456" to setOf(OTP),
        "Kód pro přihlášení: 7391" to setOf(OTP),
        "Jednorázový kód 90817265, platnost 5 minut" to setOf(OTP),
        "CZ65 0800 0000 1920 0014 5399" to setOf(IBAN),
        "IBAN: CZ6508000000192000145399" to setOf(IBAN),
        "Číslo karty 4012 8888 8888 1881" to setOf(CARD_NUMBER),
        "Rodné číslo: 780123/0008" to setOf(SSN_LIKE_ID),
        "905224/0010" to setOf(SSN_LIKE_ID),
        "RČ 7801230008" to setOf(SSN_LIKE_ID),
        "uzivatel@seznam.cz\nKoleno123!" to setOf(CREDENTIALS_PAIR),
        "jan.novak:Heslo2024!" to setOf(CREDENTIALS_PAIR),
    )

    /** Ordinary text that must never be flagged. */
    private val negatives = listOf(
        "+420 777 123 456",
        "+1 (555) 123-4567",
        "Telefon: 777 123 456",
        "2026-10-28",
        "28/10/2026",
        "Datum: 28. 10. 2026",
        "2026",
        "Rok 2026 bude dobrý",
        "123456-1234567890/0300",
        "412345-1234567898/0300", // card-like prefix and Luhn-valid digits, but a bank account
        "Číslo účtu: 19-2000145399/0800",
        "2000145399/0800",
        "Order #48213377 shipped",
        "Order number: 2024100512345675", // Luhn-valid, but no card-network prefix
        "Objednávka č. 2024118734 byla odeslána",
        "Faktura 20260145, splatnost 14 dní",
        "Variabilní symbol: 2026001234",
        "7801230008", // valid rodné číslo digits, but no slash and no label
        "781323/0007", // mod-11 valid, impossible month
        "PSČ 110 00 Praha 1",
        "Meeting at 12:30 in room 4021",
        "Schůzka v 14:30",
        "Code review tomorrow at 10",
        "Version 2.4.21 released in 2026",
        "https://example.com:8443/path",
        "localhost:8080",
        "note:remember-milk",
        "user@example.com",
        "Hello world\nhow are you",
        "Dobrý den, posílám podklady k projektu.",
    )

    @Test
    fun englishSamples() = assertTable(english)

    @Test
    fun czechSamples() = assertTable(czech)

    @Test
    fun negativesAreNotFlagged() {
        for (text in negatives) assertEquals(emptySet(), SensitiveDetector.classify(text), "<$text>")
    }

    @Test
    fun sourceSignalsMeanPasswordRegardlessOfContent() {
        val cases = listOf(
            ClipHints(isSensitiveFlag = true),
            ClipHints(sourcePackage = "com.x8bit.bitwarden"),
            ClipHints(sourcePackage = "com.kunzisoft.keepass.libre"),
            ClipHints(sourceWindowTitle = "Passwords.kdbx - KeePassXC"),
        )
        for (hints in cases) assertEquals(setOf(PASSWORD), SensitiveDetector.classify("hello there", hints), "$hints")
        assertEquals(emptySet(), SensitiveDetector.classify("hello there", ClipHints(sourcePackage = "com.whatsapp")))
    }

    @Test
    fun categoriesCombine() {
        val text = "CZ65 0800 0000 1920 0014 5399, kód 482913"
        assertTrue(SensitiveDetector.classify(text).containsAll(setOf(IBAN, OTP)))
    }

    @Test
    fun luhnKnownValues() {
        assertTrue(CardRule.luhnValid("79927398713"))
        assertEquals(false, CardRule.luhnValid("79927398710"))
    }

    private fun assertTable(cases: List<Pair<String, Set<SensitiveCategory>>>) {
        for ((text, expected) in cases) assertEquals(expected, SensitiveDetector.classify(text), "<$text>")
    }
}
