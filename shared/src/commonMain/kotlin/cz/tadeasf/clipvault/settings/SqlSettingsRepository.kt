package cz.tadeasf.clipvault.settings

import cz.tadeasf.clipvault.db.ClipDatabase
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Settings in the encrypted database. Loaded once at construction (a single small read). */
class SqlSettingsRepository(private val db: ClipDatabase, private val io: CoroutineContext) : SettingsRepository {
    private val state = MutableStateFlow(load())
    private val writes = Mutex()

    override val settings: StateFlow<Settings> = state.asStateFlow()

    override suspend fun update(transform: (Settings) -> Settings) = writes.withLock {
        val next = transform(state.value)
        withContext(io) {
            db.transaction { SettingsCodec.encode(next).forEach { (key, value) -> db.settingQueries.put(key, value) } }
        }
        state.value = next
    }

    private fun load(): Settings =
        SettingsCodec.decode(db.settingQueries.all().executeAsList().associate { it.key to it.value_ })
}

/** A random per-install id, created on first use. Identifies this device's clips and events for sync. */
@OptIn(ExperimentalUuidApi::class)
fun deviceId(db: ClipDatabase): String = db.transactionWithResult {
    db.settingQueries.get(DEVICE_ID_KEY).executeAsOneOrNull()
        ?: Uuid.random().toString().also { db.settingQueries.put(DEVICE_ID_KEY, it) }
}

private const val DEVICE_ID_KEY = "deviceId"
