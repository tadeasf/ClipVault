package cz.tadeasf.clipvault

import android.app.Activity
import android.app.StatusBarManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import cz.tadeasf.clipvault.capture.CaptureTileService
import cz.tadeasf.clipvault.capture.ClipAccessibilityService
import cz.tadeasf.clipvault.ui.AccessibilityControl
import cz.tadeasf.clipvault.ui.AppEntry
import cz.tadeasf.clipvault.ui.Platform

/** The app's [Platform] for the shared UI. (Not `AndroidPlatform`: :shared already has that name.) */
class AndroidAppPlatform(private val activity: Activity, flavor: String) : Platform {

    override val appInfo: String = run {
        val version = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        "ClipVault $version ($flavor flavor)"
    }

    override fun copyToClipboard(text: String, sensitive: Boolean) {
        val clip = ClipData.newPlainText("ClipVault", text)
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Hides the value in the system clipboard preview and from keyboards' clipboard history.
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    override fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        activity.startActivity(Intent.createChooser(send, null))
    }

    override val accessibility = object : AccessibilityControl {
        private val component = ComponentName(activity, ClipAccessibilityService::class.java)

        override fun isEnabled(): Boolean {
            val enabled = Settings.Secure.getString(activity.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return enabled?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true
        }

        override fun openSystemSettings() = activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    override val requestAddTile: (() -> Unit)? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ::addTile else null

    private fun addTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        activity.getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(activity, CaptureTileService::class.java),
            "ClipVault",
            Icon.createWithResource(activity, R.drawable.ic_tile),
            activity.mainExecutor,
        ) { }
    }

    override fun installedApps(): List<AppEntry> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = activity.packageManager
        return pm.queryIntentActivities(launcher, 0)
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.packageName != activity.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
