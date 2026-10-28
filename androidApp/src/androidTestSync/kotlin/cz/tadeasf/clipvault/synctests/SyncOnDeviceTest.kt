package cz.tadeasf.clipvault.synctests

import android.app.ActivityManager
import android.util.Log
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import cz.tadeasf.clipvault.MainActivity
import cz.tadeasf.clipvault.context
import cz.tadeasf.clipvault.storage.AndroidDriverFactory
import cz.tadeasf.clipvault.token
import cz.tadeasf.clipvault.storage.clipDatabase
import cz.tadeasf.clipvault.sync.android.CatchUp
import cz.tadeasf.clipvault.sync.android.NsdDiscovery
import cz.tadeasf.clipvault.sync.android.SyncRuntime
import cz.tadeasf.clipvault.sync.android.SyncService
import cz.tadeasf.clipvault.sync.crypto.crypto
import cz.tadeasf.clipvault.sync.db.SyncDatabase
import cz.tadeasf.clipvault.ui.sync.PairMethod
import cz.tadeasf.clipvault.ui.sync.PairingProgress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * Step 8 on the device. Runtimes run on throwaway databases (never the app's store), except the
 * permission tests, which drive the real screens and turn sync off again afterwards.
 *
 * The desktop tests need a throwaway agent showing a code (`pair --code`) and its code passed as the
 * instrumentation argument `desktopCode`; without it they are skipped.
 */
class SyncOnDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val names = mutableListOf<String>()
    private var runtime: SyncRuntime? = null

    /** A runtime on its own encrypted databases, installed as the process's runtime (service and worker use it). */
    private fun testRuntime(): SyncRuntime {
        val id = token("synctest")
        names += listOf("$id.db", "$id-sync.db")
        val key = crypto().randomBytes(32)
        val db = clipDatabase(AndroidDriverFactory(context, "$id.db").open(key))
        val syncDb = SyncDatabase(AndroidDriverFactory(context, "$id-sync.db", SyncDatabase.Schema).open(key))
        return SyncRuntime(context, db, syncDb, excluded = { emptySet() }).also {
            runtime = it
            SyncRuntime.install(it)
        }
    }

    @After
    fun tearDown() {
        runtime?.close()
        SyncRuntime.install(null)
        names.forEach { context.deleteDatabase(it) }
        context.stopService(SyncService.intent(context))
    }

    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).close()

    @Suppress("DEPRECATION") // getRunningServices still lists the caller's own services
    private fun serviceRunning(): Boolean = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(50).any { it.service.className == SyncService::class.java.name && it.foreground }

    private fun waitFor(millis: Long = 10_000, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + millis
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out: $what")
            Thread.sleep(100)
        }
    }

    @Test
    fun nsdFindsTheDesktopShowingACode() = runBlocking<Unit> {
        assumeTrue("needs a desktop agent running `pair --code`", args.getString("desktopCode") != null)
        val nsd = NsdDiscovery(context, crypto())
        try {
            val started = System.currentTimeMillis()
            val found = nsd.pairingCandidates(15_000)
            Log.i(TAG, "nsd: ${found.size} device(s) with pairing=1 after ${System.currentTimeMillis() - started} ms: $found")
            assertTrue(found.isNotEmpty(), "no _clipvault._tcp service with pairing=1")
        } finally {
            nsd.close()
        }
    }

    @Test
    fun pairsByCodeWithTheDesktop() = runBlocking<Unit> {
        val code = args.getString("desktopCode")
        assumeTrue("needs a desktop agent running `pair --code`", code != null)
        val rt = testRuntime()
        rt.setEnabled(true)
        val steps = withTimeout(60_000) {
            rt.pair(PairMethod.EnterCode(code!!)).onEach { step -> if (step is PairingProgress.Confirm) step.answer(true) }.toList()
        }
        Log.i(TAG, "pair by code: ${steps.map { it::class.simpleName }}")
        val last = steps.last()
        assertTrue(last is PairingProgress.Paired, "ended with $last")
        assertEquals(1, rt.state.value.peers.size)
    }

    @Test
    fun deniedLocationKeepsTheAllowlistOffAndDeniedCameraKeepsTheMethods() {
        // USER_FIXED: Android answers "denied" at once, the path a user's "Don't allow" takes.
        val pkg = context.packageName
        listOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "CAMERA").forEach {
            shell("pm revoke $pkg android.permission.$it")
            shell("pm set-permission-flags $pkg android.permission.$it user-fixed")
        }
        try {
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithTag("syncEnabled").performScrollTo().performClick()
            compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("wifiAllowlist").performScrollTo(); true }.getOrDefault(false) }
            compose.onNodeWithTag("wifiAllowlist").performClick()
            compose.onNodeWithTag("permissionContinue").performScrollTo().performClick()
            compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Open app settings").performScrollTo(); true }.getOrDefault(false) }
            compose.onNodeWithTag("wifiAllowlist").assertIsOff()
            compose.onNodeWithTag("permissionDismiss").performScrollTo().performClick()

            compose.onNodeWithTag("pairDevice").performScrollTo().performClick()
            compose.onNodeWithTag("pairScan").performClick()
            compose.onNodeWithTag("permissionContinue").performClick()
            compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Open app settings").assertExists(); true }.getOrDefault(false) }
            compose.onNodeWithTag("pairScan").assertExists() // still choosing a method, no camera
            Log.i(TAG, "permission denied: allowlist stays off, scan stays on the method list")
        } finally {
            runBlocking { SyncRuntime.of(context)!!.setEnabled(false) }
            listOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "CAMERA").forEach {
                shell("pm clear-permission-flags $pkg android.permission.$it user-fixed")
            }
        }
    }

    @Test
    fun theAllowlistSeesTheSsidWithFineLocation() = runBlocking<Unit> {
        // Revoking a granted permission kills the process, so the host revokes it after this run.
        assumeTrue("run alone with -e ssid 1; revoke location from the host afterwards", args.getString("ssid") == "1")
        val pkg = context.packageName
        listOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION").forEach { shell("pm grant $pkg android.permission.$it") }
        run {
            val rt = testRuntime()
            rt.setEnabled(true)
            rt.setWifiAllowlist(true)
            val locationOn = context.getSystemService(android.location.LocationManager::class.java).isLocationEnabled
            Log.i(TAG, "allowlist: location services on=$locationOn, SSID visible=${rt.state.value.currentSsid != null}")
            assertTrue(rt.state.value.currentSsid != null, "no SSID with fine location granted")
            assertEquals("Paused: not on an allowed Wi-Fi network", rt.state.value.statusLine)
            rt.setSsidAllowed(rt.state.value.currentSsid!!, true)
            assertTrue(rt.state.value.statusLine != "Paused: not on an allowed Wi-Fi network")
            rt.setEnabled(false)
        }
    }

    @Test
    fun serviceRunsWhileEnabledAndStopsOnScreenOff() = runBlocking<Unit> {
        val rt = testRuntime()
        rt.setEnabled(true)
        waitFor(what = "service started") { serviceRunning() }
        Log.i(TAG, "service: running after enable")

        shell("input keyevent KEYCODE_SLEEP") // no clip in the last 5 minutes: stops at once
        waitFor(what = "service stopped on screen off") { !serviceRunning() }
        Log.i(TAG, "service: stopped on screen off")

        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        Thread.sleep(1_000)
        rt.appVisible()
        waitFor(what = "service restarted in the foreground") { serviceRunning() }
        Log.i(TAG, "service: restarted when the app is visible again")

        // Screen on while the app is in the background: Android refuses the start, WorkManager catches up instead.
        shell("input keyevent KEYCODE_HOME")
        Thread.sleep(1_000)
        shell("input keyevent KEYCODE_SLEEP")
        waitFor(what = "service stopped on the second screen off") { !serviceRunning() }
        shell("input keyevent KEYCODE_WAKEUP")
        Thread.sleep(3_000)
        val refused = !serviceRunning()
        val queued = WorkManager.getInstance(context).getWorkInfosForUniqueWork(CatchUp.NOW).get().isNotEmpty()
        Log.i(TAG, "service: screen on in the background: started=${!refused}, expedited catch-up queued=$queued")
        assertTrue(refused == queued, "a refused start must queue a catch-up, a granted one needs none")
        shell("wm dismiss-keyguard")

        rt.setEnabled(false)
        waitFor(what = "service stopped when sync is off") { !serviceRunning() }
    }

    @Test
    fun enablingSchedulesPeriodicCatchUpThatRunsWhenConstraintsAreMet() = runBlocking<Unit> {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val work = WorkManager.getInstance(context)
        val rt = testRuntime()
        rt.setEnabled(true)

        val periodic = work.getWorkInfosForUniqueWork(CatchUp.PERIODIC).get().single()
        assertEquals(WorkInfo.State.ENQUEUED, periodic.state)
        assertEquals(androidx.work.NetworkType.CONNECTED, periodic.constraints.requiredNetworkType)
        assertEquals(15 * 60 * 1000L, periodic.periodicityInfo!!.repeatIntervalMillis)

        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        driver.setAllConstraintsMet(periodic.id)
        driver.setPeriodDelayMet(periodic.id)
        waitFor(what = "periodic catch-up ran") { work.getWorkInfoById(periodic.id).get()!!.runAttemptCount >= 1 }

        CatchUp.expedite(context)
        val now = work.getWorkInfosForUniqueWork(CatchUp.NOW).get().single()
        driver.setAllConstraintsMet(now.id)
        waitFor(what = "expedited catch-up finished") { work.getWorkInfoById(now.id).get()!!.state == WorkInfo.State.SUCCEEDED }

        rt.setEnabled(false)
        assertTrue(work.getWorkInfosForUniqueWork(CatchUp.PERIODIC).get().all { it.state == WorkInfo.State.CANCELLED })
        assertFalse(serviceRunning())
        Log.i(TAG, "work: periodic 15 min (CONNECTED) ran once, expedited ran, both cancelled on disable")
    }

    private companion object {
        const val TAG = "SyncOnDeviceTest"
    }
}
