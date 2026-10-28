package cz.tadeasf.clipvault.sync.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * When the live connection (the foreground service) runs: sync enabled, network allowed, and the screen on
 * or a clip in the last [lingerMillis]. Outside that, WorkManager catches up periodically. Pure state and one
 * timer, so the policy is tested on the JVM with a virtual clock; the runtime feeds it events.
 *
 * [setRunning] starts or stops the service and returns whether it worked: a start Android refuses (from the
 * background) leaves [running] false, so the next event tries again.
 */
class KeepAlive(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val setRunning: (Boolean) -> Boolean,
    private val lingerMillis: Long = LINGER_MILLIS,
) {
    var running = false
        private set

    private var enabled = false
    private var networkAllowed = true
    private var screenOn = true
    private var lastClipAt: Long? = null
    private var timer: Job? = null

    fun enabled(on: Boolean) = set { enabled = on }

    fun networkAllowed(allowed: Boolean) = set { networkAllowed = allowed }

    fun screen(on: Boolean) = set { screenOn = on }

    /** A clip was stored here or arrived from a peer. */
    fun clip() = set { lastClipAt = now() }

    /** Re-evaluates; starts the service if it should run but isn't (the app came to the foreground). */
    fun poke() = set {}

    private fun lingering(): Boolean = lastClipAt?.let { now() - it < lingerMillis } == true

    fun shouldRun(): Boolean = enabled && networkAllowed && (screenOn || lingering())

    private fun set(change: () -> Unit) {
        change()
        timer?.cancel()
        timer = null
        val run = shouldRun()
        if (run != running && setRunning(run)) running = run
        val clipAt = lastClipAt
        if (running && !screenOn && clipAt != null) {
            val stopAt = clipAt + lingerMillis
            timer = scope.launch {
                delay((stopAt - now()).coerceAtLeast(0))
                set {}
            }
        }
    }

    companion object {
        const val LINGER_MILLIS = 5L * 60 * 1000
    }
}
