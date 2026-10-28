package cz.tadeasf.clipvault.capture

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import cz.tadeasf.clipvault.graph
import cz.tadeasf.clipvault.history.IngestResult
import cz.tadeasf.clipvault.history.SkipReason
import cz.tadeasf.clipvault.model.ClipHints
import kotlinx.coroutines.launch

/**
 * Exported entry for the share sheet (ACTION_SEND text/plain) and text selection (ACTION_PROCESS_TEXT).
 * The text arrives in the intent, so no clipboard read is involved and no source app is trusted.
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            else -> null
        }?.toString()
        lifecycleScope.launch {
            val result = text?.let { graph.capture(it, ClipHints()) } ?: IngestResult.Skipped(SkipReason.BLANK)
            logCapture(source = null, result = result)
            finishWith(CaptureFeedback.message(result))
        }
    }
}
