// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.derpfest.accesscard.nfc.AccessMode

class AccessCardTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val controller = (application as AccessCardApplication).wallet
        controller.refresh()
        listening?.cancel()
        listening =
            scope.launch {
                launch {
                    while (isActive) {
                        controller.refresh().join()
                        delay(2000)
                    }
                }
                controller.state.collect { wallet ->
                    val tile = qsTile ?: return@collect
                    tile.label = "AccessCard"
                    tile.state =
                        if (wallet.hardware.mode == AccessMode.ACTIVE) Tile.STATE_ACTIVE
                        else Tile.STATE_INACTIVE
                    tile.subtitle =
                        when (wallet.mode) {
                            AccessMode.ACTIVE ->
                                wallet.activeCard?.name ?: getString(R.string.active_badge)
                            AccessMode.NFC_OFF -> getString(R.string.mode_nfc_off)
                            AccessMode.READING -> getString(R.string.mode_reading)
                            AccessMode.UNSUPPORTED -> getString(R.string.mode_unsupported)
                            AccessMode.APPLYING -> getString(R.string.mode_applying)
                            AccessMode.STOPPING -> getString(R.string.mode_stopping)
                            AccessMode.ERROR -> getString(R.string.mode_error)
                            else -> getString(R.string.quick_title)
                        }
                    tile.contentDescription = "${tile.label}, ${tile.subtitle}"
                    tile.updateTile()
                }
            }
    }

    override fun onStopListening() {
        listening?.cancel()
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val launch = Runnable {
            val intent =
                Intent(this, QuickAccessActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending =
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            startActivityAndCollapse(pending)
        }
        if (isLocked) unlockAndRun(launch) else launch.run()
    }
}
