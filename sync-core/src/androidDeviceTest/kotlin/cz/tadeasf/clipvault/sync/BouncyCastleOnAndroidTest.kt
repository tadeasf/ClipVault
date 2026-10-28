package cz.tadeasf.clipvault.sync

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.agreement.jpake.JPAKEParticipant
import org.bouncycastle.crypto.agreement.jpake.JPAKEPrimeOrderGroups
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.modes.XChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * The app's own org.bouncycastle next to Android's platform copy (com.android.org.bouncycastle): ours must
 * load from the app's class loader and work, and the platform's providers must be untouched.
 */
class BouncyCastleOnAndroidTest {
    private val random = SecureRandom()

    @Test
    fun appBouncyCastleLoadsFromTheAppAndWorks() {
        val loader = X25519Agreement::class.java.classLoader!!
        assertNotEquals("java.lang.BootClassLoader", loader.javaClass.name, "our BC must not come from the platform")

        val a = X25519PrivateKeyParameters(random)
        val b = X25519PrivateKeyParameters(random)
        val ab = ByteArray(32).also { X25519Agreement().apply { init(a) }.calculateAgreement(b.generatePublicKey(), it, 0) }
        val ba = ByteArray(32).also { X25519Agreement().apply { init(b) }.calculateAgreement(a.generatePublicKey(), it, 0) }
        assertContentEquals(ab, ba)

        val id = Ed25519PrivateKeyParameters(random)
        val message = "clipvault".encodeToByteArray()
        val signature = Ed25519Signer().apply { init(true, id); update(message, 0, message.size) }.generateSignature()
        assertTrue(Ed25519Signer().apply { init(false, id.generatePublicKey()); update(message, 0, message.size) }.verifySignature(signature))

        val key = ByteArray(32).also(random::nextBytes)
        val nonce = ByteArray(24)
        val sealed = aead(true, key, nonce, message)
        assertContentEquals(message, aead(false, key, nonce, sealed))
    }

    @Test
    fun finiteFieldJpakeAgreesOnTheDevice() {
        val a = JPAKEParticipant("clipvault-A", "123456".toCharArray(), JPAKEPrimeOrderGroups.NIST_3072, SHA256Digest(), random)
        val b = JPAKEParticipant("clipvault-B", "123456".toCharArray(), JPAKEPrimeOrderGroups.NIST_3072, SHA256Digest(), random)
        val (a1, b1) = a.createRound1PayloadToSend() to b.createRound1PayloadToSend()
        a.validateRound1PayloadReceived(b1)
        b.validateRound1PayloadReceived(a1)
        val (a2, b2) = a.createRound2PayloadToSend() to b.createRound2PayloadToSend()
        a.validateRound2PayloadReceived(b2)
        b.validateRound2PayloadReceived(a2)
        assertEquals(a.calculateKeyingMaterial(), b.calculateKeyingMaterial())
    }

    @Test
    fun platformCryptoIsUntouched() {
        // Android's own BouncyCastle provider is still the repackaged platform copy.
        Security.getProvider("BC")?.let { assertTrue(it.javaClass.name.startsWith("com.android.org.bouncycastle"), it.javaClass.name) }
        assertEquals(32, MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1)).size)
        assertEquals(32, Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ByteArray(32), "HmacSHA256")) }.doFinal(byteArrayOf(1)).size)
        assertTrue(Cipher.getInstance("AES/GCM/NoPadding").provider.name.isNotEmpty())
        assertTrue(Security.getProviders().none { it.javaClass.name.startsWith("org.bouncycastle.") }, "we never register our BC as a JCA provider")
    }

    private fun aead(encrypt: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
        val cipher = XChaCha20Poly1305().apply { init(encrypt, AEADParameters(KeyParameter(key), 128, nonce)) }
        val out = ByteArray(cipher.getOutputSize(input.size))
        val n = cipher.processBytes(input, 0, input.size, out, 0)
        cipher.doFinal(out, n)
        return out
    }
}
