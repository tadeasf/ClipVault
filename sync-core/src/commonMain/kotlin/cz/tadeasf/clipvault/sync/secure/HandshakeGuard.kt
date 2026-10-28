package cz.tadeasf.clipvault.sync.secure

/**
 * Rate limits of handshake.md section 3.6 and pairing.md section 2.2, per peer address, in memory.
 * - Initiator backoff: 1 s doubling to 5 min after each failure, reset on success.
 * - Responder lockout: 10 failed handshakes in 10 min block *new* handshakes from that address for
 *   10 min. Established sessions are never touched (addresses can be spoofed on a LAN).
 * - Code pairing: 3 failures in 10 min refuse code pairing from that address for the rest of the window.
 */
class HandshakeGuard(private val now: () -> Long) {
    private val dialFailures = mutableMapOf<String, Int>()
    private val failures = mutableMapOf<String, MutableList<Long>>()
    private val lockedUntil = mutableMapOf<String, Long>()
    private val codeFailures = mutableMapOf<String, MutableList<Long>>()

    /** How long the initiator waits before dialling [address] again. */
    fun backoffMillis(address: String): Long {
        val n = dialFailures[address] ?: return 0
        return minOf(MAX_BACKOFF, 1000L shl minOf(n - 1, 20))
    }

    fun dialFailed(address: String) {
        dialFailures[address] = (dialFailures[address] ?: 0) + 1
    }

    fun dialSucceeded(address: String) {
        dialFailures -= address
    }

    fun mayHandshake(address: String): Boolean = (lockedUntil[address] ?: 0) <= now()

    fun handshakeFailed(address: String) {
        val recent = recent(failures, address)
        recent += now()
        if (recent.size >= LOCKOUT_FAILURES) lockedUntil[address] = now() + WINDOW
    }

    fun mayPairByCode(address: String): Boolean = recent(codeFailures, address).size < CODE_FAILURES

    fun codeFailed(address: String) {
        recent(codeFailures, address) += now()
    }

    private fun recent(map: MutableMap<String, MutableList<Long>>, address: String) =
        map.getOrPut(address) { mutableListOf() }.apply { removeAll { it <= now() - WINDOW } }

    companion object {
        const val WINDOW = 10L * 60 * 1000
        const val LOCKOUT_FAILURES = 10
        const val CODE_FAILURES = 3
        const val MAX_BACKOFF = 5L * 60 * 1000
    }
}
