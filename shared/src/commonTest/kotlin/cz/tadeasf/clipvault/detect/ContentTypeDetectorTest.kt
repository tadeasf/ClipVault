package cz.tadeasf.clipvault.detect

import cz.tadeasf.clipvault.model.ContentType.CODE
import cz.tadeasf.clipvault.model.ContentType.EMAIL
import cz.tadeasf.clipvault.model.ContentType.JSON
import cz.tadeasf.clipvault.model.ContentType.PHONE
import cz.tadeasf.clipvault.model.ContentType.PLAIN
import cz.tadeasf.clipvault.model.ContentType.URL
import kotlin.test.Test
import kotlin.test.assertEquals

class ContentTypeDetectorTest {

    private val english = listOf(
        """{"name": "ClipVault", "version": 1}""" to JSON,
        "[1, 2, 3]" to JSON,
        "https://github.com/tadeasf/ClipVault/pull/2?tab=files" to URL,
        "  http://localhost:8080/health  " to URL,
        "www.example.org/docs" to URL,
        "jane.doe+news@example.co.uk" to EMAIL,
        "mailto:support@example.com" to EMAIL,
        "+14155552671" to PHONE,
        "+44 20 7946 0958" to PHONE,
        "fun main() {\n    println(\"hi\")\n}" to CODE,
        "import os\n\ndef run():\n    return os.getcwd()" to CODE,
        "const x = 1;\nlet y = 2;\nconsole.log(x + y);" to CODE,
        "Meeting notes: ship step 2 on Friday." to PLAIN,
        "Line one\nLine two\nLine three" to PLAIN,
        "42" to PLAIN, // valid JSON, but not an object or array
        "{not json" to PLAIN,
        "visit https://example.com today" to PLAIN, // URL inside text is not a URL clip
    )

    private val czech = listOf(
        """{"jméno": "Jan Novák", "město": "Praha"}""" to JSON,
        "https://www.seznam.cz/?q=počasí" to URL,
        "https://mapy.cz/zakladni?x=14.4&y=50.08" to URL,
        "jan.novak@seznam.cz" to EMAIL,
        "podpora@firma.cz" to EMAIL,
        "777 123 456" to PHONE,
        "+420 602 123 456" to PHONE,
        "00420777123456" to PHONE,
        "777123456" to PHONE,
        "// spočítá součet\nval soucet = a + b;\nprintln(soucet);" to CODE,
        "Dobrý den,\nposílám fakturu.\nS pozdravem Jan" to PLAIN,
        "Václavské náměstí 1, 110 00 Praha 1" to PLAIN,
        "28. 10. 2026" to PLAIN,
        "123456-1234567890/0300" to PLAIN,
    )

    @Test
    fun englishSamples() = assertTable(english)

    @Test
    fun czechSamples() = assertTable(czech)

    private fun assertTable(cases: List<Pair<String, cz.tadeasf.clipvault.model.ContentType>>) {
        for ((text, expected) in cases) assertEquals(expected, detectContentType(text), "<$text>")
    }
}
