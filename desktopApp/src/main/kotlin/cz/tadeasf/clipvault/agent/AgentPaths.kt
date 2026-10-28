package cz.tadeasf.clipvault.agent

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** Where the agent keeps its encrypted store and its control socket (XDG base directories). */
data class AgentPaths(val dataDir: File, val runtimeDir: File) {
    val database: File get() = File(dataDir, "history.db")
    val keyFile: File get() = File(dataDir, "db.key")
    /** Sync bookkeeping (identity, peers, cursors), encrypted with the same key (protocol.md section 12). */
    val syncDatabase: File get() = File(dataDir, "sync.db")
    val socket: Path get() = File(runtimeDir, "agent.sock").toPath()

    /** Creates both directories readable by the owner only. */
    fun prepare() {
        listOf(dataDir, runtimeDir).forEach { dir ->
            dir.mkdirs()
            runCatching { Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------")) }
        }
    }

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): AgentPaths {
            val home = System.getProperty("user.home")
            val data = env["XDG_DATA_HOME"]?.takeIf(String::isNotBlank) ?: "$home/.local/share"
            val runtime = env["XDG_RUNTIME_DIR"]?.takeIf(String::isNotBlank) ?: System.getProperty("java.io.tmpdir")
            return AgentPaths(File(data, "clipvault"), File(runtime, "clipvault"))
        }
    }
}
