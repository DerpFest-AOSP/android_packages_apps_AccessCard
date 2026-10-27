// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard

import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.derpfest.accesscard.data.CardStore
import org.derpfest.accesscard.data.DiagnosticStore.Event
import org.derpfest.accesscard.data.DiagnosticStore.Outcome
import org.derpfest.accesscard.model.*
import org.derpfest.accesscard.nfc.*

/** Shared by launcher, reader and tile. Only queried hardware state establishes ACTIVE. */
class WalletCoordinator(
    private val store: CardStore,
    val backend: AccessCardBackend,
    val scope: CoroutineScope,
    private val diagnostics: (Event, Outcome, AccessFailure?, HardwareState) -> Unit =
        { _, _, _, _ ->
        },
    private val changed: () -> Unit = {},
) {
    private val mutex = Mutex()
    private val operationGate = Mutex()
    private val activationMutex = Mutex()
    private val activationEpoch = AtomicLong(0)

    private data class Selection(val card: AccessCardProfile, val epoch: Long)

    private val pendingActivation = AtomicReference<Selection?>(null)
    private var readerToken: String? = null

    private data class PausedCard(val identity: CardIdentity, val generation: Long)

    private var pausedCard: PausedCard? = null
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
        if (
            hardware.mode != mutable.value.hardware.mode ||
                hardware.failure != mutable.value.hardware.failure
        )
            emit(Event.REFRESH, Outcome.SUCCEEDED, hardware.failure, hardware)
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

    private fun emit(
        event: Event,
        outcome: Outcome,
        error: AccessFailure? = null,
        hardware: HardwareState = mutable.value.hardware,
    ) {
        // Optional diagnostics must never change wallet or NFC operation results.
        runCatching { diagnostics(event, outcome, error, hardware) }
    }

    private fun perform(
        event: Event,
        mode: AccessMode,
        waitForTurn: Boolean = false,
        shouldRun: () -> Boolean = { true },
        action: () -> Unit,
    ): Job {
        if (!waitForTurn && !operationGate.tryLock())
            return scope.launch { emit(event, Outcome.FAILED, AccessFailure.BUSY) }
        return scope.launch {
            if (waitForTurn) operationGate.lock()
            try {
                mutex.withLock {
                    if (!shouldRun()) return@withLock
                    if (readerToken != null) {
                        mutable.update { it.copy(message = AccessFailure.BUSY) }
                        emit(event, Outcome.FAILED, AccessFailure.BUSY)
                        return@withLock
                    }
                    mutable.update { it.copy(operation = mode, message = null) }
                    emit(event, Outcome.STARTED)
                    var error: AccessFailure? = null
                    try {
                        action()
                    } catch (e: CancellationException) {
                        error = AccessFailure.OPERATION_FAILED
                        throw e
                    } catch (e: Exception) {
                        error = failure(e)
                        mutable.update { it.copy(message = error) }
                    } finally {
                        refreshLocked()
                        emit(event, if (error == null) Outcome.SUCCEEDED else Outcome.FAILED, error)
                    }
                }
            } finally {
                mutable.update {
                    it.copy(operation = if (readerToken != null) AccessMode.READING else null)
                }
                operationGate.unlock()
                changed()
            }
        }
    }

    fun activate(card: AccessCardProfile): Job {
        pendingActivation.set(Selection(card, activationEpoch.get()))
        return scope.launch {
            activationMutex.withLock {
                while (true) {
                    val selection = pendingActivation.getAndSet(null) ?: break
                    perform(
                            Event.APPLY,
                            AccessMode.APPLYING,
                            waitForTurn = true,
                            shouldRun = {
                                selection.epoch == activationEpoch.get() &&
                                    pendingActivation.get() == null
                            },
                        ) {
                            val actual = checkedHardware()
                            if (
                                actual.mode == AccessMode.ACTIVE &&
                                    actual.identity == selection.card.identity
                            )
                                return@perform
                            if (!backend.canApply(selection.card.identity))
                                throw AccessException(AccessFailure.INVALID_CARD)
                            if (!backend.apply(selection.card.identity))
                                throw AccessException(
                                    inspect().failure ?: AccessFailure.CONFIG_REJECTED
                                )
                        }
                        .join()
                }
            }
        }
    }

    private fun cancelSelections() {
        activationEpoch.incrementAndGet()
        pendingActivation.set(null)
    }

    fun stop(): Job {
        cancelSelections()
        return perform(Event.STOP, AccessMode.STOPPING) {
            val hardware = inspect()
            if (hardware.mode == AccessMode.NFC_OFF) throw AccessException(AccessFailure.NFC_OFF)
            if (hardware.mode == AccessMode.READING) throw AccessException(AccessFailure.BUSY)
            if (!backend.stop())
                throw AccessException(inspect().failure ?: AccessFailure.RECOVERY_REQUIRED)
        }
    }

    fun update(card: AccessCardProfile) =
        perform(Event.UPDATE, AccessMode.IDLE) { store.update(card) }

    fun setDefault(id: String?) = perform(Event.DEFAULT, AccessMode.IDLE) { store.setDefault(id) }

    fun move(id: String, delta: Int) =
        perform(Event.REORDER, AccessMode.IDLE) { store.move(id, delta) }

    fun delete(card: AccessCardProfile): Job {
        cancelSelections()
        return perform(Event.DELETE, AccessMode.STOPPING) {
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
    }

    suspend fun beginReading(): String =
        mutex.withLock {
            if (readerToken != null || operationGate.isLocked)
                throw AccessException(AccessFailure.BUSY)
            val hardware = inspect()
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
            cancelSelections()
            pausedCard = null
            if (hardware.mode == AccessMode.ACTIVE) {
                emit(Event.STOP, Outcome.STARTED)
                if (!backend.stop()) {
                    refreshLocked()
                    throw AccessException(inspect().failure ?: AccessFailure.RECOVERY_REQUIRED)
                }
                val stopped = inspect()
                if (stopped.mode != AccessMode.IDLE || stopped.needsRecovery) {
                    refreshLocked()
                    throw AccessException(stopped.failure ?: AccessFailure.RECOVERY_REQUIRED)
                }
                hardware.identity?.let { pausedCard = PausedCard(it, stopped.generation) }
                emit(Event.STOP, Outcome.SUCCEEDED, hardware = stopped)
            }
            UUID.randomUUID().toString().also { token ->
                readerToken = token
                emit(Event.READER_OPEN, Outcome.SUCCEEDED)
                mutable.update {
                    it.copy(hardware = inspect(), operation = AccessMode.READING, message = null)
                }
                changed()
            }
        }

    suspend fun endReading(token: String) =
        mutex.withLock {
            if (readerToken == token) {
                readerToken = null
                val previous = pausedCard
                pausedCard = null
                emit(Event.READER_CLOSE, Outcome.SUCCEEDED)
                try {
                    val actual = inspect()
                    // A reset, another owner or an explicit NFC shutdown supersedes this lease.
                    if (
                        previous != null &&
                            actual.mode == AccessMode.IDLE &&
                            !actual.needsRecovery &&
                            actual.generation == previous.generation
                    ) {
                        mutable.update { it.copy(operation = AccessMode.APPLYING) }
                        emit(Event.APPLY, Outcome.STARTED)
                        if (
                            !backend.canApply(previous.identity) ||
                                !backend.apply(previous.identity)
                        )
                            throw AccessException(
                                inspect().failure ?: AccessFailure.CONFIG_REJECTED
                            )
                        val restored = inspect()
                        if (
                            restored.mode != AccessMode.ACTIVE ||
                                restored.identity != previous.identity
                        )
                            throw AccessException(
                                restored.failure ?: AccessFailure.READBACK_MISMATCH
                            )
                        emit(Event.APPLY, Outcome.SUCCEEDED, hardware = restored)
                    }
                } catch (e: Exception) {
                    mutable.update { it.copy(message = failure(e)) }
                    emit(Event.APPLY, Outcome.FAILED, failure(e))
                } finally {
                    mutable.update { it.copy(operation = null) }
                    refreshLocked()
                    changed()
                }
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
                    emit(Event.SAVE, Outcome.STARTED)
                    store.add(id, name, identity, color, icon).also {
                        emit(Event.SAVE, Outcome.SUCCEEDED)
                    }
                } catch (e: Exception) {
                    emit(Event.SAVE, Outcome.FAILED, failure(e))
                    throw e
                } finally {
                    refreshLocked()
                    changed()
                }
            }
        }

    suspend fun snapshotForBackup(): WalletSnapshot = mutex.withLock { store.load() }

    /** Import local records under the same operation gate. Never apply an imported identity. */
    fun importCards(incoming: WalletSnapshot): Deferred<CardStore.ImportResult> =
        scope.async {
            if (!operationGate.tryLock()) throw AccessException(AccessFailure.BUSY)
            try {
                mutex.withLock {
                    if (readerToken != null) throw AccessException(AccessFailure.BUSY)
                    mutable.update { it.copy(operation = AccessMode.IDLE, message = null) }
                    emit(Event.IMPORT, Outcome.STARTED)
                    try {
                        store.merge(incoming).also { emit(Event.IMPORT, Outcome.SUCCEEDED) }
                    } catch (e: Exception) {
                        emit(Event.IMPORT, Outcome.FAILED, failure(e))
                        throw e
                    } finally {
                        refreshLocked()
                    }
                }
            } finally {
                mutable.update {
                    it.copy(operation = if (readerToken != null) AccessMode.READING else null)
                }
                operationGate.unlock()
                changed()
            }
        }

    fun clearMessage() {
        mutable.update { it.copy(message = null) }
    }
}
