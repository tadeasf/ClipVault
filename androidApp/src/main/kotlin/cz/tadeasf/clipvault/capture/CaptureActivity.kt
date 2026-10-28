package cz.tadeasf.clipvault.capture

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import cz.tadeasf.clipvault.graph
import cz.tadeasf.clipvault.history.IngestResult
import cz.tadeasf.clipvault.model.ClipHints
import kotlinx.coroutines.launch

/**
 * Invisible, not exported. Started by the tile, the notification action and the Accessibility service.
 * Android 10+ only lets the focused app read the clipboard, so it reads once it has window focus.
 */
class CaptureActivity : ComponentActivity() {
    private var handled = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        val read = readClipboard(getSystemService(ClipboardManager::class.java), hasFocus = true)
        val source = intent.getStringExtra(EXTRA_SOURCE_PACKAGE)
        CaptureFeedback.message(read)?.let { finishWith(it) } ?: ingest(read as ClipRead.Text, source)
    }

    private fun ingest(read: ClipRead.Text, source: String?) {
        lifecycleScope.launch {
            val result = graph.capture(read.text, ClipHints(isSensitiveFlag = read.sensitiveFlag, sourcePackage = source))
            logCapture(source, result)
            finishWith(CaptureFeedback.message(result))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handled = savedInstanceState?.getBoolean(KEY_HANDLED) ?: false
        // An invisible activity that never gets focus would block touches on whatever is underneath.
        window.decorView.postDelayed({
            if (!handled) {
                handled = true
                finishWith(CaptureFeedback.message(ClipRead.Denied)!!)
            }
        }, FOCUS_TIMEOUT_MILLIS)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_HANDLED, handled)
    }

    companion object {
        const val EXTRA_SOURCE_PACKAGE = "cz.tadeasf.clipvault.SOURCE_PACKAGE"
        private const val KEY_HANDLED = "handled"
        private const val FOCUS_TIMEOUT_MILLIS = 2_000L

        fun intent(context: Context, sourcePackage: String? = null): Intent =
            Intent(context, CaptureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_SOURCE_PACKAGE, sourcePackage)
    }
}

internal fun Activity.finishWith(message: String) {
    Toast.makeText(applicationContext, CaptureFeedback.record(message), Toast.LENGTH_SHORT).show()
    finish()
}

/** Debug builds only, and never the clip content: which path and app produced which outcome. */
internal fun Context.logCapture(source: String?, result: IngestResult) {
    if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
    val outcome = when (result) {
        is IngestResult.Stored -> if (result.duplicate) "duplicate" else if (result.sensitive) "stored-sensitive" else "stored"
        is IngestResult.Skipped -> "skipped-${result.reason}"
    }
    Log.i("ClipVaultCapture", "result=$outcome source=${source ?: "-"}")
}
