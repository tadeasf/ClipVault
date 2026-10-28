package cz.tadeasf.clipvault

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cz.tadeasf.clipvault.capture.CaptureActivity
import cz.tadeasf.clipvault.capture.CaptureFeedback
import cz.tadeasf.clipvault.model.Clip
import java.util.UUID
import kotlinx.coroutines.runBlocking

val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

/** Unique per call, so tests never collide with each other's clips or with dedupe. */
fun token(prefix: String = "cvtest") = "$prefix${UUID.randomUUID().toString().take(8)}"

fun setClipboard(clip: ClipData?) = InstrumentationRegistry.getInstrumentation().runOnMainSync {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    if (clip == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(clip)
}

fun sensitiveClip(text: String): ClipData = ClipData.newPlainText("test", text).apply {
    description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
}

/** Launches [intent], waits for the activity to finish itself, and returns the feedback toast text. */
fun runToFinish(intent: Intent, timeoutMillis: Long = 8_000): String? {
    val scenario = ActivityScenario.launch<Activity>(intent)
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (scenario.state != Lifecycle.State.DESTROYED && System.currentTimeMillis() < deadline) Thread.sleep(50)
    check(scenario.state == Lifecycle.State.DESTROYED) { "activity did not finish: ${scenario.state}" }
    return CaptureFeedback.last
}

fun captureClipboard(): String? = runToFinish(CaptureActivity.intent(context))

fun stored(text: String): List<Clip> = runBlocking { context.graph.repository.search(text, includeSensitive = true) }
