// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.derpfest.accesscard.data.CardStore
import org.derpfest.accesscard.model.*
import org.derpfest.accesscard.nfc.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WalletCoordinatorTest {
    private class Storage : CardStore.Storage {
        var bytes: ByteArray? = null
        var failWrite = false

        override fun read() = bytes?.clone()

        override fun write(value: ByteArray) {
            if (failWrite) throw IOException("Test write failure")
            bytes = value.clone()
        }
    }

    private class Backend : AccessCardBackend {
        override var capabilities =
            BackendCapabilities(
                true,
                true,
                true,
                Qualification.EXPERIMENTAL,
                setOf(4, 7, 10),
                emptySet(),
            )
        var hardware = HardwareState(AccessMode.IDLE)
        var applies = 0
        var stops = 0
        var reject = false
        var serviceLost = false

        override fun inspect(): HardwareState {
            if (serviceLost) throw IllegalStateException("Test service death")
            return hardware
        }

        override fun canApply(identity: CardIdentity) =
            capabilities.canApplyIdentity && identity.isLegacyTypeA

        override fun apply(identity: CardIdentity): Boolean {
            applies++
            if (reject) {
                hardware = hardware.copy(failure = AccessFailure.TIMEOUT)
                return false
            }
            hardware = HardwareState(AccessMode.ACTIVE, identity)
            return true
        }

        override fun stop(): Boolean {
            stops++
            if (reject) {
                hardware = hardware.copy(failure = AccessFailure.RECOVERY_REQUIRED)
                return false
            }
            hardware = HardwareState(AccessMode.IDLE)
            return true
        }
    }

    private fun identity(n: Int = 1) =
        CardIdentity(byteArrayOf(n.toByte(), 2, 3, 4), byteArrayOf(0, 4), 8)

    private fun profile(n: Int = 1) =
        AccessCardProfile(UUID.randomUUID().toString(), "Card $n", identity(n), 0, 0, 0)

    private fun runCase(
        block: suspend TestScope.(Storage, CardStore, Backend, WalletCoordinator) -> Unit
    ) = runTest {
        val storage = Storage()
        val store = CardStore(storage)
        val backend = Backend()
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val wallet = WalletCoordinator(store, backend, owner)
        try {
            block(storage, store, backend, wallet)
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun rapidTapsCannotQueueSeveralActivations() = runCase { _, _, backend, wallet ->
        val card = profile()
        wallet.activate(card)
        wallet.activate(profile(2))
        advanceUntilIdle()
        assertEquals(1, backend.applies)
        assertEquals(card.identity, wallet.state.value.hardware.identity)
    }

    @Test
    fun changingDefaultNeverWritesController() = runCase { _, store, backend, wallet ->
        val a = store.add("A", identity(), 0, 0)
        val b = store.add("B", identity(2), 0, 0)
        wallet.activate(a).join()
        wallet.setDefault(b.id).join()
        assertEquals(b.id, wallet.state.value.wallet.defaultId)
        assertEquals(a.identity, wallet.state.value.hardware.identity)
        assertEquals(1, backend.applies)
    }

    @Test
    fun failureDoesNotMarkBrowsedCardActive() = runCase { _, _, backend, wallet ->
        val a = profile()
        wallet.activate(a).join()
        backend.reject = true
        wallet.activate(profile(2)).join()
        assertEquals(a.identity, wallet.state.value.hardware.identity)
        assertEquals(AccessFailure.TIMEOUT, wallet.state.value.message)
    }

    @Test
    fun activeDeleteRequiresSuccessfulRestore() = runCase { _, store, backend, wallet ->
        val a = store.add("A", identity(), 0, 0)
        wallet.activate(a).join()
        backend.reject = true
        wallet.delete(a).join()
        assertEquals(1, store.load().cards.size)
        assertEquals(AccessFailure.RECOVERY_REQUIRED, wallet.state.value.message)
        backend.reject = false
        wallet.delete(a).join()
        assertTrue(store.load().cards.isEmpty())
        assertEquals(AccessMode.IDLE, wallet.state.value.hardware.mode)
    }

    @Test
    fun lostServiceIsNotReportedAsStorageFailure() = runCase { _, store, backend, wallet ->
        val a = store.add("A", identity(), 0, 0)
        backend.serviceLost = true
        wallet.delete(a).join()
        assertEquals(AccessFailure.SERVICE_UNAVAILABLE, wallet.state.value.message)
        assertFalse(wallet.state.value.storageFailed)
        assertEquals(1, store.load().cards.size)
    }

    @Test
    fun readerLeaseBlocksLauncherAndTileActivation() = runCase { _, _, backend, wallet ->
        val token = wallet.beginReading()
        assertEquals(AccessMode.READING, wallet.state.value.mode)
        wallet.activate(profile()).join()
        assertEquals(0, backend.applies)
        assertEquals(AccessFailure.BUSY, wallet.state.value.message)
        wallet.endReading(token)
        wallet.activate(profile()).join()
        assertEquals(1, backend.applies)
    }

    @Test
    fun staleReaderCannotReleaseNewReaderLease() = runCase { _, _, _, wallet ->
        val old = wallet.beginReading()
        wallet.endReading(old)
        val current = wallet.beginReading()
        wallet.endReading(old)
        assertEquals(AccessMode.READING, wallet.state.value.mode)
        wallet.endReading(current)
        assertFalse(wallet.state.value.busy)
    }

    @Test
    fun activeCardNeedsExplicitStopBeforeReading() = runCase { _, _, backend, wallet ->
        wallet.activate(profile()).join()
        try {
            wallet.beginReading()
            fail("Active card must be stopped")
        } catch (e: AccessException) {
            assertEquals(AccessFailure.NEEDS_STOP, e.failure)
        }
        assertEquals(0, backend.stops)
    }

    @Test
    fun unknownDeviceReadsButCannotEmulate() = runCase { _, _, backend, wallet ->
        backend.capabilities =
            backend.capabilities.copy(
                canApplyIdentity = false,
                qualification = Qualification.UNKNOWN,
            )
        backend.hardware = HardwareState(AccessMode.UNSUPPORTED)
        val token = wallet.beginReading()
        wallet.endReading(token)
        wallet.activate(profile()).join()
        assertEquals(0, backend.applies)
        assertEquals(AccessFailure.UNSUPPORTED, wallet.state.value.message)
    }

    @Test
    fun recoveryCanBeRetriedFromErrorState() = runCase { _, _, backend, wallet ->
        backend.hardware = HardwareState(AccessMode.ERROR, needsRecovery = true)
        wallet.stop().join()
        assertEquals(1, backend.stops)
        assertEquals(AccessMode.IDLE, wallet.state.value.mode)
    }

    @Test
    fun readFailureAndRetryReleaseLease() = runCase { _, _, _, wallet ->
        val session = ReaderSession(wallet, this, defaultName = { "Card $it" })
        session.resume().join()
        val token = session.state.value.token!!
        session
            .onTag(
                token,
                object : IdentityReader {
                    override fun read(): CardIdentity =
                        throw AccessException(AccessFailure.TAG_LOST)

                    override fun close() {}
                },
            )
            .join()
        advanceUntilIdle()
        assertEquals(AccessFailure.TAG_LOST, session.state.value.error)
        assertFalse(wallet.state.value.busy)
        session.resume().join()
        assertEquals(ReaderPhase.WAITING, session.state.value.phase)
        session.pause().join()
        assertFalse(wallet.state.value.busy)
    }

    @Test
    fun staleTagAfterPauseDoesNotPublishIdentity() = runCase { _, _, _, wallet ->
        val session = ReaderSession(wallet, this, defaultName = { "Card $it" })
        session.resume().join()
        val old = session.state.value.token!!
        session.pause().join()
        session.resume().join()
        var closed = false
        session
            .onTag(
                old,
                object : IdentityReader {
                    override fun read(): CardIdentity {
                        fail("Stale reader must not connect")
                        return identity()
                    }

                    override fun close() {
                        closed = true
                    }
                },
            )
            .join()
        advanceUntilIdle()
        assertTrue(closed)
        assertNull(session.state.value.identity)
        session.pause().join()
    }

    @Test
    fun durableSaveSurvivesReaderScopeCancellation() = runCase { _, store, _, wallet ->
        val readerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val initial = ReaderState(identity = identity(), name = "Home", phase = ReaderPhase.FOUND)
        val session = ReaderSession(wallet, readerScope, initial) { "Card $it" }
        val save = session.save()
        readerScope.cancel()
        save.join()
        assertEquals(ReaderPhase.SAVED, session.state.value.phase)
        assertEquals(initial.draftId, session.state.value.savedCardId)
        assertEquals(1, store.load().cards.size)
    }

    @Test
    fun processDeathRetryReturnsSameDurableCard() = runCase { _, store, _, wallet ->
        val initial = ReaderState(identity = identity(), name = "Home", phase = ReaderPhase.FOUND)
        ReaderSession(wallet, this, initial) { "Card $it" }.save().join()
        val recreated = ReaderSession(wallet, this, initial) { "Card $it" }
        recreated.save().join()
        assertEquals(ReaderPhase.SAVED, recreated.state.value.phase)
        assertEquals(initial.draftId, recreated.state.value.savedCardId)
        assertEquals(1, store.load().cards.size)
    }

    @Test
    fun failedSaveDoesNotReportSuccessAndCanRetry() = runCase { storage, store, _, wallet ->
        val session =
            ReaderSession(
                wallet,
                this,
                ReaderState(identity = identity(), name = "Home", phase = ReaderPhase.FOUND),
            ) {
                "Card $it"
            }
        storage.failWrite = true
        session.save().join()
        assertEquals(ReaderPhase.FOUND, session.state.value.phase)
        assertEquals(AccessFailure.STORAGE, session.state.value.error)
        assertNull(session.state.value.savedCardId)
        storage.failWrite = false
        session.save().join()
        assertEquals(ReaderPhase.SAVED, session.state.value.phase)
        assertEquals(1, store.load().cards.size)
    }

    @Test
    fun duplicateSaveDoesNotReportSuccess() = runCase { _, store, _, wallet ->
        store.add("Existing", identity(), 0, 0)
        val session =
            ReaderSession(
                wallet,
                this,
                ReaderState(identity = identity(), name = "Home", phase = ReaderPhase.FOUND),
            ) {
                "Card $it"
            }
        session.save().join()
        assertEquals(AccessFailure.DUPLICATE, session.state.value.error)
        assertNull(session.state.value.savedCardId)
    }

    @Test
    fun rapidPauseAndResumeCannotCleanUpTheNewLease() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val wallet = WalletCoordinator(CardStore(Storage()), Backend(), owner)
        val session = ReaderSession(wallet, readerScope, defaultName = { "Card $it" })
        try {
            val jobs = mutableListOf<Job>()
            repeat(25) {
                jobs += session.resume()
                jobs += session.pause()
            }
            jobs += session.resume()
            withTimeout(10000) { jobs.joinAll() }
            assertEquals(ReaderPhase.WAITING, session.state.value.phase)
            assertNotNull(session.state.value.token)
            assertEquals(AccessMode.READING, wallet.state.value.mode)
        } finally {
            session.pause().join()
            readerScope.cancel()
            owner.cancel()
        }
    }

    @Test
    fun pauseClosesInFlightConnectionAndRejectsItsLateResult() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.atomic.AtomicInteger(0)
        val wallet = WalletCoordinator(CardStore(Storage()), Backend(), owner)
        val session = ReaderSession(wallet, readerScope, defaultName = { "Card $it" })
        try {
            session.resume().join()
            val old = session.state.value.token!!
            session
                .onTag(
                    old,
                    object : IdentityReader {
                        override fun read(): CardIdentity {
                            entered.countDown()
                            released.await()
                            return identity()
                        }

                        override fun close() {
                            closed.incrementAndGet()
                            released.countDown()
                        }
                    },
                )
                .join()
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            withTimeout(5000) { session.pause().join() }
            assertTrue(closed.get() > 0)
            assertNull(session.state.value.identity)
            assertFalse(wallet.state.value.busy)
            session.resume().join()
            assertEquals(ReaderPhase.WAITING, session.state.value.phase)
            assertNotEquals(old, session.state.value.token)
        } finally {
            released.countDown()
            session.pause().join()
            readerScope.cancel()
            owner.cancel()
        }
    }

    @Test
    fun readingAndPausingPreserveFoundCardWithoutActivatingIt() = runCase { _, _, backend, wallet ->
        val session = ReaderSession(wallet, this, defaultName = { "Card $it" })
        session.resume().join()
        session
            .onTag(
                session.state.value.token!!,
                object : IdentityReader {
                    override fun read() = identity()

                    override fun close() {}
                },
            )
            .join()
        advanceUntilIdle()
        assertEquals(ReaderPhase.FOUND, session.state.value.phase)
        session.rename("Custom")
        session.pause().join()
        session.resume().join()
        assertEquals("Custom", session.state.value.name)
        assertEquals(identity(), session.state.value.identity)
        assertEquals(0, backend.applies)
        assertFalse(wallet.state.value.busy)
    }
    @Test
    fun diagnosticFailureCannotBreakActivationOrStopping() = runTest {
        val backend = Backend()
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val wallet = WalletCoordinator(CardStore(Storage()), backend, owner,
            diagnostics = { _, _, _, _ -> throw IOException("Full diagnostic cache") })
        try {
            wallet.activate(profile()).join()
            assertEquals(AccessMode.ACTIVE, wallet.state.value.hardware.mode)
            assertNull(wallet.state.value.message)
            wallet.stop().join()
            assertEquals(AccessMode.IDLE, wallet.state.value.hardware.mode)
            assertNull(wallet.state.value.message)
        } finally { owner.cancel() }
    }

}
