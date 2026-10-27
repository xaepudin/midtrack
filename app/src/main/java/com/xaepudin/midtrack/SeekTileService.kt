package com.xaepudin.midtrack

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: flip Midtrack on or off without opening the app or
 * digging the notification out of the shade. The subtitle shows the rule in
 * force for whatever is playing, so the tile doubles as a status readout.
 */
class SeekTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        // The tile can have been changed anywhere (app, notification), so
        // always re-read the state when the shade opens.
        refresh()
    }

    override fun onClick() {
        super.onClick()
        Prefs.setEnabled(this, !Prefs.isEnabled(this))
        refresh()
        // Keep the notification and an open MainActivity in step with the tile.
        Notifications.repost(this)
        sendBroadcast(Intent(MidtrackActionReceiver.ACTION_PREFS_CHANGED).setPackage(packageName))
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val enabled = Prefs.isEnabled(this)
        val activePkg = Prefs.activePkg(this)
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= 30) {
            tile.stateDescription = getString(if (enabled) R.string.state_on else R.string.state_off)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            // Subtitle arrived in Android 10; on older releases the tile is
            // just on/off, which is still the whole point.
            tile.subtitle = when {
                !enabled -> getString(R.string.state_off)
                activePkg != null -> SeekLogic.modeLabel(Prefs.config(this, activePkg))
                else -> SeekLogic.modeLabel(Prefs.config(this))
            }
        }
        tile.updateTile()
    }
}
