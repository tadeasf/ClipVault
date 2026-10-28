package cz.tadeasf.clipvault

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.tadeasf.clipvault.capture.ShareActivity
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real CaptureActivity / ShareActivity against the real encrypted database, on a device. */
@RunWith(AndroidJUnit4::class)
class CaptureTest {

    @Test
    fun capturesPlainTextFromTheClipboard() {
        val text = token()
        setClipboard(ClipData.newPlainText("t", text))
        assertEquals("Saved", captureClipboard())
        assertEquals(listOf(text), stored(text).map { it.content })
    }

    @Test
    fun secondCaptureOfTheSameTextIsADuplicate() {
        val text = token()
        setClipboard(ClipData.newPlainText("t", text))
        assertEquals("Saved", captureClipboard())
        assertEquals("Already saved, moved to the top", captureClipboard())
        assertEquals(1, stored(text).size)
    }

    @Test
    fun clipMarkedSensitiveByTheSourceIsNotStored() {
        val text = token("secret")
        setClipboard(sensitiveClip(text))
        assertEquals("Sensitive, not saved", captureClipboard())
        assertEquals(emptyList(), stored(text))
    }

    @Test
    fun sensitiveContentIsStoredHidden() {
        val otp = Random.nextInt(100_000, 999_999).toString()
        setClipboard(ClipData.newPlainText("t", "Váš ověřovací kód je $otp"))
        assertEquals("Saved as sensitive (hidden, expires soon)", captureClipboard())
        assertTrue(stored(otp).single().sensitive)
    }

    @Test
    fun emptyClipboardIsReported() {
        setClipboard(null)
        assertEquals("Clipboard is empty", captureClipboard())
    }

    @Test
    fun nonTextClipIsIgnored() {
        setClipboard(ClipData.newRawUri("image", Uri.parse("content://cz.tadeasf.clipvault.test/image/1")))
        assertEquals("Only text is captured", captureClipboard())
    }

    @Test
    fun shareSheetTextIsStored() {
        val text = token("shared")
        val intent = Intent(context, ShareActivity::class.java).setAction(Intent.ACTION_SEND)
            .setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        assertEquals("Saved", runToFinish(intent))
        assertEquals(1, stored(text).size)
    }

    @Test
    fun selectedTextIsStored() {
        val text = token("selected")
        val intent = Intent(context, ShareActivity::class.java).setAction(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        assertEquals("Saved", runToFinish(intent))
        assertEquals(1, stored(text).size)
    }

    @Test
    fun emptyShareIsReported() {
        val intent = Intent(context, ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
        assertEquals("Clipboard is empty", runToFinish(intent))
    }
}
