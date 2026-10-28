package cz.tadeasf.clipvault.sync.crypto

import java.math.BigInteger
import java.security.SecureRandom
import org.bouncycastle.crypto.CryptoException
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.agreement.jpake.JPAKEParticipant
import org.bouncycastle.crypto.agreement.jpake.JPAKEPrimeOrderGroups
import org.bouncycastle.crypto.agreement.jpake.JPAKERound1Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound2Payload
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.XChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

actual fun crypto(): Crypto = BouncyCastleCrypto

/** BouncyCastle 1.86 lightweight API only: no JCA provider is registered (handshake.md section 2). */
internal object BouncyCastleCrypto : Crypto {
    private val random = SecureRandom()

    override fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    override fun x25519PublicKey(secret: ByteArray): ByteArray = X25519PrivateKeyParameters(secret).generatePublicKey().encoded

    override fun x25519(secret: ByteArray, peerPublic: ByteArray): ByteArray? = try {
        ByteArray(32).also { X25519Agreement().apply { init(X25519PrivateKeyParameters(secret)) }.calculateAgreement(X25519PublicKeyParameters(peerPublic), it, 0) }
            .takeUnless { out -> out.all { it == 0.toByte() } }
    } catch (_: IllegalStateException) {
        null // BouncyCastle refuses an all-zero result itself
    } catch (_: IllegalArgumentException) {
        null // malformed public key
    }

    override fun ed25519PublicKey(seed: ByteArray): ByteArray = Ed25519PrivateKeyParameters(seed).generatePublicKey().encoded

    override fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray =
        Ed25519Signer().apply { init(true, Ed25519PrivateKeyParameters(seed)); update(message, 0, message.size) }.generateSignature()

    override fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = try {
        Ed25519Signer().apply { init(false, Ed25519PublicKeyParameters(publicKey)); update(message, 0, message.size) }.verifySignature(signature)
    } catch (_: IllegalArgumentException) {
        false
    }

    override fun sha256(data: ByteArray): ByteArray =
        ByteArray(32).also { SHA256Digest().apply { update(data, 0, data.size) }.doFinal(it, 0) }

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        ByteArray(32).also { HMac(SHA256Digest()).apply { init(KeyParameter(key)); update(data, 0, data.size) }.doFinal(it, 0) }

    override fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        ByteArray(length).also { HKDFBytesGenerator(SHA256Digest()).apply { init(HKDFParameters(ikm, salt, info)) }.generateBytes(it, 0, length) }

    override fun xchachaSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        aead(true, key, nonce, aad, plaintext)

    override fun xchachaOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray? = try {
        aead(false, key, nonce, aad, ciphertext)
    } catch (_: InvalidCipherTextException) {
        null
    }

    private fun aead(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        val cipher = XChaCha20Poly1305().apply { init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad)) }
        val out = ByteArray(cipher.getOutputSize(input.size))
        val written = cipher.processBytes(input, 0, input.size, out, 0)
        return out.copyOf(written + cipher.doFinal(out, written))
    }

    override fun jpake(participantId: String, code: CharArray): Jpake = BcJpake(participantId, code)

    /** Encoding of pairing.md section 2.3: integers `lp(unsigned big-endian)`, arrays `u8 count || items`. */
    private class BcJpake(id: String, code: CharArray) : Jpake {
        private val participant = JPAKEParticipant(id, code, JPAKEPrimeOrderGroups.NIST_3072, SHA256Digest(), random)

        override fun round1(): ByteArray = participant.createRound1PayloadToSend().run {
            lp(participantId) + int(gx1) + int(gx2) + ints(knowledgeProofForX1) + ints(knowledgeProofForX2)
        }

        override fun receiveRound1(payload: ByteArray) = validate {
            val r = Reader(payload)
            participant.validateRound1PayloadReceived(JPAKERound1Payload(r.text(), r.int(), r.int(), r.ints(), r.ints()).also { r.end() })
        }

        override fun round2(): ByteArray = participant.createRound2PayloadToSend().run { lp(participantId) + int(a) + ints(knowledgeProofForX2s) }

        override fun receiveRound2(payload: ByteArray) = validate {
            val r = Reader(payload)
            participant.validateRound2PayloadReceived(JPAKERound2Payload(r.text(), r.int(), r.ints()).also { r.end() })
        }

        override fun keyingMaterial(): ByteArray = fixed(participant.calculateKeyingMaterial(), KM_BYTES)

        private fun validate(block: () -> Unit) = try {
            block()
        } catch (e: CryptoException) {
            throw CryptoFailure("J-PAKE payload rejected: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw CryptoFailure("J-PAKE payload malformed: ${e.message}", e)
        } catch (e: IllegalStateException) {
            throw CryptoFailure("J-PAKE out of order: ${e.message}", e)
        }

        private fun int(value: BigInteger) = lp(unsigned(value))

        private fun ints(values: Array<BigInteger>): ByteArray {
            require(values.size <= 255)
            return byteArrayOf(values.size.toByte()) + values.fold(ByteArray(0)) { acc, v -> acc + int(v) }
        }

        private class Reader(private val bytes: ByteArray) {
            private var at = 0

            fun chunk(): ByteArray {
                if (at + 4 > bytes.size) throw IllegalArgumentException("truncated")
                val size = readU32be(bytes, at).toInt()
                at += 4
                if (size < 0 || at + size > bytes.size) throw IllegalArgumentException("truncated")
                return bytes.copyOfRange(at, at + size).also { at += size }
            }

            fun text() = chunk().decodeToString()

            fun int() = BigInteger(1, chunk())

            fun ints(): Array<BigInteger> {
                if (at >= bytes.size) throw IllegalArgumentException("truncated")
                val count = bytes[at++].toInt() and 0xff
                return Array(count) { int() }
            }

            fun end() {
                if (at != bytes.size) throw IllegalArgumentException("trailing bytes")
            }
        }
    }

    private const val KM_BYTES = 384

    private fun unsigned(value: BigInteger): ByteArray = value.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }

    private fun fixed(value: BigInteger, size: Int): ByteArray {
        val raw = unsigned(value)
        require(raw.size <= size) { "keying material too long" }
        return ByteArray(size - raw.size) + raw
    }
}
