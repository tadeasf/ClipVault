package cz.tadeasf.clipvault.history

import kotlin.test.Test
import kotlin.test.assertEquals

class FtsQueryTest {

    private val cases = listOf(
        "" to null,
        "   " to null,
        "hello" to FtsQuery.Substring("\"hello\""),
        "  two   words " to FtsQuery.Substring("\"two\" \"words\""),
        "kód" to FtsQuery.Substring("\"kód\""),
        "go" to FtsQuery.Prefix("\"go\"*"),
        "a OR b" to FtsQuery.Prefix("\"a\"* \"OR\"* \"b\"*"),
        "say \"hi\"" to FtsQuery.Substring("\"say\" \"\"\"hi\"\"\""),
        "col:value NEAR(x)" to FtsQuery.Substring("\"col:value\" \"NEAR(x)\""),
    )

    @Test
    fun picksIndexAndQuotesTokens() {
        for ((input, expected) in cases) assertEquals(expected, ftsQuery(input), "input=<$input>")
    }
}
