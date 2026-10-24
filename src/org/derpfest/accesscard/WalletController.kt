// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import android.util.Log
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.derpfest.accesscard.data.CardStore
import org.derpfest.accesscard.data.WalletActions
import org.derpfest.accesscard.model.*
import org.derpfest.accesscard.nfc.*

data class WalletState(
    val wallet: WalletSnapshot = WalletSnapshot.empty(),
    val hardware: HardwareState = HardwareState(AccessMode.IDLE),
    val loading: Boolean = true,
    val operation: AccessMode? = null,
    val storageFailed: Boolean = false,
    val message: Int? = null,
) {
    val busy
        get() = operation != null

    val mode
        get() = operation?.takeUnless { it == AccessMode.IDLE } ?: hardware.mode

    val activeCard
        get() = wallet.cards.firstOrNull { it.identity == hardware.identity }
}

/** Process-shared coordinator for the launcher, reader and QS. No persistent hardware truth. */
class WalletController(private val context: Context, val backend: AccessCardBackend) {
    private val store = CardStore.inDirectory(context.filesDir)
    private val actions =
        WalletActions(
            store,
            object : WalletActions.Gateway {
                override fun activeIdentity(): CardIdentity? {
                    val status = backend.inspect()
                    if (status.mode == AccessMode.ERROR) throw IOException("Unknown NFC state")
                    return status.identity
                }

                override fun stop() = backend.stop()
            },
        )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val operationPending = AtomicBoolean(false)
    private val mutable = MutableStateFlow(WalletState())
    val state: StateFlow<WalletState> = mutable.asStateFlow()

    init {
        refresh()
    }

    fun refresh(notifyTile: Boolean = false) =
        scope.launch {
            mutex.withLock { refreshLocked() }
            if (notifyTile) requestTileRefresh()
        }

    private fun refreshLocked() {
        val wallet =
            try {
                store.load()
            } catch (e: IOException) {
                Log.w("AccessCard", "Unable to read wallet", e)
                mutable.update {
                    it.copy(loading = false, storageFailed = true, message = R.string.error_storage)
                }
                null
            }
        val hardware =
            try {
                backend.inspect()
            } catch (e: Exception) {
                Log.w("AccessCard", "Unable to query NFC state", e)
                HardwareState(AccessMode.ERROR)
            }
        mutable.update {
            it.copy(
                wallet = wallet ?: it.wallet,
                hardware = hardware,
                loading = false,
                storageFailed = wallet == null,
            )
        }
    }

    private fun requestTileRefresh() {
        TileService.requestListeningState(
            context,
            ComponentName(context, AccessCardTileService::class.java),
        )
    }

    private fun perform(mode: AccessMode, action: () -> Unit) =
        scope.launch {
            if (!operationPending.compareAndSet(false, true)) return@launch
            mutable.update { it.copy(operation = mode, message = null) }
            try {
                // Wait for a concurrent read instead of silently dropping the user's action.
                mutex.withLock {
                    try {
                        action()
                    } catch (e: WalletActions.StopFailedException) {
                        mutable.update { it.copy(message = R.string.error_restore) }
                    } catch (e: CardStore.DuplicateCardException) {
                        mutable.update { it.copy(message = R.string.error_duplicate) }
                    } catch (e: Exception) {
                        Log.w("AccessCard", "Wallet operation failed", e)
                        mutable.update {
                            it.copy(
                                message =
                                    if (e is IOException) R.string.error_storage
                                    else R.string.error_operation
                            )
                        }
                    } finally {
                        refreshLocked()
                    }
                }
            } finally {
                mutable.update { it.copy(operation = null) }
                operationPending.set(false)
                requestTileRefresh()
            }
        }

    fun activate(card: AccessCardProfile) =
        perform(AccessMode.APPLYING) {
            if (!backend.canApply(card.identity)) {
                mutable.update { it.copy(message = R.string.error_card_mode) }
            } else if (!backend.apply(card.identity)) {
                mutable.update { it.copy(message = R.string.error_apply) }
            }
        }

    fun stop() =
        perform(AccessMode.STOPPING) {
            if (!backend.stop()) mutable.update { it.copy(message = R.string.error_restore) }
        }

    fun update(card: AccessCardProfile) = perform(AccessMode.IDLE) { store.update(card) }

    fun setDefault(id: String?) = perform(AccessMode.IDLE) { store.setDefault(id) }

    fun move(id: String, delta: Int) = perform(AccessMode.IDLE) { store.move(id, delta) }

    fun delete(card: AccessCardProfile) = perform(AccessMode.STOPPING) { actions.remove(card.id) }

    /** The reader awaits durable storage before showing success or returning a result. */
    suspend fun add(
        name: String,
        identity: CardIdentity,
        color: Int,
        icon: Int,
    ): AccessCardProfile =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    store.add(name, identity, color, icon)
                } finally {
                    refreshLocked()
                    requestTileRefresh()
                }
            }
        }

    fun clearMessage() {
        mutable.update { it.copy(message = null) }
    }
}
