package cz.tadeasf.clipvault

import android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
import android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED
import android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
import android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.tadeasf.clipvault.capture.CopySignal
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CopySignalTest {

    private data class Case(val type: Int, val pkg: String, val text: String, val desc: String?, val expected: String?)

    private val cases = listOf(
        // One UI's "Copied to clipboard" (the signal that fired on the SM-S916B in Chrome and Firefox)
        Case(TYPE_NOTIFICATION_STATE_CHANGED, "com.android.systemui", "Copied to clipboard", null, "copied-message"),
        Case(TYPE_NOTIFICATION_STATE_CHANGED, "com.android.systemui", "Zkopírováno do schránky", null, "copied-message"),
        Case(TYPE_VIEW_CLICKED, "com.android.chrome", "Copy", null, "copy-button"),
        Case(TYPE_VIEW_CLICKED, "org.telegram.messenger", "", "Kopírovat", "copy-button"),
        Case(TYPE_VIEW_CLICKED, "com.android.chrome", "Copy link address", null, "copy-button"),
        // Not copies
        Case(TYPE_VIEW_CLICKED, "com.android.chrome", "Copyright 2026", null, null),
        Case(TYPE_VIEW_CLICKED, "com.android.chrome", "Select all", null, null),
        Case(TYPE_VIEW_TEXT_SELECTION_CHANGED, "com.android.chrome", "Copy", null, null),
        Case(TYPE_WINDOW_STATE_CHANGED, "com.whatsapp", "WhatsApp", null, null),
        // Our own snackbars/toasts must never trigger a capture loop
        Case(TYPE_NOTIFICATION_STATE_CHANGED, "cz.tadeasf.clipvault", "Copied to clipboard", null, null),
        Case(TYPE_NOTIFICATION_STATE_CHANGED, "cz.tadeasf.clipvault.sync", "Copied to clipboard", null, null),
    )

    @Test
    fun classifiesEvents() {
        for (case in cases) {
            assertEquals(case.expected, CopySignal.of(case.type, case.pkg, case.text, case.desc), "$case")
        }
    }
}
