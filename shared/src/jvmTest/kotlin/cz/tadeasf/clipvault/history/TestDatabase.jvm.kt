package cz.tadeasf.clipvault.history

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlSchema
import cz.tadeasf.clipvault.storage.JvmDriverFactory
import java.io.File
import java.nio.file.Files

actual fun newTestDatabase(): TestDatabase {
    val file = File(Files.createTempDirectory("clipvault-test").toFile(), "history.db").apply { deleteOnExit() }
    return object : TestDatabase {
        override fun open(key: ByteArray, schema: SqlSchema<QueryResult.Value<Unit>>) =
            JvmDriverFactory(file, schema).open(key)
        override fun header() = file.inputStream().use { it.readNBytes(16) }
    }
}
