package cz.tadeasf.clipvault.sync

import cz.tadeasf.clipvault.detect.SensitiveCategory
import cz.tadeasf.clipvault.sync.wire.Ack
import cz.tadeasf.clipvault.sync.wire.Bye
import cz.tadeasf.clipvault.sync.wire.CatchUpBatch
import cz.tadeasf.clipvault.sync.wire.CatchUpRequest
import cz.tadeasf.clipvault.sync.wire.FetchPayload
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
    private val node: SyncNode,
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

    internal fun start() = node.config.let { send(Hello(it.deviceId, it.sensitiveOptIn.names(), it.maxPayloadBytes)) }

    /** One decoded message from the peer; envelope numbering and versions are the transport's job. */
    fun receive(message: Message) {
        if (!closed) handle(message)
    }

    /** Asks the peer for an over-cap clip's content. */
    fun fetch(clipId: String) = send(FetchPayload(clipId))

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
                message.events.forEach { node.applier.apply(it, peer.deviceId) }
                if (!rescanning) node.advance(peer.deviceId, message.throughSeq) // a re-scan skips events, so it never moves the cursor
                send(Ack(message.throughSeq))
                nextRequest(message, peer)
                node.changed()
            }
            is Push -> {
                received(message.event, peer)
                node.changed()
            }
            is FetchPayload -> send(Payload(message.clipId, node.log.payload(message.clipId, peer)))
            is Payload -> message.bytes?.let { node.applier.fillPayload(message.clipId, it) }
            else -> Unit // Ack is informational; pairing and revoke messages belong to the secure connection
        }
    }

    private fun hello(message: Hello) {
        if (expectedPeerDeviceId != null && message.deviceId != expectedPeerDeviceId) {
            return close("device id ${message.deviceId} does not match the authenticated identity")
        }
        peer = message
        val (cursor, optInsThen) = node.cursor(message.deviceId)
        rescan = (node.config.sensitiveOptIn - optInsThen).ifEmpty { null }
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
                rescanning = true
                send(CatchUpRequest(0, rescan.names()))
            }
            else -> {
                rescanning = false
                this.rescan = null
                node.rememberOptIns(peer.deviceId)
            }
        }
    }

    private fun received(event: SyncEvent, peer: Hello) {
        node.applier.apply(event, peer.deviceId)
        node.advance(peer.deviceId, event.seq) // pushes come in seq order; skipped seqs were filtered for us
        send(Ack(event.seq))
    }

    private fun send(message: Message) {
        if (!closed) transmit(message)
    }
}
