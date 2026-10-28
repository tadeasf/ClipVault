package cz.tadeasf.clipvault

import android.content.ClipData
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Not a test: for a manual end-to-end run, puts `-e e2eClip TEXT` on the phone's clipboard (generated
 * text, never read back), so the Quick Settings tile captures it. Skipped without the argument.
 */
class EndToEndHelper {
    @Test
    fun putOnClipboard() {
        val text = InstrumentationRegistry.getArguments().getString("e2eClip")
        assumeTrue("manual end-to-end helper", text != null)
        setClipboard(ClipData.newPlainText("e2e", text))
    }
}
