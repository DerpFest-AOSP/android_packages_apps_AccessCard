// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard

import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.derpfest.accesscard.data.CardStore
import org.derpfest.accesscard.model.*
import org.derpfest.accesscard.nfc.*

/** Shared by launcher, reader and tile. Only queried hardware state establishes ACTIVE. */
class WalletCoordinator(
    private val store: CardStore,
    val backend: AccessCardBackend,
    val scope: CoroutineScope,
    private val changed: () -> Unit = {},
) {
    private val mutex = Mutex()
    private val operationPending = AtomicBoolean(false)
    private var readerToken: String? = null
    private val mutable = MutableStateFlow(WalletState())
    val state: StateFlow<WalletState> = mutable.asStateFlow()

    fun refresh(notifyTile: Boolean = false) =
        scope.launch {
            mutex.withLock { refreshLocked() }
            if (notifyTile) changed()
        }

    private fun inspect() =
        try {
            backend.inspect()
        } catch (e: Exception) {
            HardwareState(AccessMode.ERROR, failure = AccessFailure.SERVICE_UNAVAILABLE)
        }

    private fun refreshLocked() {
        val wallet =
            try {
                store.load()
            } catch (e: IOException) {
                null
            }
        val hardware = inspect()
        mutable.update {
            it.copy(
                wallet = wallet ?: it.wallet,
                hardware = hardware,
                loading = false,
                storageFailed = wallet == null,
                message = if (wallet == null) AccessFailure.STORAGE else it.message,
            )
        }
    }

    private fun failure(e: Exception): AccessFailure =
        when (e) {
            is AccessException -> e.failure
            is CardStore.DuplicateCardException -> AccessFailure.DUPLICATE
            is IOException -> AccessFailure.STORAGE
            else -> AccessFailure.OPERATION_FAILED
        }

    private fun checkedHardware(): HardwareState {
        val hardware = inspect()
        when (hardware.mode) {
            AccessMode.ERROR ->
                throw AccessException(hardware.failure ?: AccessFailure.SERVICE_UNAVAILABLE)
            AccessMode.NFC_OFF -> throw AccessException(AccessFailure.NFC_OFF)
            AccessMode.UNSUPPORTED -> throw AccessException(AccessFailure.UNSUPPORTED)
            AccessMode.READING,
            AccessMode.APPLYING,
            AccessMode.STOPPING -> throw AccessException(AccessFailure.BUSY)
            else -> return hardware
        }
    }

    private fun perform(mode: AccessMode, action: () -> Unit): Job {
        // Reserve before scheduling so rapid taps cannot queue conflicting hardware writes.
        if (!operationPending.compareAndSet(false, true)) return scope.launch {}
        return scope.launch {
            try {
                mutex.withLock {
                    if (readerToken != null) {
                        mutable.update { it.copy(message = AccessFailure.BUSY) }
                        return@withLock
                    }
                    mutable.update { it.copy(operation = mode, message = null) }
                    try {
                        action()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        mutable.update { it.copy(message = failure(e)) }
                    } finally {
                        refreshLocked()
                    }
                }
            } finally {
                mutable.update {
                    it.copy(operation = if (readerToken != null) AccessMode.READING else null)
                }
                operationPending.set(false)
                changed()
            }
        }
    }

    fun activate(card: AccessCardProfile) =
        perform(AccessMode.APPLYING) {
            checkedHardware()
            if (!backend.canApply(card.identity)) throw AccessException(AccessFailure.INVALID_CARD)
            if (!backend.apply(card.identity))
                throw AccessException(inspect().failure ?: AccessFailure.CONFIG_REJECTED)
        }

    fun stop() =
        perform(AccessMode.STOPPING) {
            val hardware = inspect()
            if (hardware.mode == AccessMode.NFC_OFF) throw AccessException(AccessFailure.NFC_OFF)
            if (hardware.mode == AccessMode.READING) throw AccessException(AccessFailure.BUSY)
            if (!backend.stop())
                throw AccessException(inspect().failure ?: AccessFailure.RECOVERY_REQUIRED)
        }

    fun update(card: AccessCardProfile) = perform(AccessMode.IDLE) { store.update(card) }

    fun setDefault(id: String?) = perform(AccessMode.IDLE) { store.setDefault(id) }

    fun move(id: String, delta: Int) = perform(AccessMode.IDLE) { store.move(id, delta) }

    fun delete(card: AccessCardProfile) =
        perform(AccessMode.STOPPING) {
            val hardware = inspect()
            if (hardware.mode == AccessMode.ERROR || hardware.needsRecovery) {
                throw AccessException(hardware.failure ?: AccessFailure.SERVICE_UNAVAILABLE)
            }
            if (hardware.mode == AccessMode.READING) throw AccessException(AccessFailure.BUSY)
            if (hardware.identity == card.identity && !backend.stop()) {
                throw AccessException(inspect().failure ?: AccessFailure.RECOVERY_REQUIRED)
            }
            store.remove(card.id)
        }

    suspend fun beginReading(): String =
        mutex.withLock {
            if (readerToken != null || operationPending.get())
                throw AccessException(AccessFailure.BUSY)
            val hardware = inspect()
            if (hardware.mode == AccessMode.ACTIVE) throw AccessException(AccessFailure.NEEDS_STOP)
            // Older modules can still support safe read-only card collection.
            if (!backend.capabilities.canReadIdentity)
                throw AccessException(AccessFailure.UNSUPPORTED)
            when (hardware.mode) {
                AccessMode.NFC_OFF -> throw AccessException(AccessFailure.NFC_OFF)
                AccessMode.ERROR ->
                    throw AccessException(hardware.failure ?: AccessFailure.SERVICE_UNAVAILABLE)
                AccessMode.READING,
                AccessMode.APPLYING,
                AccessMode.STOPPING -> throw AccessException(AccessFailure.BUSY)
                else -> {}
            }
            UUID.randomUUID().toString().also { token ->
                readerToken = token
                mutable.update {
                    it.copy(hardware = hardware, operation = AccessMode.READING, message = null)
                }
                changed()
            }
        }

    suspend fun endReading(token: String) =
        mutex.withLock {
            if (readerToken == token) {
                readerToken = null
                mutable.update { it.copy(operation = null) }
                refreshLocked()
                changed()
            }
        }

    /**
     * Application-owned transaction: cancelling an Activity's waiter never cancels a durable save.
     */
    fun add(
        id: String,
        name: String,
        identity: CardIdentity,
        color: Int,
        icon: Int,
    ): Deferred<AccessCardProfile> =
        scope.async {
            mutex.withLock {
                try {
                    store.add(id, name, identity, color, icon)
                } finally {
                    refreshLocked()
                    changed()
                }
            }
        }

    fun clearMessage() {
        mutable.update { it.copy(message = null) }
    }
}
