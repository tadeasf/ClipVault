package cz.tadeasf.clipvault.sync.wire

import cz.tadeasf.clipvault.sync.SyncProtocol
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json

sealed interface Decoded {
    val seq: Long

    data class Ok(override val seq: Long, val message: Message) : Decoded

    /** A message type from a newer minor version: skip it. */
    data class Ignored(override val seq: Long, val type: String) : Decoded

    /** A different major version: answer Bye and close. */
    data class Refused(override val seq: Long, val major: Int) : Decoded
}

/** CBOR on the wire (protocol.md section 2). Unknown fields are skipped, which is the minor-version rule. */
@OptIn(ExperimentalSerializationApi::class)
object WireCodec {
    private val cbor = Cbor {
        ignoreUnknownKeys = true
        alwaysUseByteString = true
    }
    private val json = Json { encodeDefaults = true }

    private val version = ProtocolVersion(SyncProtocol.MAJOR, SyncProtocol.MINOR)

    fun encode(message: Message, seq: Long, version: ProtocolVersion = this.version): ByteArray =
        cbor.encodeToByteArray(Envelope.serializer(), Envelope(version, typeOf(message), seq, cbor.encodeToByteArray(Message.serializer(), message)))

    /** Throws [SerializationException] on bytes that are not an envelope of a known major version. */
    fun decode(bytes: ByteArray): Decoded {
        val envelope = cbor.decodeFromByteArray(Envelope.serializer(), bytes)
        return when {
            envelope.version.major != SyncProtocol.MAJOR -> Decoded.Refused(envelope.seq, envelope.version.major)
            envelope.type !in TYPES -> Decoded.Ignored(envelope.seq, envelope.type)
            else -> Decoded.Ok(envelope.seq, cbor.decodeFromByteArray(Message.serializer(), envelope.payload))
        }
    }

    /** For logs and tests only; never sent. */
    fun debugJson(message: Message): String = json.encodeToString(Message.serializer(), message)

    private val TYPES = setOf("hello", "catchup_request", "catchup_batch", "push", "ack", "fetch_payload", "payload", "bye")

    /** The same names as the classes' @SerialName (checked by WireTest). */
    fun typeOf(message: Message): String = when (message) {
        is Hello -> "hello"
        is CatchUpRequest -> "catchup_request"
        is CatchUpBatch -> "catchup_batch"
        is Push -> "push"
        is Ack -> "ack"
        is FetchPayload -> "fetch_payload"
        is Payload -> "payload"
        is Bye -> "bye"
    }
}
