package cz.tadeasf.clipvault.storage

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import cz.tadeasf.clipvault.db.Clip
import cz.tadeasf.clipvault.db.ClipDatabase
import cz.tadeasf.clipvault.db.MetaEvent
import cz.tadeasf.clipvault.detect.SensitiveCategory

/** Opens the SQLCipher-encrypted history database with a raw 32-byte [key] (no passphrase KDF). */
fun interface DriverFactory {
    fun open(key: ByteArray): SqlDriver
}

fun clipDatabase(driver: SqlDriver): ClipDatabase = ClipDatabase(
    driver,
    Clip.Adapter(contentTypeAdapter = EnumColumnAdapter(), categoriesAdapter = CategoriesAdapter),
    MetaEvent.Adapter(field_Adapter = EnumColumnAdapter()), // SQLDelight escapes `field`
)

/** Comma-separated category names; empty string for a non-sensitive clip. */
internal object CategoriesAdapter : ColumnAdapter<Set<SensitiveCategory>, String> {
    override fun decode(databaseValue: String) =
        databaseValue.split(',').filter(String::isNotEmpty).map(SensitiveCategory::valueOf).toSet()

    override fun encode(value: Set<SensitiveCategory>) = value.sortedBy { it.ordinal }.joinToString(",") { it.name }
}
