package cz.tadeasf.clipvault.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import cz.tadeasf.clipvault.db.ClipDatabase
import java.io.File
import org.sqlite.mc.SQLiteMCSqlCipherConfig

/** [schema] is overridable so tests can create an older schema version and exercise migrations. */
class JvmDriverFactory(
    private val dbFile: File,
    private val schema: SqlSchema<QueryResult.Value<Unit>> = ClipDatabase.Schema,
) : DriverFactory {
    override fun open(key: ByteArray): SqlDriver {
        dbFile.parentFile?.mkdirs()
        // SQLCipher v4 format with a raw key, the same scheme as the Android driver.
        val config = SQLiteMCSqlCipherConfig.getV4Defaults().withRawUnsaltedKey(key).build()
        config.setBusyTimeout(BUSY_TIMEOUT_MILLIS) // the desktop agent's daemon and a fallback `ingest` may overlap
        val properties = config.toProperties()
        return JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", properties, schema)
    }

    fun exists(): Boolean = dbFile.exists()
}

private const val BUSY_TIMEOUT_MILLIS = 5_000
