package cz.tadeasf.clipvault.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import cz.tadeasf.clipvault.db.ClipDatabase
import java.io.File
import org.sqlite.mc.SQLiteMCSqlCipherConfig

class JvmDriverFactory(private val dbFile: File) : DriverFactory {
    override fun open(key: ByteArray): SqlDriver {
        dbFile.parentFile?.mkdirs()
        // SQLCipher v4 format with a raw key, the same scheme as the Android driver.
        val properties = SQLiteMCSqlCipherConfig.getV4Defaults().withRawUnsaltedKey(key).build().toProperties()
        return JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", properties, ClipDatabase.Schema)
    }

    fun exists(): Boolean = dbFile.exists()
}
