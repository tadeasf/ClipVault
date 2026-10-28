package cz.tadeasf.clipvault.sync.android

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Step 8: the service's lifecycle under the screen-off/screen-on policy, on a virtual clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class KeepAliveTest {
    private val minute = 60_000L

    private class Service {
        val log = mutableListOf<String>()
        var refuseStart = false
    }

    private fun TestScope.keepAlive(service: Service = Service()) = KeepAlive(backgroundScope, { testScheduler.currentTime }, { run ->
        if (run && service.refuseStart) {
            service.log += "refused"
            false
        } else {
            service.log += if (run) "start" else "stop"
            true
        }
    })

    @Test
    fun screenOnKeepsItRunningAndScreenOffStopsItFiveMinutesAfterTheLastClip() = runTest {
        val service = Service()
        val k = keepAlive(service)
        k.enabled(true)
        assertTrue(k.running)
        advanceTimeBy(60 * minute)
        assertTrue(k.running, "screen on: never stops")

        k.clip()
        advanceTimeBy(1 * minute)
        k.screen(false)
        advanceTimeBy(4 * minute - 1)
        runCurrent()
        assertTrue(k.running, "5 min after the clip not reached yet")
        advanceTimeBy(2)
        runCurrent()
        assertFalse(k.running)
        assertEquals(listOf("start", "stop"), service.log)
    }

    @Test
    fun screenOffWithNoRecentClipStopsAtOnce() = runTest {
        val k = keepAlive()
        k.enabled(true)
        advanceTimeBy(10 * minute)
        k.screen(false)
        assertFalse(k.running)
    }

    @Test
    fun aClipWhileTheScreenIsOffExtendsTheLinger() = runTest {
        val k = keepAlive()
        k.enabled(true)
        k.screen(false)
        advanceTimeBy(4 * minute)
        k.clip()
        advanceTimeBy(4 * minute)
        runCurrent()
        assertTrue(k.running, "4 min after the clip")
        advanceTimeBy(1 * minute + 1)
        runCurrent()
        assertFalse(k.running)
    }

    @Test
    fun aClipWhileStoppedStartsItForFiveMinutes() = runTest {
        val k = keepAlive()
        k.enabled(true)
        k.screen(false)
        advanceTimeBy(10 * minute)
        assertFalse(k.running)
        k.clip()
        assertTrue(k.running)
        advanceTimeBy(5 * minute + 1)
        runCurrent()
        assertFalse(k.running)
    }

    @Test
    fun screenOnBeforeTheLingerEndsCancelsTheStop() = runTest {
        val k = keepAlive()
        k.enabled(true)
        k.screen(false)
        advanceTimeBy(3 * minute)
        k.screen(true)
        advanceTimeBy(30 * minute)
        runCurrent()
        assertTrue(k.running)
    }

    @Test
    fun disabledOrDisallowedNetworkStopsAtOnce() = runTest {
        val k = keepAlive()
        k.enabled(true)
        k.networkAllowed(false)
        assertFalse(k.running)
        k.networkAllowed(true)
        assertTrue(k.running)
        k.enabled(false)
        assertFalse(k.running)
        k.clip()
        assertFalse(k.running, "a clip never starts it while sync is off")
    }

    @Test
    fun aRefusedStartIsRetriedOnTheNextEvent() = runTest {
        val service = Service().apply { refuseStart = true }
        val k = keepAlive(service)
        k.enabled(true) // e.g. the process started in the background
        assertFalse(k.running)
        service.refuseStart = false
        k.poke() // the app came to the foreground
        assertTrue(k.running)
        assertEquals(listOf("refused", "start"), service.log)
    }

    @Test
    fun qrImageDecodesBackToTheText() {
        val text = "CVP1:" + "A".repeat(51)
        val qr = qrImage(text)
        val scale = 4
        val side = (qr.size + 8) * scale
        val pixels = ByteArray(side * side) { i ->
            val (x, y) = (i % side) / scale - 4 to (i / side) / scale - 4
            if (x in 0 until qr.size && y in 0 until qr.size && qr[x, y]) 0 else -1
        }
        val source = object : LuminanceSource(side, side) {
            override fun getRow(y: Int, row: ByteArray?): ByteArray = pixels.copyOfRange(y * side, (y + 1) * side)
            override fun getMatrix(): ByteArray = pixels
        }
        assertEquals(text, QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text)
    }
}
