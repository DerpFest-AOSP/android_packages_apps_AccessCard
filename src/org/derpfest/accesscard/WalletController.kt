// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import kotlinx.coroutines.*
import org.derpfest.accesscard.data.CardStore
import org.derpfest.accesscard.model.*
import org.derpfest.accesscard.nfc.*
import org.derpfest.accesscard.tools.*

/** Android bindings; all launchers use one coordinator per Android user process. */
class WalletController(context: Context, val backend: AccessCardBackend) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val preferences = AppPreferences(context)
    val diagnostics = Diagnostics(context, preferences)
    val coordinator =
        WalletCoordinator(CardStore.inDirectory(context.filesDir), backend, scope,
            diagnostics = diagnostics::record) {
            TileService.requestListeningState(
                context,
                ComponentName(context, AccessCardTileService::class.java),
            )
        }
    val state = coordinator.state

    init {
        scope.launch { runCatching { diagnostics.initialize() } }
        refresh()
    }

    fun refresh(notifyTile: Boolean = false) = coordinator.refresh(notifyTile)

    fun activate(card: AccessCardProfile) = coordinator.activate(card)

    fun stop() = coordinator.stop()

    fun update(card: AccessCardProfile) = coordinator.update(card)

    fun setDefault(id: String?) = coordinator.setDefault(id)

    fun move(id: String, delta: Int) = coordinator.move(id, delta)

    fun delete(card: AccessCardProfile) = coordinator.delete(card)

    fun clearMessage() = coordinator.clearMessage()
}
