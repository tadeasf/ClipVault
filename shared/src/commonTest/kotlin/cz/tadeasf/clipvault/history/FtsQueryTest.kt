package cz.tadeasf.clipvault.history

import kotlin.test.Test
import kotlin.test.assertEquals

class FtsQueryTest {

    private val cases = listOf(
        "" to null,
        "   " to null,
        "hello" to "\"hello\"*",
        "  two   words " to "\"two\"* \"words\"*",
        "a OR b" to "\"a\"* \"OR\"* \"b\"*",
        "say \"hi\"" to "\"say\"* \"\"\"hi\"\"\"*",
        "col:value NEAR(x)" to "\"col:value\"* \"NEAR(x)\"*",
    )

    @Test
    fun buildsQuotedPrefixQueries() {
        for ((input, expected) in cases) assertEquals(expected, ftsQuery(input), "input=<$input>")
    }
}
