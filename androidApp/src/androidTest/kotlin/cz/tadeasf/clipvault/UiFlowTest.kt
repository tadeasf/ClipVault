package cz.tadeasf.clipvault

import android.content.ClipData
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Capture -> the row shows up -> long-press opens detail -> delete -> the row is gone. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UiFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun captureShowOpenDelete() {
        val text = token("uiflow")
        setClipboard(ClipData.newPlainText("t", text))
        assertEquals("Saved", captureClipboard())

        val row = hasTestTag("clip:$text")
        compose.waitUntilExactlyOneExists(row, timeoutMillis = 5_000)
        compose.onNode(row).performTouchInput { longClick() }

        compose.waitUntilExactlyOneExists(hasTestTag("content") and hasText(text), timeoutMillis = 5_000)
        compose.onNodeWithTag("delete").performClick()

        compose.waitUntilDoesNotExist(row, timeoutMillis = 5_000)
        assertTrue(stored(text).isEmpty())
    }
}
