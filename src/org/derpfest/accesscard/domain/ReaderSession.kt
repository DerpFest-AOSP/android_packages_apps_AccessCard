// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard

import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.derpfest.accesscard.data.CardStore
import org.derpfest.accesscard.model.CardIdentity
import org.derpfest.accesscard.nfc.*

enum class ReaderPhase {
    PREPARING,
    WAITING,
    READING,
    FOUND,
    SAVING,
    SAVED,
    ERROR,
}

data class ReaderState(
    val draftId: String = UUID.randomUUID().toString(),
    val phase: ReaderPhase = ReaderPhase.PREPARING,
    val identity: CardIdentity? = null,
    val name: String = "",
    val color: Int = 0,
    val icon: Int = 0,
    val error: AccessFailure? = null,
    val token: String? = null,
    val savedCardId: String? = null,
)

interface IdentityReader : Closeable {
    fun read(): CardIdentity
}

/** Retained across configuration changes. Never holds an Activity or performs work on main. */
class ReaderSession(
    private val wallet: WalletCoordinator,
    private val scope: CoroutineScope,
    initial: ReaderState = ReaderState(),
    private val defaultName: (Int) -> String,
) {
    private val mutable = MutableStateFlow(initial)
    val state = mutable.asStateFlow()
    private val lifecycle = Mutex()
    private val resumed = AtomicBoolean(false)
    private val epoch = AtomicLong(0)
    private var lease: String? = null
    private var worker: Job? = null
    private var connection: IdentityReader? = null
    private val savePending = AtomicBoolean(false)

    private suspend fun cleanup() {
        runCatching { connection?.close() }
        worker?.cancelAndJoin()
        worker = null
        connection = null
        lease?.let { wallet.endReading(it) }
        lease = null
    }

    fun resume(): Job {
        resumed.set(true)
        val generation = epoch.incrementAndGet()
        return scope.launch {
            lifecycle.withLock {
                if (!resumed.get() || epoch.get() != generation) return@withLock
                cleanup()
                if (!resumed.get() || epoch.get() != generation) return@withLock
                val old = mutable.value
                if (
                    old.identity != null ||
                        old.phase == ReaderPhase.SAVING ||
                        old.phase == ReaderPhase.SAVED
                )
                    return@withLock
                mutable.update {
                    it.copy(phase = ReaderPhase.PREPARING, error = null, token = null)
                }
                try {
                    val token = wallet.beginReading()
                    if (!resumed.get() || epoch.get() != generation) {
                        wallet.endReading(token)
                    } else {
                        lease = token
                        mutable.update { it.copy(phase = ReaderPhase.WAITING, token = token) }
                    }
                } catch (e: AccessException) {
                    mutable.update { it.copy(phase = ReaderPhase.ERROR, error = e.failure) }
                }
            }
        }
    }

    fun pause(): Job {
        resumed.set(false)
        val generation = epoch.incrementAndGet()
        return scope.launch {
            lifecycle.withLock {
                if (resumed.get() || epoch.get() != generation) return@withLock
                cleanup()
                mutable.update {
                    it.copy(
                        token = null,
                        phase = if (it.identity == null) ReaderPhase.PREPARING else it.phase,
                    )
                }
            }
        }
    }

    fun readerFailed(token: String, failure: AccessFailure) =
        scope.launch {
            lifecycle.withLock {
                if (lease != token) return@withLock
                cleanup()
                mutable.update { it.copy(phase = ReaderPhase.ERROR, token = null, error = failure) }
            }
        }

    fun onTag(token: String, reader: IdentityReader) =
        scope.launch {
            lifecycle.withLock {
                if (
                    lease != token || !resumed.get() || mutable.value.phase != ReaderPhase.WAITING
                ) {
                    runCatching { reader.close() }
                    return@withLock
                }
                val generation = epoch.get()
                connection = reader
                mutable.update { it.copy(phase = ReaderPhase.READING) }
                worker =
                    scope.launch {
                        try {
                            val identity = runInterruptible { reader.read() }
                            if (resumed.get() && epoch.get() == generation) {
                                mutable.update {
                                    it.copy(
                                        identity = identity,
                                        phase = ReaderPhase.FOUND,
                                        name =
                                            defaultName(wallet.state.value.wallet.cards.size + 1),
                                        error = null,
                                        token = null,
                                    )
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (resumed.get() && epoch.get() == generation) {
                                mutable.update {
                                    it.copy(
                                        phase = ReaderPhase.ERROR,
                                        token = null,
                                        error =
                                            (e as? AccessException)?.failure
                                                ?: AccessFailure.READ_FAILED,
                                    )
                                }
                            }
                        } finally {
                            runCatching { reader.close() }
                            // Keep emulation paused while the new card is named and saved.
                            // Activity pause disables reader mode before releasing this lease.
                        }
                    }
            }
        }

    fun rename(value: String) {
        if (!savePending.get()) mutable.update { it.copy(name = value) }
    }

    fun color(value: Int) {
        if (!savePending.get()) mutable.update { it.copy(color = value) }
    }

    fun icon(value: Int) {
        if (!savePending.get()) mutable.update { it.copy(icon = value) }
    }

    fun save(): Job {
        val draft = mutable.value
        if (
            draft.identity == null ||
                draft.name.isBlank() ||
                draft.phase == ReaderPhase.SAVED ||
                !savePending.compareAndSet(false, true)
        )
            return scope.launch {}
        mutable.update { it.copy(phase = ReaderPhase.SAVING, error = null) }
        // Ownership is the application, including the waiter. Leaving the reader cannot lose
        // the result or cancel a write halfway through its atomic commit.
        return wallet.scope.launch {
            try {
                val added =
                    wallet
                        .add(draft.draftId, draft.name, draft.identity, draft.color, draft.icon)
                        .await()
                mutable.update { it.copy(phase = ReaderPhase.SAVED, savedCardId = added.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update {
                    it.copy(
                        phase = ReaderPhase.FOUND,
                        error =
                            when (e) {
                                is CardStore.DuplicateCardException -> AccessFailure.DUPLICATE
                                is AccessException -> e.failure
                                else -> AccessFailure.STORAGE
                            },
                    )
                }
            } finally {
                savePending.set(false)
            }
        }
    }
}
