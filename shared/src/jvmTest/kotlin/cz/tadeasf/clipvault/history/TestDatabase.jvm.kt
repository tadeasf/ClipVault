package cz.tadeasf.clipvault.history

import cz.tadeasf.clipvault.storage.JvmDriverFactory
import java.io.File
import java.nio.file.Files

actual fun newTestDatabase(): TestDatabase {
    val file = File(Files.createTempDirectory("clipvault-test").toFile(), "history.db").apply { deleteOnExit() }
    return object : TestDatabase {
        override fun open(key: ByteArray) = JvmDriverFactory(file).open(key)
        override fun header() = file.inputStream().use { it.readNBytes(16) }
    }
}
