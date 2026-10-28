package cz.tadeasf.clipvault.history

import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlSchema
import cz.tadeasf.clipvault.storage.AndroidDriverFactory
import java.util.UUID

actual fun newTestDatabase(): TestDatabase {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val name = "test-${UUID.randomUUID()}.db"
    return object : TestDatabase {
        override fun open(key: ByteArray, schema: SqlSchema<QueryResult.Value<Unit>>) =
            AndroidDriverFactory(context, name, schema).open(key)
        override fun header() = ByteArray(16).also { header ->
            context.getDatabasePath(name).inputStream().use { it.read(header) } // readNBytes needs API 33
        }
    }
}
