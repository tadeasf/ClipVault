package cz.tadeasf.clipvault.history

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import cz.tadeasf.clipvault.detect.SensitiveCategory
import cz.tadeasf.clipvault.model.Clip
import cz.tadeasf.clipvault.model.ClipDraft
import cz.tadeasf.clipvault.model.ContentType
import cz.tadeasf.clipvault.storage.clipDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

/** Opens a real step-1 (v1) database with the current schema, which runs 1.sqm and 2.sqm through the platform driver. */
class MigrationTest {
    private val key = ByteArray(32) { 5 }

    /** Exactly what SQLDelight generated for the step-1 schema. */
    private object V1Schema : SqlSchema<QueryResult.Value<Unit>> {
        override val version = 1L

        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            V1_DDL.forEach { driver.execute(null, it, 0) }
            return QueryResult.Unit
        }

        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> = QueryResult.Unit
    }

    @Test
    fun migratesStepOneDatabase() = runTest {
        val store = newTestDatabase()
        store.open(key, V1Schema).apply { V1_ROWS.forEach { execute(null, it, 0) } }.close()

        val driver = store.open(key)
        try {
            val db = clipDatabase(driver)
            val clips = listOf("a", "b", "c", "d").map { db.clipQueries.selectById("clip-$it", ::Clip).executeAsOne() }

            // 1.sqm: clip seqs start after the highest existing MetaEvent seq (2), in insertion order.
            assertEquals(listOf(3L, 4L, 5L, 6L), clips.map { it.seq })
            assertEquals(ContentType.PLAIN, clips[0].contentType)
            assertEquals(ContentType.PLAIN, clips[1].contentType)
            assertEquals(ContentType.URL, clips[2].contentType)
            assertTrue(clips[0].pinned)
            // 2.sqm: sensitive rows from before categories existed are UNKNOWN, the rest empty.
            assertEquals(emptySet(), clips[0].categories)
            assertEquals(setOf(SensitiveCategory.UNKNOWN), clips[3].categories)

            val repo = SqlClipRepository(db, "device-x", { 9_000L }, Dispatchers.Unconfined)
            assertEquals(listOf("clip-a"), repo.search("hub").map { it.id }) // trigram index rebuilt
            assertEquals(listOf("clip-b"), repo.search("namesti").map { it.id })
            assertEquals(listOf("clip-b"), repo.search("Vá").map { it.id }) // prefix index rebuilt
            assertEquals(7L, repo.get(repo.insert(ClipDraft("after migration")).id)?.seq)
        } finally {
            driver.close()
        }
    }

    private companion object {
        val V1_DDL = listOf(
            """
            CREATE TABLE Clip (
              id TEXT NOT NULL PRIMARY KEY,
              deviceId TEXT NOT NULL,
              createdAt INTEGER NOT NULL,
              contentType TEXT NOT NULL,
              contentHash TEXT NOT NULL,
              content TEXT NOT NULL,
              sensitive INTEGER NOT NULL,
              pinned INTEGER NOT NULL DEFAULT 0,
              deleted INTEGER NOT NULL DEFAULT 0,
              lamportClock INTEGER NOT NULL
            )
            """,
            """
            CREATE TABLE MetaEvent (
              seq INTEGER PRIMARY KEY AUTOINCREMENT,
              clipId TEXT NOT NULL,
              field TEXT NOT NULL,
              value INTEGER NOT NULL,
              lamportClock INTEGER NOT NULL,
              deviceId TEXT NOT NULL
            )
            """,
            "CREATE INDEX clip_list ON Clip(deleted, pinned, createdAt)",
            "CREATE INDEX clip_hash ON Clip(contentHash)",
            """
            CREATE TRIGGER clip_fts_insert AFTER INSERT ON Clip BEGIN
              INSERT INTO ClipFts(rowid, content) VALUES (new.rowid, new.content);
            END
            """,
            """
            CREATE TRIGGER clip_fts_delete AFTER DELETE ON Clip BEGIN
              DELETE FROM ClipFts WHERE rowid = old.rowid;
            END
            """,
            """
            CREATE TRIGGER clip_fts_update AFTER UPDATE OF content ON Clip BEGIN
              DELETE FROM ClipFts WHERE rowid = old.rowid;
              INSERT INTO ClipFts(rowid, content) VALUES (new.rowid, new.content);
            END
            """,
            "CREATE INDEX meta_clip ON MetaEvent(clipId)",
            "CREATE VIRTUAL TABLE ClipFts USING fts5(content)",
        )

        val V1_ROWS = listOf(
            "INSERT INTO Clip VALUES ('clip-a', 'dev', 1000, 'PLAIN_TEXT', 'h-a', 'migrated github note', 0, 1, 0, 1)",
            "INSERT INTO Clip VALUES ('clip-b', 'dev', 2000, 'ADDRESS', 'h-b', 'Václavské náměstí 1', 0, 0, 0, 2)",
            "INSERT INTO Clip VALUES ('clip-c', 'dev', 3000, 'URL', 'h-c', 'https://example.com', 0, 0, 0, 3)",
            "INSERT INTO Clip VALUES ('clip-d', 'dev', 4000, 'PLAIN_TEXT', 'h-d', '483920', 1, 0, 0, 4)",
            "INSERT INTO MetaEvent(clipId, field, value, lamportClock, deviceId) VALUES ('clip-a', 'PINNED', 1, 4, 'dev')",
            "INSERT INTO MetaEvent(clipId, field, value, lamportClock, deviceId) VALUES ('clip-b', 'PINNED', 0, 5, 'dev')",
        )
    }
}
