package cz.tadeasf.clipvault.sync.crypto

import cz.tadeasf.clipvault.sync.secure.HandshakeMode
import cz.tadeasf.clipvault.sync.secure.Initiator
import cz.tadeasf.clipvault.sync.secure.LocalIdentity
import cz.tadeasf.clipvault.sync.secure.Responder
import cz.tadeasf.clipvault.sync.pairing.deviceIdOf
import cz.tadeasf.clipvault.sync.pairing.fingerprintWords
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The BouncyCastle primitives against tools/handshake_vectors.py, an independent Python implementation of
 * docs/sync/handshake.md and pairing.md. Every value is rebuilt here from the spec with [crypto] only.
 */
class HandshakeVectorsTest {
    private val c = crypto()
    private val vectors: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("handshake-vectors.json")) { "vectors resource missing" }
            .bufferedReader().readText(),
    ).jsonObject
    private val inputs = vectors.obj("inputs")

    private fun JsonObject.obj(key: String) = getValue(key).jsonObject
    private fun JsonObject.hex(key: String): ByteArray = getValue(key).jsonPrimitive.content.hexToByteArray()
    private fun input(key: String) = inputs.hex(key)
    private fun label(suffix: String) = "clipvault-hs-v1 $suffix".encodeToByteArray()

    @Test
    fun keysFromSeeds() {
        assertContentEquals(input("eph_a_pub"), c.x25519PublicKey(input("eph_a")))
        assertContentEquals(input("eph_b_pub"), c.x25519PublicKey(input("eph_b")))
        assertContentEquals(input("id_a_pub"), c.ed25519PublicKey(input("id_a_seed")))
        assertContentEquals(input("id_b_pub"), c.ed25519PublicKey(input("id_b_seed")))
    }

    @Test
    fun everyModeMatchesByteForByte() {
        for ((name, expected) in vectors.obj("modes")) {
            val v = expected.jsonObject
            val mode = v.getValue("mode").jsonPrimitive.int
            val session = name == "session"
            val ephA = c.x25519PublicKey(input("eph_a"))
            val ephB = c.x25519PublicKey(input("eph_b"))
            val (nonceA, nonceB) = input("nonce_a") to input("nonce_b")

            val init = byteArrayOf(0x01) + "CVHS".encodeToByteArray() + byteArrayOf(1, mode.toByte()) + ephA + nonceA
            assertContentEquals(v.hex("init"), init, "$name init")
            val t1 = lp("clipvault-hs-v1") + lp(byteArrayOf(mode.toByte())) + lp(ephA) + lp(nonceA) + lp(ephB) + lp(nonceB) + lp(v.hex("binding"))
            assertContentEquals(v.hex("t1"), t1, "$name T1")
            val h1 = c.sha256(t1)
            assertContentEquals(v.hex("h1"), h1, "$name h1")

            val ss = c.x25519(input("eph_a"), ephB)!!
            assertContentEquals(v.hex("ss"), ss, "$name ss")
            assertContentEquals(ss, c.x25519(input("eph_b"), ephA))
            val kMacA = c.hkdfSha256(ss, h1, label("mac A"), 32)
            val kMacB = c.hkdfSha256(ss, h1, label("mac B"), 32)
            assertContentEquals(v.hex("k_ab"), c.hkdfSha256(ss, h1, label("key A->B"), 32), "$name k_ab")
            assertContentEquals(v.hex("k_ba"), c.hkdfSha256(ss, h1, label("key B->A"), 32), "$name k_ba")
            assertContentEquals(v.hex("k_mac_a"), kMacA, "$name k_mac_a")
            assertContentEquals(v.hex("k_mac_b"), kMacB, "$name k_mac_b")

            val idB = if (session) c.ed25519PublicKey(input("id_b_seed")) else ByteArray(0)
            val sigB = if (session) c.ed25519Sign(input("id_b_seed"), lp(label("sig B")) + lp(h1)) else ByteArray(0)
            val macB = c.hmacSha256(kMacB, lp(label("mac B")) + lp(h1) + lp(idB))
            assertContentEquals(v.hex("sig_b"), sigB, "$name sig_b")
            assertContentEquals(v.hex("mac_b"), macB, "$name mac_b")
            assertContentEquals(v.hex("resp"), byteArrayOf(0x02) + ephB + nonceB + sigB + macB, "$name resp")

            val t2 = t1 + lp(sigB) + lp(macB) + lp(idB)
            val h2 = c.sha256(t2)
            assertContentEquals(v.hex("h2"), h2, "$name h2")
            val idA = if (session) c.ed25519PublicKey(input("id_a_seed")) else ByteArray(0)
            val sigA = if (session) c.ed25519Sign(input("id_a_seed"), lp(label("sig A")) + lp(h2)) else ByteArray(0)
            val macA = c.hmacSha256(kMacA, lp(label("mac A")) + lp(h2) + lp(idA))
            assertContentEquals(v.hex("fin"), byteArrayOf(0x03) + sigA + macA, "$name fin")
            if (session) {
                assert(c.ed25519Verify(idA, lp(label("sig A")) + lp(h2), sigA))
                assert(c.ed25519Verify(idB, lp(label("sig B")) + lp(h1), sigB))
            }
            assertContentEquals(v.hex("h3"), c.sha256(t2 + lp(sigA) + lp(macA) + lp(idA)), "$name h3")
        }
    }

    @Test
    fun framesSealOpenAndRejectTampering() {
        val keys = vectors.obj("modes").obj("session")
        for ((name, key) in listOf("a_to_b" to keys.hex("k_ab"), "b_to_a" to keys.hex("k_ba"))) {
            val f = vectors.obj("frames").obj(name)
            val counter = f.getValue("counter").jsonPrimitive.content.toLong()
            val header = "CV".encodeToByteArray() + byteArrayOf(1, 0) + u64be(counter)
            assertContentEquals(f.hex("header"), header, name)
            assertContentEquals(f.hex("nonce"), u64be(counter) + ByteArray(16), name)
            val sealed = c.xchachaSeal(key, f.hex("nonce"), header, f.hex("plaintext"))
            assertContentEquals(f.hex("ciphertext"), sealed, "$name ciphertext")
            assertContentEquals(f.hex("plaintext"), c.xchachaOpen(key, f.hex("nonce"), header, sealed))
        }
        val ab = vectors.obj("frames").obj("a_to_b")
        val negative = vectors.obj("negative")
        assertNull(c.xchachaOpen(keys.hex("k_ab"), ab.hex("nonce"), ab.hex("aad"), negative.hex("tampered_a_to_b")), "tampered tag")
        assertNull(c.xchachaOpen(keys.hex("k_ba"), ab.hex("nonce"), ab.hex("aad"), ab.hex("ciphertext")), "A->B frame under k_BA")
    }

    @Test
    fun replayedFinFailsAgainstAFreshResp() {
        val session = vectors.obj("modes").obj("session")
        val negative = vectors.obj("negative")
        val idA = input("id_a_pub")
        val freshH2 = negative.hex("fresh_h2")
        assertFalse(c.ed25519Verify(idA, lp(label("sig A")) + lp(freshH2), session.hex("sig_a")))
        val freshMac = c.hmacSha256(negative.hex("fresh_k_mac_a"), lp(label("mac A")) + lp(freshH2) + lp(idA))
        assertFalse(constantTimeEquals(freshMac, session.hex("mac_a")))
    }

    @Test
    fun pairingDerivations() {
        val pake = vectors.obj("pake_kdf")
        assertContentEquals(pake.hex("k_pake"), c.hkdfSha256(pake.hex("km"), ByteArray(0), "clipvault-pake-v1".encodeToByteArray(), 32))

        val (idA, idB) = input("id_a_pub") to input("id_b_pub")
        val fp = vectors.obj("fingerprint")
        val (lo, hi) = listOf(idA, idB).sortedWith { x, y -> x.toUByteArray().compareWith(y.toUByteArray()) }
        val digest = c.sha256(lp("clipvault-fp-v1") + lp(lo) + lp(hi))
        assertContentEquals(fp.hex("fp"), digest)
        val indices = fp.getValue("indices").jsonArray.map { it.jsonPrimitive.int }
        assertEquals(indices, (0 until 6).map { k -> bits(digest, 11 * k, 11) })

        val ids = vectors.obj("device_id")
        assertEquals(ids.getValue("a").jsonPrimitive.content, c.sha256(lp("clipvault-devid-v1") + lp(idA)).copyOf(16).toHexString())
        assertEquals(ids.getValue("b").jsonPrimitive.content, c.sha256(lp("clipvault-devid-v1") + lp(idB)).copyOf(16).toHexString())

        val identity = vectors.obj("identity")
        val h3 = vectors.obj("modes").obj("pair_qr").hex("h3")
        val signed = lp("clipvault-id-v1") + lp(h3) + lp(identity.hex("name"))
        assertContentEquals(identity.hex("sig"), c.ed25519Sign(input("id_a_seed"), signed))
    }

    /** The production state machines, fed the fixed inputs, must emit exactly the reference bytes. */
    @Test
    fun stateMachinesReproduceTheReference() {
        val idA = LocalIdentity(input("id_a_seed"), c)
        val idB = LocalIdentity(input("id_b_seed"), c)
        for ((name, expected) in vectors.obj("modes")) {
            val v = expected.jsonObject
            val mode = HandshakeMode.of(v.getValue("mode").jsonPrimitive.int.toByte())!!
            val binding = v.hex("binding")
            val a = Initiator(c, mode, binding, idA, listOf(idB.publicKey), input("eph_a"), input("nonce_a"))
            val b = Responder(c, idB, listOf(idA.publicKey), { if (it == mode) binding else null }, input("eph_b"), input("nonce_b"))
            val init = a.init()
            assertContentEquals(v.hex("init"), init, "$name init")
            val resp = b.onInit(init)
            assertContentEquals(v.hex("resp"), resp, "$name resp")
            val (fin, keysA) = a.onResp(resp)
            assertContentEquals(v.hex("fin"), fin, "$name fin")
            val keysB = b.onFin(fin)
            assertContentEquals(v.hex("k_ab"), keysA.sendKey, "$name A sends with k_ab")
            assertContentEquals(v.hex("k_ba"), keysA.receiveKey)
            assertContentEquals(v.hex("k_ba"), keysB.sendKey, "$name B sends with k_ba")
            assertContentEquals(v.hex("k_ab"), keysB.receiveKey)
            assertContentEquals(v.hex("h3"), keysA.h3, "$name h3 (A)")
            assertContentEquals(v.hex("h3"), keysB.h3, "$name h3 (B)")
            if (mode == HandshakeMode.SESSION) {
                assertContentEquals(idB.publicKey, keysA.peer)
                assertContentEquals(idA.publicKey, keysB.peer)
            } else {
                assertNull(keysA.peer)
                assertNull(keysB.peer)
            }
        }
    }

    @Test
    fun productionHelpersMatchTheReference() {
        val (idA, idB) = input("id_a_pub") to input("id_b_pub")
        val fp = vectors.obj("fingerprint")
        assertEquals(fp.getValue("words").jsonArray.map { it.jsonPrimitive.content }, fingerprintWords(c, idA, idB))
        assertEquals(fingerprintWords(c, idA, idB), fingerprintWords(c, idB, idA), "the same words on both devices")
        assertEquals(vectors.obj("device_id").getValue("a").jsonPrimitive.content, deviceIdOf(c, idA))
    }

    private fun UByteArray.compareWith(other: UByteArray): Int {
        for (i in 0 until minOf(size, other.size)) if (this[i] != other[i]) return this[i].compareTo(other[i])
        return size - other.size
    }

    /** [count] bits from bit offset [from], most significant first. */
    private fun bits(bytes: ByteArray, from: Int, count: Int): Int =
        (from until from + count).fold(0) { acc, bit -> (acc shl 1) or ((bytes[bit / 8].toInt() shr (7 - bit % 8)) and 1) }
}
