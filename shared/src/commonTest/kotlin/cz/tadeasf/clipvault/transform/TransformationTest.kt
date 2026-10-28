package cz.tadeasf.clipvault.transform

import cz.tadeasf.clipvault.transform.Transformation.Base64Decode
import cz.tadeasf.clipvault.transform.Transformation.Base64Encode
import cz.tadeasf.clipvault.transform.Transformation.CleanUrl
import cz.tadeasf.clipvault.transform.Transformation.JsonMinify
import cz.tadeasf.clipvault.transform.Transformation.JsonPretty
import cz.tadeasf.clipvault.transform.Transformation.LowerCase
import cz.tadeasf.clipvault.transform.Transformation.SentenceCase
import cz.tadeasf.clipvault.transform.Transformation.StripFormatting
import cz.tadeasf.clipvault.transform.Transformation.TitleCase
import cz.tadeasf.clipvault.transform.Transformation.Trim
import cz.tadeasf.clipvault.transform.Transformation.UpperCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransformationTest {

    private data class Case(val transformation: Transformation, val input: String, val expected: String?)

    /** expected = null means the transformation must fail (soft, as Result.failure). */
    private val cases = listOf(
        Case(Trim, "  hello \n", "hello"),
        Case(Trim, "\t Dobrý den \t", "Dobrý den"),
        Case(UpperCase, "příliš žluťoučký kůň", "PŘÍLIŠ ŽLUŤOUČKÝ KŮŇ"),
        Case(UpperCase, "hello", "HELLO"),
        Case(LowerCase, "ŠKODA Octavia", "škoda octavia"),
        Case(LowerCase, "Hello World", "hello world"),
        Case(TitleCase, "hello WORLD, it's me", "Hello World, It's Me"),
        Case(TitleCase, "václavské NÁMĚSTÍ", "Václavské Náměstí"),
        Case(SentenceCase, "HELLO. how ARE you? fine!", "Hello. How are you? Fine!"),
        Case(SentenceCase, "DOBRÝ DEN. JAK SE MÁTE?", "Dobrý den. Jak se máte?"),
        Case(StripFormatting, "a​b  c  d\n\n\n\n  e  ", "ab c d\n\ne"),
        Case(StripFormatting, "Řádek 1\r\n\t Řádek 2 ", "Řádek 1\nŘádek 2"),
        Case(
            CleanUrl,
            "https://shop.example.com/item?id=42&utm_source=news&utm_medium=email&fbclid=abc#reviews",
            "https://shop.example.com/item?id=42#reviews",
        ),
        Case(CleanUrl, "https://youtu.be/dQw4w9WgXcQ?si=XyZ123", "https://youtu.be/dQw4w9WgXcQ"),
        Case(
            CleanUrl,
            "Koukni: https://www.alza.cz/macbook?gclid=Cj0K&q=pro a https://seznam.cz/?igshid=1",
            "Koukni: https://www.alza.cz/macbook?q=pro a https://seznam.cz/",
        ),
        Case(CleanUrl, "https://example.com/search?q=utm_source&page=2", "https://example.com/search?q=utm_source&page=2"),
        Case(CleanUrl, "no link here", null),
        Case(JsonPretty, """{"a":1,"b":[true,null]}""", "{\n    \"a\": 1,\n    \"b\": [\n        true,\n        null\n    ]\n}"),
        Case(JsonPretty, "{broken", null),
        Case(JsonMinify, "{\n  \"jméno\" : \"Jan\",\n  \"věk\": 30\n}", """{"jméno":"Jan","věk":30}"""),
        Case(JsonMinify, "plain text", null),
        Case(Base64Encode, "ClipVault", "Q2xpcFZhdWx0"),
        Case(Base64Encode, "Žluťoučký kůň", "xb1sdcWlb3XEjWvDvSBrxa/FiA=="),
        Case(Base64Decode, "Q2xpcFZhdWx0", "ClipVault"),
        Case(Base64Decode, "xb1sdcWlb3XEjWvDvSBrxa/FiA==", "Žluťoučký kůň"),
        Case(Base64Decode, "Q2xpcFZhdWx0\n", "ClipVault"),
        Case(Base64Decode, "aGVsbG8gd29ybGQ", "hello world"), // missing padding
        Case(Base64Decode, "//79", null), // valid base64, binary result
        Case(Base64Decode, "not base64!", null),
        Case(Base64Decode, "AAEC", null), // control characters
    )

    @Test
    fun transformationTable() {
        for ((transformation, input, expected) in cases) {
            val result = transformation(input)
            if (expected == null) {
                assertTrue(result.isFailure, "$transformation should fail on <$input>")
            } else {
                assertEquals(expected, result.getOrThrow(), "$transformation on <$input>")
            }
        }
    }

    @Test
    fun idempotentTransformations() {
        val idempotent = listOf(Trim, UpperCase, LowerCase, TitleCase, SentenceCase, StripFormatting, CleanUrl, JsonPretty, JsonMinify)
        for ((transformation, input, expected) in cases) {
            if (transformation !in idempotent || expected == null) continue
            assertEquals(expected, transformation(expected).getOrThrow(), "$transformation twice on <$input>")
        }
    }

    @Test
    fun roundTrips() {
        val texts = listOf("ClipVault", "Příliš žluťoučký kůň úpěl ďábelské ódy", "line1\nline2\ttab", "{\"a\": [1, 2]}")
        for (text in texts) {
            assertEquals(text, Base64Decode(Base64Encode(text).getOrThrow()).getOrThrow())
        }
        val json = """{"a":{"b":[1,2,{"c":"č"}]},"d":null}"""
        assertEquals(json, JsonMinify(JsonPretty(json).getOrThrow()).getOrThrow())
    }

    @Test
    fun registryListsEveryTransformation() {
        assertEquals(11, transformations.size)
        assertEquals(transformations.size, transformations.toSet().size)
    }
}
