package cz.tadeasf.clipvault.sync.secure

import cz.tadeasf.clipvault.sync.crypto.crypto
import cz.tadeasf.clipvault.sync.pairing.QrPayload
import cz.tadeasf.clipvault.sync.pairing.sanitizeName
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecureUnitTest {
    private val c = crypto()
    private val alice = LocalIdentity(c.randomBytes(32), c)
    private val bob = LocalIdentity(c.randomBytes(32), c)
    private val mallory = LocalIdentity(c.randomBytes(32), c)

    private fun session(a: LocalIdentity = alice, b: LocalIdentity = bob, bKnows: List<ByteArray> = listOf(alice.publicKey)): Pair<SessionKeys, SessionKeys> {
        val i = Initiator(c, HandshakeMode.SESSION, ByteArray(0), a, listOf(bob.publicKey))
        val r = Responder(c, b, bKnows, { if (it == HandshakeMode.SESSION) ByteArray(0) else null })
        val (fin, ka) = i.onResp(r.onInit(i.init()))
        return ka to r.onFin(fin)
    }

    private fun failure(block: () -> Unit) = assertFailsWith<HandshakeFailure> { block() }.kind

    // Handshake

    @Test
    fun sessionHandshakeAuthenticatesBothSides() {
        val (a, b) = session()
        assertContentEquals(bob.publicKey, a.peer)
        assertContentEquals(alice.publicKey, b.peer)
        assertContentEquals(a.sendKey, b.receiveKey)
        assertContentEquals(a.h3, b.h3)
    }

    @Test
    fun unpairedDevicesAreRejected() {
        assertEquals(HandshakeFailure.Kind.NOT_AUTHENTICATED, failure { session(a = mallory) }) // Bob doesn't know Mallory
        val i = Initiator(c, HandshakeMode.SESSION, ByteArray(0), alice, listOf(bob.publicKey))
        val r = Responder(c, mallory, listOf(alice.publicKey), { ByteArray(0) })
        assertEquals(HandshakeFailure.Kind.NOT_AUTHENTICATED, failure { i.onResp(r.onInit(i.init())) }) // Alice doesn't know Mallory
    }

    @Test
    fun pairingNeedsTheBindingAndAnOpenWindow() {
        val token = c.randomBytes(16)
        val closed = Responder(c, bob, emptyList(), { if (it == HandshakeMode.SESSION) ByteArray(0) else null })
        val dial = Initiator(c, HandshakeMode.PAIR_QR, token, alice, emptyList())
        assertEquals(HandshakeFailure.Kind.MODE_NOT_ACCEPTED, failure { closed.onInit(dial.init()) })

        val open = Responder(c, bob, emptyList(), { if (it == HandshakeMode.PAIR_QR) token else null })
        val wrong = Initiator(c, HandshakeMode.PAIR_QR, c.randomBytes(16), alice, emptyList())
        assertEquals(HandshakeFailure.Kind.NOT_AUTHENTICATED, failure { wrong.onResp(open.onInit(wrong.init())) })
    }

    @Test
    fun lowOrderEphemeralKeyAborts() {
        val init = Initiator(c, HandshakeMode.SESSION, ByteArray(0), alice, listOf(bob.publicKey)).init()
        init.fill(0, 7, 39) // eph_A = 0, a low-order point
        val r = Responder(c, bob, listOf(alice.publicKey), { ByteArray(0) })
        assertEquals(HandshakeFailure.Kind.LOW_ORDER, failure { r.onInit(init) })
    }

    @Test
    fun replayedInitAndFinFail() {
        val i = Initiator(c, HandshakeMode.SESSION, ByteArray(0), alice, listOf(bob.publicKey))
        val init = i.init()
        val (fin, _) = i.onResp(Responder(c, bob, listOf(alice.publicKey), { ByteArray(0) }).onInit(init))
        // The attacker replays Init and Fin to a fresh responder: its new ephemeral key changes h2.
        val fresh = Responder(c, bob, listOf(alice.publicKey), { ByteArray(0) })
        fresh.onInit(init)
        assertEquals(HandshakeFailure.Kind.NOT_AUTHENTICATED, failure { fresh.onFin(fin) })
    }

    @Test
    fun reflectionFails() {
        // Alice's own Init reflected to Alice as responder: her own key is never among her candidates.
        val i = Initiator(c, HandshakeMode.SESSION, ByteArray(0), alice, listOf(bob.publicKey))
        val selfAsResponder = Responder(c, alice, listOf(bob.publicKey), { ByteArray(0) })
        assertEquals(HandshakeFailure.Kind.NOT_AUTHENTICATED, failure { i.onResp(selfAsResponder.onInit(i.init())) })
    }

    @Test
    fun malformedMessagesAbort() {
        val r = Responder(c, bob, listOf(alice.publicKey), { ByteArray(0) })
        assertEquals(HandshakeFailure.Kind.MALFORMED, failure { r.onInit(ByteArray(71)) })
        val init = Initiator(c, HandshakeMode.SESSION, ByteArray(0), alice, listOf(bob.publicKey)).init()
        init[5] = 2
        assertEquals(HandshakeFailure.Kind.VERSION, failure { r.onInit(init) })
    }

    // Channel

    @Test
    fun channelRoundTripsAndRejectsTamperingGapsAndWrongDirection() {
        val (a, b) = session()
        val now = { 0L }
        val toBob = SecureChannel(c, a, now)
        val atBob = SecureChannel(c, b, now)
        val f1 = toBob.seal("one".encodeToByteArray())
        val f2 = toBob.seal("two".encodeToByteArray())
        assertFailsWith<ChannelClosed> { SecureChannel(c, b, now).open(f2) } // gap: 2 before 1
        val tampered = f1.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<ChannelClosed> { SecureChannel(c, b, now).open(tampered) }
        assertFailsWith<ChannelClosed> { SecureChannel(c, a, now).open(f1) } // A->B frame read as B->A
        assertEquals(1L to "one", atBob.open(f1).let { it.first to it.second.decodeToString() })
        assertFailsWith<ChannelClosed> { atBob.open(f1) } // replay within the direction
        assertEquals("two", atBob.open(f2).second.decodeToString())
    }

    @Test
    fun sessionEndsAtTwoToTheThirtyTwoFramesOrTwentyFourHours() {
        val (a, b) = session()
        var time = 0L
        val nearLimit = SecureChannel.at(c, a, { time }, startedAt = 0, sent = SecureChannel.MAX_COUNTER - 2, received = 0)
        val receiver = SecureChannel.at(c, b, { time }, startedAt = 0, sent = 0, received = SecureChannel.MAX_COUNTER - 2)
        assertEquals("last", receiver.open(nearLimit.seal("last".encodeToByteArray())).second.decodeToString()) // counter 2^32 - 1
        assertFailsWith<ChannelClosed> { nearLimit.seal("one too many".encodeToByteArray()) }

        val fresh = SecureChannel(c, a) { time }
        time = SecureChannel.MAX_AGE_MILLIS
        assertTrue(fresh.expired)
        assertFailsWith<ChannelClosed> { fresh.seal(ByteArray(1)) }
    }

    @Test
    fun framerSplitsTheStreamAndRefusesOversizedLengths() {
        val framer = LengthFramer(max = 100)
        val stream = LengthFramer.frame("hello".encodeToByteArray()) + LengthFramer.frame("world".encodeToByteArray())
        assertEquals(emptyList(), framer.push(stream.copyOfRange(0, 7)).map { it.decodeToString() })
        assertEquals(listOf("hello", "world"), framer.push(stream.copyOfRange(7, stream.size)).map { it.decodeToString() })
        assertFailsWith<ChannelClosed> { LengthFramer(max = 100).push(LengthFramer.frame(ByteArray(101))) }
    }

    // Guard

    @Test
    fun guardBacksOffLocksOutAndLimitsCodeAttempts() {
        var time = 0L
        val guard = HandshakeGuard { time }
        repeat(3) { guard.dialFailed("10.0.0.2") }
        assertEquals(4000, guard.backoffMillis("10.0.0.2"))
        repeat(20) { guard.dialFailed("10.0.0.2") }
        assertEquals(HandshakeGuard.MAX_BACKOFF, guard.backoffMillis("10.0.0.2"))
        guard.dialSucceeded("10.0.0.2")
        assertEquals(0, guard.backoffMillis("10.0.0.2"))

        repeat(9) { guard.handshakeFailed("10.0.0.3") }
        assertTrue(guard.mayHandshake("10.0.0.3"))
        guard.handshakeFailed("10.0.0.3")
        assertFalse(guard.mayHandshake("10.0.0.3"))
        assertTrue(guard.mayHandshake("10.0.0.4"), "per address")
        time += HandshakeGuard.WINDOW
        assertTrue(guard.mayHandshake("10.0.0.3"))

        repeat(3) { guard.codeFailed("10.0.0.5") }
        assertFalse(guard.mayPairByCode("10.0.0.5"))
        time += HandshakeGuard.WINDOW + 1
        assertTrue(guard.mayPairByCode("10.0.0.5"))
    }

    // Pairing helpers

    @Test
    fun peerNamesAreSanitised() {
        assertEquals("Phone", sanitizeName("  Ph\u0000o\u001Bne\n "))
        assertEquals("evil.exe", sanitizeName("‮evil⁦.exe‏"))
        assertEquals("unnamed device", sanitizeName("\u0007‮ "))
        assertEquals("Tadeáš 📱", sanitizeName("Tadeáš 📱"))
        val capped = sanitizeName("č".repeat(40)) // 2 bytes each
        assertEquals(32, capped.length)
        assertEquals(64, capped.encodeToByteArray().size)
        assertTrue(sanitizeName("a" + "📱".repeat(20)).encodeToByteArray().size <= 64, "cut on a code point boundary")
    }

    @Test
    fun qrPayloadHasTheSpecifiedSize() {
        val qr = QrPayload(addresses = listOf(byteArrayOf(192.toByte(), 168.toByte(), 1, 23)), port = 47321, token = ByteArray(16) { 7 }, expiresAt = 1_800_000_000)
        val bytes = qr.encode()
        assertEquals(38, bytes.size)
        assertEquals(0xA5.toByte(), bytes[0], "definite-length map of 5")
        assertEquals(56, qr.toText().length)
        val back = QrPayload.parse(qr.toText())!!
        assertEquals(47321, back.port)
        assertContentEquals(qr.token, back.token)
        assertNull(QrPayload.parse("CVP1:not-cbor"))
        assertNull(QrPayload.parse("https://example.com"))
        val four = QrPayload(addresses = List(4) { ByteArray(16) }, port = 47321, token = ByteArray(16), expiresAt = 1_800_000_000)
        assertEquals(101, four.encode().size)
    }
}
