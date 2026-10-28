package cz.tadeasf.clipvault.sync

import cz.tadeasf.clipvault.detect.SensitiveCategory
import cz.tadeasf.clipvault.sync.wire.Ack
import cz.tadeasf.clipvault.sync.wire.Bye
import cz.tadeasf.clipvault.sync.wire.CatchUpBatch
import cz.tadeasf.clipvault.sync.wire.CatchUpRequest
import cz.tadeasf.clipvault.sync.wire.FetchPayload
import cz.tadeasf.clipvault.sync.wire.HARD_MAX_PAYLOAD
import cz.tadeasf.clipvault.sync.wire.Hello
import cz.tadeasf.clipvault.sync.wire.Message
import cz.tadeasf.clipvault.sync.wire.Payload
import cz.tadeasf.clipvault.sync.wire.Push
import cz.tadeasf.clipvault.sync.wire.SyncEvent
import cz.tadeasf.clipvault.sync.wire.WireCodec

/**
 * One connection to one peer (protocol.md section 11). Symmetric: both sides send Hello, then each
 * pulls the other's log page by page, and once a side has served the last page it pushes new events.
 */
class SyncSession internal constructor(
    private val node: SyncEngine,
    private val transmit: (Message) -> Unit,
    private val expectedPeerDeviceId: String?,
) {
    var peer: Hello? = null
        private set
    var closed = false
        private set
    var closeReason: String? = null
        private set

    /** Sender side: the peer has caught up with us; new events go out as Push from [pushedThrough]. */
    private var live = false
    private var pushedThrough = 0L

    /** Receiver side: categories opted into since the last sync with this peer, and whether that re-scan runs. */
    private var rescan: Set<SensitiveCategory>? = null
    private var rescanning = false

    /** Receiver side: the normal catch-up from this peer has finished (later events arrive as Push). */
    var caughtUp = false
        private set

    /** Events received from this peer in batches and pushes (duplicates included). */
    var eventsReceived = 0L
        private set

    // FetchPayload: at most MAX_FETCHES in flight per peer; the rest wait here.
    private var fetchesInFlight = 0
    private val fetchQueue = ArrayDeque<String>()

    /** The opt-ins this session announced; a later settings change applies from the next session on. */
    private val optIns = node.config.sensitiveOptIn

    internal fun start() = node.config.let {
        send(Hello(it.deviceId, optIns.names(), minOf(it.maxPayloadBytes, HARD_MAX_PAYLOAD), it.syncEnabledAt.takeIf { at -> at > 0 }))
    }

    /** One decoded message from the peer; envelope numbering and versions are the transport's job. */
    fun receive(message: Message) {
        if (!closed) handle(message)
    }

    /** Asks the peer for an over-cap clip's content; at most [MAX_FETCHES] at a time, the rest queue. */
    fun fetch(clipId: String) {
        if (fetchesInFlight < MAX_FETCHES) {
            fetchesInFlight++
            send(FetchPayload(clipId))
        } else {
            fetchQueue.addLast(clipId)
        }
    }

    private fun fetched() {
        fetchesInFlight = maxOf(0, fetchesInFlight - 1)
        fetchQueue.removeFirstOrNull()?.let(::fetch)
    }

    fun close(reason: String? = null) {
        if (closed) return
        if (reason != null) send(Bye(reason))
        closed = true
        closeReason = reason
        node.detach(this)
    }

    internal fun pushNew() {
        val peer = peer
        if (!live || closed || peer == null) return
        do {
            val page = node.log.page(pushedThrough, peer)
            page.events.forEach { send(Push(it)) }
            pushedThrough = page.throughSeq
        } while (page.hasMore)
    }

    private fun handle(message: Message) {
        if (message is Hello) return hello(message)
        if (message is Bye) return close()
        val peer = peer ?: return close("${WireCodec.typeOf(message)} before hello")
        when (message) {
            is CatchUpRequest -> serve(message, peer)
            is CatchUpBatch -> {
                message.events.forEach { received(it, peer) }
                if (!rescanning) node.advance(peer.deviceId, message.throughSeq) // a re-scan skips events, so it never moves the cursor
                send(Ack(message.throughSeq))
                nextRequest(message, peer)
                node.changed()
            }
            is Push -> {
                pushed(message.event, peer)
                node.changed()
            }
            is FetchPayload -> send(Payload(message.clipId, node.log.payload(message.clipId, peer)))
            is Payload -> {
                message.bytes?.takeIf { it.size <= HARD_MAX_PAYLOAD }?.let { node.applier.fillPayload(message.clipId, it) }
                fetched()
            }
            else -> Unit // Ack is informational; pairing and revoke messages belong to the secure connection
        }
    }

    private fun hello(message: Hello) {
        if (expectedPeerDeviceId != null && message.deviceId != expectedPeerDeviceId) {
            return close("device id ${message.deviceId} does not match the authenticated identity")
        }
        peer = message
        val (cursor, optInsThen) = node.cursor(message.deviceId)
        rescan = (optIns - optInsThen).ifEmpty { null }
        send(CatchUpRequest(cursor))
    }

    private fun serve(request: CatchUpRequest, peer: Hello) {
        val page = node.log.page(request.sinceSeq, peer, request.onlySensitive?.let(::categoriesOf))
        send(CatchUpBatch(page.events, page.hasMore, page.throughSeq))
        if (!page.hasMore && request.onlySensitive == null) {
            live = true
            pushedThrough = page.throughSeq
        }
    }

    private fun nextRequest(batch: CatchUpBatch, peer: Hello) {
        val rescan = rescan
        when {
            batch.hasMore -> send(CatchUpRequest(batch.throughSeq, rescan?.takeIf { rescanning }?.names()))
            !rescanning && rescan != null -> {
                caughtUp = true
                rescanning = true
                send(CatchUpRequest(0, rescan.names()))
            }
            else -> {
                rescanning = false
                this.rescan = null
                caughtUp = true
                node.rememberOptIns(peer.deviceId, optIns)
            }
        }
    }

    /** Applies one event; the cursor advances only together with the Ack, after the apply is durable. */
    private fun received(event: SyncEvent, peer: Hello) {
        eventsReceived++
        node.applied(event, node.applier.apply(event, peer.deviceId))
    }

    private fun pushed(event: SyncEvent, peer: Hello) {
        received(event, peer)
        node.advance(peer.deviceId, event.seq) // pushes come in seq order; skipped seqs were filtered for us
        send(Ack(event.seq))
    }

    private fun send(message: Message) {
        if (!closed) transmit(message)
    }

    companion object {
        const val MAX_FETCHES = 2
    }
}
