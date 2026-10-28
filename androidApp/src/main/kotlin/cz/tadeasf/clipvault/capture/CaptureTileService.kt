package cz.tadeasf.clipvault.capture

import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.text.format.DateUtils
import cz.tadeasf.clipvault.graph

/** Quick Settings tile: tap -> CaptureActivity reads the clipboard. Subtitle shows the last capture. */
class CaptureTileService : TileService() {

    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val last = graph.settings.settings.value.lastCaptureAt
            tile.subtitle = last?.let { DateUtils.getRelativeTimeSpanString(it).toString() } ?: "Tap to capture"
        }
        tile.updateTile()
    }

    override fun onClick() {
        val intent = CaptureActivity.intent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
