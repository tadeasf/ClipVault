package cz.tadeasf.clipvault.capture

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.content.pm.ApplicationInfo

/**
 * Opt-in automatic capture. Sees accessibility events only (canRetrieveWindowContent=false): it never reads
 * screen content. When an event looks like a copy, it starts CaptureActivity, which takes focus and reads
 * the clipboard. The last foreground app is passed along as the clip's source package.
 */
class ClipAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var foregroundPackage: String? = null
    private var lastTrigger = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && isAppPackage(pkg)) foregroundPackage = pkg
        val signal = CopySignal.of(event.eventType, pkg, event.text.joinToString(" "), event.contentDescription) ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrigger < DEBOUNCE_MILLIS) return
        lastTrigger = now
        val source = foregroundPackage
        debugLog("trigger=$signal event=${AccessibilityEvent.eventTypeToString(event.eventType)} pkg=$pkg source=$source")
        // Give the copying app a moment to finish writing the clip before we take focus.
        handler.postDelayed({ startActivity(CaptureActivity.intent(this, source)) }, CAPTURE_DELAY_MILLIS)
    }

    override fun onInterrupt() = Unit

    /** Ignore our own windows, the system UI and the keyboard when tracking the source app. */
    private fun isAppPackage(pkg: String): Boolean {
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        return pkg != packageName && pkg != "com.android.systemui" && pkg != ime && pkg != "android"
    }

    private fun debugLog(message: String) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.i("ClipVaultA11y", message)
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 1_000L
        const val CAPTURE_DELAY_MILLIS = 150L
    }
}

/** Which accessibility events count as "the user just copied". Pure, so it can be tested and tuned. */
object CopySignal {
    private val copyButton = Regex(
        """^(copy|copy text|copy link|copy link address|copy to clipboard|kopírovat|zkopírovat|kopírovat odkaz|zkopírovat odkaz)$""",
        RegexOption.IGNORE_CASE,
    )
    private val copiedMessage = Regex("""(copied|zkopírováno|kopírováno)""", RegexOption.IGNORE_CASE)

    /** Returns why this event is a copy signal, or null. */
    fun of(eventType: Int, pkg: String, text: String, contentDescription: CharSequence?): String? {
        val label = (text.ifBlank { contentDescription?.toString().orEmpty() }).trim()
        return when (eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "copy-button".takeIf { copyButton.matches(label) }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
            AccessibilityEvent.TYPE_ANNOUNCEMENT,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            -> "copied-message".takeIf { !pkg.startsWith(OWN_PACKAGE_PREFIX) && copiedMessage.containsMatchIn(label) }
            else -> null
        }
    }

    private const val OWN_PACKAGE_PREFIX = "cz.tadeasf.clipvault"
}
