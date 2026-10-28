package cz.tadeasf.clipvault.sync

import cz.tadeasf.clipvault.db.ClipDatabase
import cz.tadeasf.clipvault.detect.SensitiveCategory
import cz.tadeasf.clipvault.history.SqlClipRepository
import cz.tadeasf.clipvault.model.ClipDraft
import cz.tadeasf.clipvault.model.ClipHints
import cz.tadeasf.clipvault.security.sha256Hex
import cz.tadeasf.clipvault.storage.JvmDriverFactory
import cz.tadeasf.clipvault.storage.clipDatabase
import cz.tadeasf.clipvault.sync.db.SyncDatabase
import cz.tadeasf.clipvault.sync.wire.Hello
import cz.tadeasf.clipvault.sync.wire.Decoded
import cz.tadeasf.clipvault.sync.wire.SyncEvent
import cz.tadeasf.clipvault.sync.wire.WireCodec
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers

/** What a user sees of one clip. Devices have converged when these lists are equal. */
data class Visible(val hash: String, val content: String, val pinned: Boolean, val createdAt: Long, val sensitive: Boolean)

/** A device with real encrypted databases, the real repository for local actions, and a sync node. */
class Device(val id: String, dir: File, config: SyncConfig, private val now: () -> Long) {
    private val key = ByteArray(32) { 7 }
    private val clipDriver = JvmDriverFactory(File(dir, "$id-history.db")).open(key)
    private val syncDriver = JvmDriverFactory(File(dir, "$id-sync.db"), SyncDatabase.Schema).open(key)
    val db: ClipDatabase = clipDatabase(clipDriver)
    val syncDb = SyncDatabase(syncDriver)
    /** The id local events are authored under; enableSync switches it to the identity-derived one. */
    var deviceId: String = id
    private val repo get() = SqlClipRepository(db, deviceId, now, Dispatchers.Unconfined)
    val node = SyncEngine(db, syncDb, config)

    /** A local write without notifying the engine (the SyncNode's write { } does that, on its dispatcher). */
    suspend fun add(text: String): String = repo.insert(ClipDraft(text)).id

    suspend fun copy(text: String, sourcePackage: String? = null): String =
        repo.insert(ClipDraft(text, ClipHints(sourcePackage = sourcePackage))).id.also { node.changed() }

    suspend fun pin(text: String, pinned: Boolean = true) = rowOf(text)?.let { repo.setPinned(it, pinned); node.changed() }

    suspend fun delete(text: String) = rowOf(text)?.let { repo.delete(it); node.changed() }

    fun rowOf(text: String): String? = db.clipQueries.findLiveByHash(sha256Hex(text)).executeAsOneOrNull()

    fun state(): List<Visible> = db.clipQueries.page(true, false, null, 10_000, 0).executeAsList()
        .map { Visible(it.contentHash, it.content, it.pinned, it.createdAt, it.sensitive) }
        .sortedBy { it.hash }

    fun texts(): Set<String> = state().map { it.content }.toSet()

    /** This device's whole log as a peer that accepts everything would get it. */
    fun export(): List<SyncEvent> {
        val everything = Hello("export", SensitiveCategory.entries.map { it.name }.toSet(), Long.MAX_VALUE)
        val events = mutableListOf<SyncEvent>()
        var since = 0L
        do {
            val page = node.log.page(since, everything)
            events += page.events
            since = page.throughSeq
        } while (page.hasMore)
        return events
    }

    fun close() {
        clipDriver.close()
        syncDriver.close()
    }
}

/**
 * In-process network: devices and the connections between them, each an ordered queue per direction
 * (like TCP). [drain] delivers one message at a time, picking the connection at random when given a
 * [Random], which interleaves the conversations.
 */
class Net(vararg ids: String, configure: (String) -> SyncConfig = { SyncConfig(it) }) : AutoCloseable {
    private val dir: File = Files.createTempDirectory("cv-sync").toFile()
    private var time = 1_000L
    val devices = ids.associateWith { Device(it, dir, configure(it)) { time++ } }

    operator fun get(id: String) = devices.getValue(id)

    /** An unencrypted connection: envelopes over two FIFO queues (the secure one is SecureHarness). */
    inner class Link(val a: Device, val b: Device) {
        val toA = ArrayDeque<ByteArray>()
        val toB = ArrayDeque<ByteArray>()
        private var seqToA = 0L
        private var seqToB = 0L
        val atA: SyncSession = a.node.connect { toB.addLast(WireCodec.encode(it, ++seqToB)) }
        val atB: SyncSession = b.node.connect { toA.addLast(WireCodec.encode(it, ++seqToA)) }

        fun deliverOne(towardsA: Boolean) {
            val (queue, session) = if (towardsA) toA to atA else toB to atB
            session.receive((WireCodec.decode(queue.removeFirst()) as Decoded.Ok).message)
            listOf(atA, atB).forEach { s -> s.closeReason?.let { fail("${a.id}-${b.id} closed: $it") } }
        }
    }

    val links = mutableListOf<Link>()

    fun connect(a: String, b: String): Link = Link(get(a), get(b)).also { links += it }

    fun disconnect(link: Link) {
        link.atA.close()
        link.atB.close()
        links -= link
    }

    fun disconnectAll() = links.toList().forEach(::disconnect)

    fun link(a: String, b: String) = links.firstOrNull { setOf(it.a.id, it.b.id) == setOf(a, b) }

    /** Delivers up to [max] messages; returns false when every queue was empty. */
    fun step(random: Random? = null, max: Int = 1): Boolean {
        repeat(max) {
            val ready = links.flatMap { l -> listOfNotNull(l.takeIf { it.toA.isNotEmpty() }?.to(true), l.takeIf { it.toB.isNotEmpty() }?.to(false)) }
            if (ready.isEmpty()) return false
            val (link, towardsA) = if (random == null) ready.first() else ready.random(random)
            link.deliverOne(towardsA)
        }
        return true
    }

    fun drain(random: Random? = null) {
        var steps = 0
        while (step(random)) check(++steps < 100_000) { "no quiescence" }
    }

    /** Connects [a] and [b], lets them talk until quiet, and hangs up. */
    fun sync(a: String, b: String, random: Random? = null) {
        val link = connect(a, b)
        drain(random)
        disconnect(link)
    }

    fun syncEveryone(random: Random? = null) {
        disconnectAll()
        val ids = devices.keys.toList()
        for (i in ids.indices) for (j in i + 1 until ids.size) connect(ids[i], ids[j])
        drain(random)
    }

    fun states() = devices.mapValues { it.value.state() }

    override fun close() {
        devices.values.forEach { it.close() }
        dir.deleteRecursively()
    }
}
