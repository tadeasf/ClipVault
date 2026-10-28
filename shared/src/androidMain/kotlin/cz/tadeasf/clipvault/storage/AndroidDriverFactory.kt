package cz.tadeasf.clipvault.storage

import android.content.Context
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import cz.tadeasf.clipvault.db.ClipDatabase
import cz.tadeasf.clipvault.security.toHex
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** [schema] is overridable so tests can create an older schema version and exercise migrations. */
class AndroidDriverFactory(
    private val context: Context,
    private val name: String = "history.db",
    private val schema: SqlSchema<QueryResult.Value<Unit>> = ClipDatabase.Schema,
) : DriverFactory {
    override fun open(key: ByteArray): SqlDriver {
        System.loadLibrary("sqlcipher")
        // SQLCipher treats the literal x'<64 hex>' as a raw key and skips PBKDF2.
        val rawKey = "x'${key.toHex()}'".encodeToByteArray()
        return AndroidSqliteDriver(schema, context, name, SupportOpenHelperFactory(rawKey))
    }

    fun exists(): Boolean = context.getDatabasePath(name).exists()
}
