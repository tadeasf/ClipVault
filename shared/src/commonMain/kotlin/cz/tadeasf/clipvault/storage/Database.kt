package cz.tadeasf.clipvault.storage

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import cz.tadeasf.clipvault.db.Clip
import cz.tadeasf.clipvault.db.ClipDatabase
import cz.tadeasf.clipvault.db.MetaEvent

/** Opens the SQLCipher-encrypted history database with a raw 32-byte [key] (no passphrase KDF). */
fun interface DriverFactory {
    fun open(key: ByteArray): SqlDriver
}

fun clipDatabase(driver: SqlDriver): ClipDatabase = ClipDatabase(
    driver,
    Clip.Adapter(contentTypeAdapter = EnumColumnAdapter()),
    MetaEvent.Adapter(field_Adapter = EnumColumnAdapter()), // SQLDelight escapes `field`
)
