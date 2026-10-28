package cz.tadeasf.clipvault.sync

import cz.tadeasf.clipvault.sync.wire.Ack
import cz.tadeasf.clipvault.sync.wire.Bye
import cz.tadeasf.clipvault.sync.wire.CatchUpBatch
import cz.tadeasf.clipvault.sync.wire.CatchUpRequest
import cz.tadeasf.clipvault.sync.wire.ClipCreated
import cz.tadeasf.clipvault.sync.wire.Decoded
import cz.tadeasf.clipvault.sync.wire.Envelope
import cz.tadeasf.clipvault.sync.wire.FetchPayload
import cz.tadeasf.clipvault.sync.wire.Hello
import cz.tadeasf.clipvault.sync.wire.Message
import cz.tadeasf.clipvault.sync.wire.MetaChanged
import cz.tadeasf.clipvault.sync.wire.Payload
import cz.tadeasf.clipvault.sync.wire.ProtocolVersion
import cz.tadeasf.clipvault.sync.wire.Push
import cz.tadeasf.clipvault.sync.wire.WireCodec
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

@OptIn(ExperimentalSerializationApi::class)
class WireTest {
    private val clip = ClipCreated(
        seq = 7, lamport = 3, deviceId = "A", clipId = "c1", createdAt = 1000, contentType = "PLAIN",
        contentHash = "ab", sensitive = false, payload = byteArrayOf(0, 1, -1), payloadSize = 3,
    )
    private val messages: List<Message> = listOf(
        Hello("A", setOf("OTP")),
        CatchUpRequest(5),
        CatchUpRequest(0, setOf("OTP")),
        CatchUpBatch(listOf(clip, MetaChanged(8, 4, "B", "c1", "PINNED", true)), hasMore = true, throughSeq = 8),
        Push(clip),
        Ack(8),
        FetchPayload("c1"),
        Payload("c1", null),
        Bye("done"),
    )

    @Test
    fun everyMessageRoundTripsThroughCbor() {
        messages.forEachIndexed { i, message ->
            val decoded = WireCodec.decode(WireCodec.encode(message, i + 1L))
            assertIs<Decoded.Ok>(decoded)
            assertEquals(i + 1L, decoded.seq)
            assertEquals(WireCodec.debugJson(message), WireCodec.debugJson(decoded.message))
        }
        val payload = (WireCodec.decode(WireCodec.encode(Push(clip), 1)) as Decoded.Ok).message as Push
        assertContentEquals(clip.payload, (payload.event as ClipCreated).payload)
    }

    @Test
    fun envelopeTypeMatchesTheSerialName() {
        messages.forEach { assertContains(WireCodec.debugJson(it), "\"type\":\"${WireCodec.typeOf(it)}\"") }
    }

    /** What a v1.1 peer might send: a new field in Hello. */
    @Serializable
    private sealed interface MessageV11

    @Serializable
    @SerialName("hello")
    private data class HelloV11(val deviceId: String, val nickname: String) : MessageV11

    private val cbor = Cbor { alwaysUseByteString = true }

    @Test
    fun higherMinorIsReadIgnoringUnknownFields() {
        val body = cbor.encodeToByteArray(MessageV11.serializer(), HelloV11("A", "Phone"))
        val bytes = cbor.encodeToByteArray(Envelope.serializer(), Envelope(ProtocolVersion(1, 1), "hello", 1, body))
        assertEquals(Hello("A"), (WireCodec.decode(bytes) as Decoded.Ok).message)
    }

    @Test
    fun unknownMessageTypeFromHigherMinorIsIgnored() {
        val bytes = cbor.encodeToByteArray(Envelope.serializer(), Envelope(ProtocolVersion(1, 3), "gossip", 4, byteArrayOf(1)))
        assertEquals(Decoded.Ignored(4, "gossip"), WireCodec.decode(bytes))
    }

    @Test
    fun differentMajorIsRefused() {
        assertEquals(Decoded.Refused(1, 2), WireCodec.decode(WireCodec.encode(Ack(1), 1, ProtocolVersion(2, 0))))
    }
}
