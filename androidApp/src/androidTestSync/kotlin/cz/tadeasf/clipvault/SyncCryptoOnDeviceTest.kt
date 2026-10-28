package cz.tadeasf.clipvault

import cz.tadeasf.clipvault.sync.crypto.crypto
import cz.tadeasf.clipvault.sync.pairing.QrPayload
import cz.tadeasf.clipvault.sync.pairing.fingerprintWords
import cz.tadeasf.clipvault.sync.secure.HandshakeMode
import cz.tadeasf.clipvault.sync.secure.Initiator
import cz.tadeasf.clipvault.sync.secure.LocalIdentity
import cz.tadeasf.clipvault.sync.secure.Responder
import cz.tadeasf.clipvault.sync.secure.SecureChannel
import cz.tadeasf.clipvault.sync.wire.Decoded
import cz.tadeasf.clipvault.sync.wire.Hello
import cz.tadeasf.clipvault.sync.wire.WireCodec
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The sync flavor's handshake stack inside the app (not the library test APK): run against the minified
 * build, it proves R8 and the keep rules leave BouncyCastle and kotlinx-serialization working.
 */
class SyncCryptoOnDeviceTest {
    private val c = crypto()

    @Test
    fun handshakeChannelAndPakeWorkInTheApp() {
        val a = LocalIdentity(c.randomBytes(32), c)
        val b = LocalIdentity(c.randomBytes(32), c)
        val i = Initiator(c, HandshakeMode.SESSION, ByteArray(0), a, listOf(b.publicKey))
        val r = Responder(c, b, listOf(a.publicKey), { ByteArray(0) })
        val (fin, ka) = i.onResp(r.onInit(i.init()))
        val kb = r.onFin(fin)
        assertContentEquals(b.publicKey, ka.peer)
        val toB = SecureChannel(c, ka) { 0L }
        val atB = SecureChannel(c, kb) { 0L }
        val envelope = WireCodec.encode(Hello("device-a"), toB.nextCounter)
        val (counter, plain) = atB.open(toB.seal(envelope))
        val decoded = WireCodec.decode(plain) as Decoded.Ok
        assertEquals(counter, decoded.seq)
        assertEquals(Hello("device-a"), decoded.message)

        val pa = c.jpake("clipvault-A", "123456".toCharArray())
        val pb = c.jpake("clipvault-B", "123456".toCharArray())
        val (a1, b1) = pa.round1() to pb.round1()
        pa.receiveRound1(b1)
        pb.receiveRound1(a1)
        val (a2, b2) = pa.round2() to pb.round2()
        pa.receiveRound2(b2)
        pb.receiveRound2(a2)
        assertContentEquals(pa.keyingMaterial(), pb.keyingMaterial())

        val qr = QrPayload(addresses = listOf(byteArrayOf(10, 0, 0, 2)), port = 47321, token = c.randomBytes(16), expiresAt = 1_800_000_000)
        assertEquals(38, qr.encode().size)
        assertEquals(47321, QrPayload.parse(qr.toText())!!.port)
        assertEquals(6, fingerprintWords(c, a.publicKey, b.publicKey).size)
    }
}
