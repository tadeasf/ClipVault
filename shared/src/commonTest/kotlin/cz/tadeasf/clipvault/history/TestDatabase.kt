package cz.tadeasf.clipvault.history

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import cz.tadeasf.clipvault.db.ClipDatabase

/** A fresh encrypted database file on the platform under test (jvm temp file / Android app storage). */
interface TestDatabase {
    fun open(key: ByteArray, schema: SqlSchema<QueryResult.Value<Unit>> = ClipDatabase.Schema): SqlDriver

    /** First 16 bytes of the database file on disk. */
    fun header(): ByteArray
}

expect fun newTestDatabase(): TestDatabase
