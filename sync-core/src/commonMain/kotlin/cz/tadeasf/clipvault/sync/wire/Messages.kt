package cz.tadeasf.clipvault.sync.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire model of docs/sync/protocol.md sections 3, 5 and 6. Fields added in a later minor version must
// have defaults: older readers skip them, newer readers fill them in for older senders.

@Serializable
data class ProtocolVersion(val major: Int, val minor: Int)

/** [seq] counts envelopes per connection and direction; [payload] is the CBOR of the message named [type]. */
@Serializable
class Envelope(val version: ProtocolVersion, val type: String, val seq: Long, val payload: ByteArray)

/** [seq] is the sender's local seq; [lamport] and [deviceId] are the author's and never change on relay. */
@Serializable
sealed interface SyncEvent {
    val seq: Long
    val lamport: Long
    val deviceId: String
    val clipId: String
}

/** [payload] is null when the clip is over the receiver's cap; it is fetched later (FetchPayload). */
@Serializable
@SerialName("clip_created")
data class ClipCreated(
    override val seq: Long,
    override val lamport: Long,
    override val deviceId: String,
    override val clipId: String,
    val createdAt: Long,
    val contentType: String,
    val contentHash: String,
    val sensitive: Boolean,
    val categories: Set<String> = emptySet(),
    val payload: ByteArray? = null,
    val payloadSize: Long = 0,
) : SyncEvent

@Serializable
@SerialName("meta_changed")
data class MetaChanged(
    override val seq: Long,
    override val lamport: Long,
    override val deviceId: String,
    override val clipId: String,
    val field: String,
    val value: Boolean,
) : SyncEvent

@Serializable
sealed interface Message

@Serializable
@SerialName("hello")
data class Hello(val deviceId: String, val sensitiveOptIn: Set<String> = emptySet(), val maxPayloadBytes: Long = DEFAULT_MAX_PAYLOAD) : Message

/** [onlySensitive] non-null: a re-scan from [sinceSeq] limited to sensitive clips of these categories (section 10). */
@Serializable
@SerialName("catchup_request")
data class CatchUpRequest(val sinceSeq: Long, val onlySensitive: Set<String>? = null) : Message

/** [throughSeq]: the highest seq scanned for this page, filtered events included. */
@Serializable
@SerialName("catchup_batch")
data class CatchUpBatch(val events: List<SyncEvent>, val hasMore: Boolean, val throughSeq: Long) : Message

@Serializable
@SerialName("push")
data class Push(val event: SyncEvent) : Message

@Serializable
@SerialName("ack")
data class Ack(val seq: Long) : Message

@Serializable
@SerialName("fetch_payload")
data class FetchPayload(val clipId: String) : Message

@Serializable
@SerialName("payload")
class Payload(val clipId: String, val bytes: ByteArray?) : Message

@Serializable
@SerialName("bye")
data class Bye(val reason: String) : Message

/** Design 5.5. */
const val DEFAULT_MAX_PAYLOAD = 1024L * 1024
