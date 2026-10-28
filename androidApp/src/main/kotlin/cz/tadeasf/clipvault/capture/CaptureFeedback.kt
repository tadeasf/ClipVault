package cz.tadeasf.clipvault.capture

import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import cz.tadeasf.clipvault.history.IngestResult
import cz.tadeasf.clipvault.history.SkipReason

/** What reading the clipboard produced. */
sealed interface ClipRead {
    data class Text(val text: String, val sensitiveFlag: Boolean) : ClipRead
    data object Empty : ClipRead
    data object NotText : ClipRead

    /** Android 10+ returns nothing to apps without focus; we report it instead of guessing "empty". */
    data object Denied : ClipRead
}

private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE" // ClipDescription.EXTRA_IS_SENSITIVE, API 33

fun readClipboard(clipboard: ClipboardManager, hasFocus: Boolean): ClipRead {
    if (!hasFocus) return ClipRead.Denied
    val clip = clipboard.primaryClip ?: return ClipRead.Empty
    if (clip.itemCount == 0) return ClipRead.Empty
    val text = clip.getItemAt(0).text?.toString() ?: return ClipRead.NotText
    return ClipRead.Text(text, sensitiveFlag = clip.description.isMarkedSensitive())
}

private fun ClipDescription.isMarkedSensitive(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && extras?.getBoolean(EXTRA_IS_SENSITIVE) == true

/** The toast every capture path shows. Kept in one place so tests can check it. */
object CaptureFeedback {
    /** Last message shown; read by instrumented tests only. */
    @Volatile
    var last: String? = null
        private set

    fun message(read: ClipRead): String? = when (read) {
        is ClipRead.Text -> null
        ClipRead.Empty -> "Clipboard is empty"
        ClipRead.NotText -> "Only text is captured"
        ClipRead.Denied -> "Couldn't read the clipboard. Open ClipVault and try again"
    }

    fun message(result: IngestResult): String = when (result) {
        is IngestResult.Stored -> when {
            result.duplicate -> "Already saved, moved to the top"
            result.sensitive -> "Saved as sensitive (hidden, expires soon)"
            else -> "Saved"
        }
        is IngestResult.Skipped -> when (result.reason) {
            SkipReason.BLANK -> "Clipboard is empty"
            SkipReason.SENSITIVE_SOURCE -> "Sensitive, not saved"
            SkipReason.EXCLUDED_SOURCE -> "Excluded app, not saved"
        }
    }

    fun record(message: String) = message.also { last = it }
}
