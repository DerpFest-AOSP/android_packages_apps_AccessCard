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
    private val foregroundOwner = Any()

    private class ResumeStore : WalletResumeStore {
        var saved = WalletResumeState()
        var failRead = false
        var failWrite = false

        override fun load(): WalletResumeState {
            if (failRead) throw IOException("Test resume read failure")
            return saved
        }

        override fun save(state: WalletResumeState) {
            if (failWrite) throw IOException("Test resume write failure")
            saved = state
        }
    }

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
        var onApply: (CardIdentity) -> Unit = {}

        override fun inspect(): HardwareState {
            if (serviceLost) throw IllegalStateException("Test service death")
            return hardware
        }

        override fun canApply(identity: CardIdentity) =
            capabilities.canApplyIdentity && identity.isLegacyTypeA

        override fun apply(identity: CardIdentity): Boolean {
            applies++
            onApply(identity)
            if (reject) {
                hardware = hardware.copy(failure = AccessFailure.TIMEOUT)
                return false
            }
            hardware =
                HardwareState(AccessMode.ACTIVE, identity, generation = hardware.generation + 1)
            return true
        }

        override fun stop(): Boolean {
            stops++
            if (reject) {
                hardware = hardware.copy(failure = AccessFailure.RECOVERY_REQUIRED)
                return false
            }
            hardware = HardwareState(AccessMode.IDLE, generation = hardware.generation + 1)
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
        val wallet = WalletCoordinator(store, backend, owner, ResumeStore())
        try {
            wallet.enterForeground(foregroundOwner).join()
            block(storage, store, backend, wallet)
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun coldStartRestoresLastSuccessfulCardOnlyAfterEnteringForeground() = runTest {
        val store = CardStore(Storage())
        val default = store.add("Default", identity(), 0, 0)
        val last = store.add("Last used", identity(2), 0, 0)
        store.setDefault(default.id)
        val resume = ResumeStore()
        val previous = WalletCoordinator(store, Backend(), backgroundScope, resume)
        previous.enterForeground(foregroundOwner).join()
        previous.activate(last).join()
        previous.leaveForeground(foregroundOwner).join()

        val backend = Backend()
        val restarted = WalletCoordinator(store, backend, backgroundScope, resume)
        restarted.refresh().join()
        assertEquals(0, backend.applies)
        assertEquals(AccessMode.IDLE, backend.hardware.mode)
        assertEquals(last.id, restarted.state.value.selectedCardId)
        restarted.enterForeground(Any()).join()
        assertEquals(last.identity, backend.hardware.identity)
        assertEquals(default.id, store.load().defaultId)
    }

    @Test
    fun manualStopSurvivesProcessRestart() = runTest {
        val store = CardStore(Storage())
        val card = store.add("Home", identity(), 0, 0)
        val resume = ResumeStore()
        val previous = WalletCoordinator(store, Backend(), backgroundScope, resume)
        previous.enterForeground(foregroundOwner).join()
        previous.stop().join()

        val backend = Backend()
        val restarted = WalletCoordinator(store, backend, backgroundScope, resume)
        restarted.enterForeground(Any()).join()
        assertEquals(card.id, restarted.state.value.selectedCardId)
        assertEquals(AccessMode.IDLE, backend.hardware.mode)
        assertEquals(0, backend.applies)
        restarted.activate(card).join()
        assertFalse(resume.saved.manuallyStopped)
        assertEquals(card.identity, backend.hardware.identity)
    }

    @Test
    fun failedActivationKeepsThePreviousSuccessfulCardForRestart() = runTest {
        val store = CardStore(Storage())
        val first = store.add("First", identity(), 0, 0)
        val rejected = store.add("Rejected", identity(2), 0, 0)
        val resume = ResumeStore()
        val backend = Backend()
        val wallet = WalletCoordinator(store, backend, backgroundScope, resume)
        wallet.enterForeground(foregroundOwner).join()
        backend.reject = true
        wallet.activate(rejected).join()
        assertEquals(first.id, resume.saved.cardId)

        val restartedBackend = Backend()
        val restarted = WalletCoordinator(store, restartedBackend, backgroundScope, resume)
        restarted.enterForeground(Any()).join()
        assertEquals(first.identity, restartedBackend.hardware.identity)
    }

    @Test
    fun unreadableResumeStateDoesNotActivateACard() = runTest {
        val store = CardStore(Storage())
        store.add("Home", identity(), 0, 0)
        val resume = ResumeStore().apply { failRead = true }
        val backend = Backend()
        val wallet = WalletCoordinator(store, backend, backgroundScope, resume)
        wallet.enterForeground(foregroundOwner).join()
        assertEquals(0, backend.applies)
        assertTrue(wallet.state.value.storageFailed)
        assertEquals(AccessFailure.STORAGE, wallet.state.value.message)
    }

    @Test
    fun failedResumeWriteReportsStorageErrorAndKeepsTheActualHardwareState() = runTest {
        val store = CardStore(Storage())
        val card = store.add("Home", identity(), 0, 0)
        val resume = ResumeStore().apply { failWrite = true }
        val backend = Backend()
        val wallet = WalletCoordinator(store, backend, backgroundScope, resume)
        wallet.enterForeground(foregroundOwner).join()
        assertEquals(card.identity, wallet.state.value.hardware.identity)
        assertEquals(AccessMode.ACTIVE, wallet.state.value.hardware.mode)
        assertEquals(AccessFailure.STORAGE, wallet.state.value.message)
        assertNull(resume.saved.cardId)
    }

    @Test
    fun manualStopRestoresNfcEvenWhenResumeStateCannotBeSaved() = runTest {
        val store = CardStore(Storage())
        store.add("Home", identity(), 0, 0)
        val resume = ResumeStore()
        val backend = Backend()
        val wallet = WalletCoordinator(store, backend, backgroundScope, resume)
        wallet.enterForeground(foregroundOwner).join()
        resume.failWrite = true
        wallet.stop().join()
        assertEquals(AccessMode.IDLE, backend.hardware.mode)
        assertEquals(AccessFailure.STORAGE, wallet.state.value.message)
        wallet.leaveForeground(foregroundOwner).join()
        wallet.enterForeground(foregroundOwner).join()
        assertEquals(1, backend.applies)
    }

    @Test
    fun stoppingDuringAnApplyPersistsTheStopAfterTheApplyCompletes() = runTest {
        val store = CardStore(Storage())
        val card = store.add("Home", identity(), 0, 0)
        val resume = ResumeStore()
        val backend = Backend()
        val wallet = WalletCoordinator(store, backend, backgroundScope, resume)
        wallet.enterForeground(foregroundOwner).join()
        val next = store.add("Next", identity(2), 0, 0)
        var stopping: Job? = null
        backend.onApply = { stopping = wallet.stop() }
        wallet.activate(next).join()
        stopping!!.join()
        assertTrue(resume.saved.manuallyStopped)
        assertEquals(next.id, resume.saved.cardId)
        assertEquals(AccessMode.IDLE, backend.hardware.mode)
        assertNotEquals(card.id, resume.saved.cardId)
    }

    @Test
    fun explicitActivationBeforeResumeLoadOverridesTheSavedManualStop() = runTest {
        val store = CardStore(Storage())
        val previous = store.add("Previous", identity(), 0, 0)
        val requested = store.add("Requested", identity(2), 0, 0)
        val resume = ResumeStore().apply { saved = WalletResumeState(previous.id, true) }
        val backend = Backend()
        val wallet = WalletCoordinator(store, backend, backgroundScope, resume)
        val entering = wallet.enterForeground(foregroundOwner)
        wallet.activate(requested).join()
        entering.join()
        assertEquals(requested.identity, backend.hardware.identity)
        assertEquals(WalletResumeState(requested.id, false), resume.saved)
    }

    @Test
    fun deletedLastUsedCardFallsBackToTheExistingDefault() = runTest {
        val store = CardStore(Storage())
        val default = store.add("Default", identity(), 0, 0)
        val deleted = store.add("Deleted", identity(2), 0, 0)
        val resume = ResumeStore()
        val previous = WalletCoordinator(store, Backend(), backgroundScope, resume)
        previous.enterForeground(foregroundOwner).join()
        previous.activate(deleted).join()
        previous.delete(deleted).join()
        assertNull(resume.saved.cardId)

        val backend = Backend()
        val restarted = WalletCoordinator(store, backend, backgroundScope, resume)
        restarted.enterForeground(Any()).join()
        assertEquals(default.identity, backend.hardware.identity)
    }

    @Test
    fun leavingForegroundRestoresDefaultNfcAndRejectsBackgroundSelections() =
        runCase { _, store, backend, wallet ->
            val card = store.add("Home", identity(), 0, 0)
            wallet.activate(card).join()
            wallet.leaveForeground(foregroundOwner).join()
            wallet.activate(card).join()
            assertEquals(AccessMode.IDLE, backend.hardware.mode)
            assertEquals(1, backend.applies)
            assertEquals(1, backend.stops)
        }

    @Test
    fun returningToForegroundRestoresTheLastSelectionInsteadOfTheDefault() =
        runCase { _, store, backend, wallet ->
            val default = store.add("Default", identity(), 0, 0)
            val selected = store.add("Selected", identity(2), 0, 0)
            store.setDefault(default.id)
            wallet.activate(selected).join()
            wallet.leaveForeground(foregroundOwner).join()
            wallet.enterForeground(foregroundOwner).join()
            assertEquals(selected.identity, backend.hardware.identity)
            assertEquals(selected.id, wallet.state.value.selectedCardId)
        }

    @Test
    fun openingTheWalletUsesTheDefaultCard() = runCase { _, store, backend, wallet ->
        store.add("First", identity(), 0, 0)
        val default = store.add("Default", identity(2), 0, 0)
        store.setDefault(default.id)
        wallet.leaveForeground(foregroundOwner).join()
        wallet.enterForeground(foregroundOwner).join()
        assertEquals(default.identity, backend.hardware.identity)
    }

    @Test
    fun staleActivityPauseCannotStopTheNewForegroundEntry() = runCase { _, store, backend, wallet ->
        val card = store.add("Home", identity(), 0, 0)
        wallet.activate(card).join()
        val panel = Any()
        wallet.enterForeground(panel).join()
        wallet.leaveForeground(foregroundOwner).join()
        assertEquals(AccessMode.ACTIVE, backend.hardware.mode)
        assertEquals(0, backend.stops)
        wallet.leaveForeground(panel).join()
        assertEquals(AccessMode.IDLE, backend.hardware.mode)
    }

    @Test
    fun pauseCancelsPendingSelectionsBeforeTheyTouchTheController() =
        runCase { _, _, backend, wallet ->
            wallet.activate(profile())
            wallet.leaveForeground(foregroundOwner).join()
            advanceUntilIdle()
            assertEquals(0, backend.applies)
            assertEquals(AccessMode.IDLE, backend.hardware.mode)
        }

    @Test
    fun pauseDuringAnApplyQueuesRestorationInsteadOfDroppingItAsBusy() =
        runCase { _, _, backend, wallet ->
            backend.onApply = { wallet.leaveForeground(foregroundOwner) }
            wallet.activate(profile()).join()
            advanceUntilIdle()
            assertEquals(1, backend.applies)
            assertEquals(1, backend.stops)
            assertEquals(AccessMode.IDLE, backend.hardware.mode)
        }

    @Test
    fun failedForegroundRestoreKeepsTheActualStateAndReportsTheFailure() =
        runCase { _, _, backend, wallet ->
            wallet.activate(profile()).join()
            backend.reject = true
            wallet.leaveForeground(foregroundOwner).join()
            assertEquals(AccessMode.ACTIVE, wallet.state.value.hardware.mode)
            assertEquals(AccessFailure.RECOVERY_REQUIRED, wallet.state.value.message)
        }

    @Test
    fun losingTheServiceWhileLeavingForegroundIsNotReportedAsACompletedRestore() =
        runCase { _, _, backend, wallet ->
            wallet.activate(profile()).join()
            backend.serviceLost = true
            wallet.leaveForeground(foregroundOwner).join()
            assertEquals(AccessMode.ERROR, wallet.state.value.hardware.mode)
            assertEquals(AccessFailure.SERVICE_UNAVAILABLE, wallet.state.value.message)
        }

    @Test
    fun readerCannotRestoreAnIdentityWhileTheWalletIsInTheBackground() =
        runCase { _, store, backend, wallet ->
            val card = store.add("Home", identity(), 0, 0)
            wallet.activate(card).join()
            val token = wallet.beginReading()
            wallet.leaveForeground(foregroundOwner).join()
            wallet.endReading(token)
            assertEquals(AccessMode.IDLE, backend.hardware.mode)
            assertEquals(1, backend.applies)
            wallet.enterForeground(foregroundOwner).join()
            assertEquals(card.identity, backend.hardware.identity)
        }

    @Test
    fun returningFromReaderWaitsForItsLeaseBeforeRestoringTheSelection() =
        runCase { _, store, backend, wallet ->
            val card = store.add("Home", identity(), 0, 0)
            wallet.activate(card).join()
            wallet.leaveForeground(foregroundOwner).join()
            val token = wallet.beginReading()
            val returning = wallet.enterForeground(foregroundOwner)
            runCurrent()
            assertEquals(AccessMode.IDLE, backend.hardware.mode)
            wallet.endReading(token)
            returning.join()
            assertEquals(card.identity, backend.hardware.identity)
        }

    @Test
    fun manualStopRemainsStoppedAcrossForegroundChanges() = runCase { _, store, backend, wallet ->
        val card = store.add("Home", identity(), 0, 0)
        wallet.activate(card).join()
        wallet.stop().join()
        wallet.leaveForeground(foregroundOwner).join()
        wallet.enterForeground(foregroundOwner).join()
        assertEquals(AccessMode.IDLE, backend.hardware.mode)
        assertEquals(1, backend.applies)
        wallet.activate(card).join()
        assertEquals(AccessMode.ACTIVE, backend.hardware.mode)
    }

    @Test
    fun manualStopDuringReadingCancelsThePreviousIdentityRestore() =
        runCase { _, _, backend, wallet ->
            wallet.activate(profile()).join()
            val token = wallet.beginReading()
            wallet.stop().join()
            wallet.endReading(token)
            assertEquals(AccessMode.IDLE, backend.hardware.mode)
            assertEquals(1, backend.applies)
        }

    @Test
    fun explicitSelectionSupersedesAnAutomaticResumeThatHasNotStarted() =
        runCase { _, store, backend, wallet ->
            val first = store.add("First", identity(), 0, 0)
            val selected = store.add("Selected", identity(2), 0, 0)
            wallet.activate(first).join()
            wallet.leaveForeground(foregroundOwner).join()
            val returning = wallet.enterForeground(foregroundOwner)
            wallet.activate(selected)
            advanceUntilIdle()
            returning.join()
            assertEquals(selected.identity, backend.hardware.identity)
        }

    @Test
    fun manualStopCancelsAnAutomaticResumeThatHasNotStarted() =
        runCase { _, store, backend, wallet ->
            val card = store.add("Home", identity(), 0, 0)
            wallet.activate(card).join()
            wallet.leaveForeground(foregroundOwner).join()
            wallet.enterForeground(foregroundOwner)
            wallet.stop().join()
            advanceUntilIdle()
            assertEquals(AccessMode.IDLE, backend.hardware.mode)
            assertEquals(1, backend.applies)
        }

    @Test
    fun rapidSelectionsApplyOnlyTheLatestCard() = runCase { _, _, backend, wallet ->
        val card = profile()
        val last = profile(2)
        wallet.activate(card)
        wallet.activate(last)
        advanceUntilIdle()
        assertEquals(1, backend.applies)
        assertEquals(last.identity, wallet.state.value.hardware.identity)
    }

    @Test
    fun selectingOriginalCardDuringAChangeRestoresLatestIntent() =
        runCase { _, _, backend, wallet ->
            val original = profile()
            val next = profile(2)
            wallet.activate(original).join()
            backend.onApply = { identity ->
                if (identity == next.identity) wallet.activate(original)
            }
            wallet.activate(next).join()
            advanceUntilIdle()
            assertEquals(original.identity, wallet.state.value.hardware.identity)
            assertEquals(3, backend.applies)
        }

    @Test
    fun reselectingAnActiveCardDoesNotRewriteController() = runCase { _, _, backend, wallet ->
        val card = profile()
        wallet.activate(card).join()
        wallet.activate(card).join()
        assertEquals(1, backend.applies)
    }

    @Test
    fun explicitStopCancelsSelectionsThatHaveNotStarted() = runCase { _, _, backend, wallet ->
        wallet.activate(profile())
        wallet.stop().join()
        advanceUntilIdle()
        assertEquals(0, backend.applies)
        assertEquals(AccessMode.IDLE, wallet.state.value.mode)
    }

    @Test
    fun selectionWaitsForStorageAndKeepsOnlyTheLatestTarget() =
        runCase { _, store, backend, wallet ->
            val card = store.add("Original", identity(), 0, 0)
            wallet.update(card.withAppearance("Renamed", card.color, card.icon))
            wallet.activate(profile(2))
            wallet.activate(profile(3))
            advanceUntilIdle()
            assertEquals("Renamed", store.load().cards.single().name)
            assertEquals(identity(3), wallet.state.value.hardware.identity)
            assertEquals(1, backend.applies)
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
    fun walletEditReportsBusyWhileAnApplyHoldsTheOperation() = runBlocking {
        val store = CardStore(Storage())
        val card = store.add("Home", identity(), 0, 0)
        val backend = Backend()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var blockApply = false
        backend.onApply = {
            if (!blockApply) return@onApply
            if (!started.isCompleted) started.complete(Unit)
            runBlocking { release.await() }
        }
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val wallet = WalletCoordinator(store, backend, owner, ResumeStore())
        try {
            wallet.enterForeground(foregroundOwner).join()
            blockApply = true
            val applying = wallet.activate(card)
            withTimeout(5_000) { started.await() }
            wallet.update(card.withNotes("note")).join()
            assertEquals(AccessFailure.BUSY, wallet.state.value.message)
            assertEquals("", store.load().cards[0].notes)
            release.complete(Unit)
            applying.join()
            assertEquals(card.identity, backend.hardware.identity)
        } finally {
            if (!release.isCompleted) release.complete(Unit)
            owner.cancel()
        }
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
    fun readingAutomaticallyPausesAndRestoresThePreviousIdentity() =
        runCase { _, _, backend, wallet ->
            val card = profile()
            wallet.activate(card).join()
            val token = wallet.beginReading()
            assertEquals(1, backend.stops)
            assertEquals(AccessMode.IDLE, backend.hardware.mode)
            assertEquals(AccessMode.READING, wallet.state.value.mode)
            wallet.endReading(token)
            assertEquals(card.identity, wallet.state.value.hardware.identity)
            assertEquals(AccessMode.ACTIVE, wallet.state.value.mode)
            assertEquals(2, backend.applies)
        }

    @Test
    fun failedPauseNeverEnablesReadingOrLosesActiveCard() = runCase { _, _, backend, wallet ->
        val card = profile()
        wallet.activate(card).join()
        backend.reject = true
        try {
            wallet.beginReading()
            fail("Failed pause must not start a reader")
        } catch (e: AccessException) {
            assertEquals(AccessFailure.RECOVERY_REQUIRED, e.failure)
        }
        assertEquals(card.identity, backend.hardware.identity)
        assertFalse(wallet.state.value.busy)
    }

    @Test
    fun readerDoesNotUndoAnExplicitNfcShutdown() = runCase { _, _, backend, wallet ->
        wallet.activate(profile()).join()
        val token = wallet.beginReading()
        backend.hardware =
            HardwareState(AccessMode.NFC_OFF, generation = backend.hardware.generation + 1)
        wallet.endReading(token)
        assertEquals(1, backend.applies)
        assertEquals(AccessMode.NFC_OFF, wallet.state.value.mode)
    }

    @Test
    fun readerDoesNotOverwriteAResetOrAnotherSession() = runCase { _, _, backend, wallet ->
        wallet.activate(profile()).join()
        val token = wallet.beginReading()
        backend.hardware =
            HardwareState(AccessMode.IDLE, generation = backend.hardware.generation + 1)
        wallet.endReading(token)
        assertEquals(1, backend.applies)
        assertEquals(AccessMode.IDLE, wallet.state.value.mode)
    }

    @Test
    fun failedResumeDoesNotClaimPreviousCardIsActive() = runCase { _, _, backend, wallet ->
        wallet.activate(profile()).join()
        val token = wallet.beginReading()
        backend.reject = true
        wallet.endReading(token)
        assertEquals(AccessMode.IDLE, wallet.state.value.hardware.mode)
        assertNull(wallet.state.value.hardware.identity)
        assertEquals(AccessFailure.TIMEOUT, wallet.state.value.message)
        assertFalse(wallet.state.value.busy)
    }

    @Test
    fun cardStaysPausedWhileNamingAndSavingAndResumesOnExit() =
        runCase { _, store, backend, wallet ->
            val original = store.add("Original", identity(), 0, 0)
            wallet.activate(original).join()
            val session = ReaderSession(wallet, this, defaultName = { "Card $it" })
            session.resume().join()
            session
                .onTag(
                    session.state.value.token!!,
                    object : IdentityReader {
                        override fun read() = identity(2)

                        override fun close() {}
                    },
                )
                .join()
            advanceUntilIdle()
            assertEquals(ReaderPhase.FOUND, session.state.value.phase)
            assertEquals(AccessMode.IDLE, backend.hardware.mode)
            assertTrue(wallet.state.value.busy)
            session.save().join()
            assertEquals(ReaderPhase.SAVED, session.state.value.phase)
            assertEquals(2, store.load().cards.size)
            assertEquals(AccessMode.IDLE, backend.hardware.mode)
            session.pause().join()
            assertEquals(original.identity, wallet.state.value.hardware.identity)
            assertFalse(wallet.state.value.busy)
        }

    @Test
    fun cancellingReaderResumesPreviousCardAndStaleCloseCannotStopIt() =
        runCase { _, _, backend, wallet ->
            val card = profile()
            wallet.activate(card).join()
            val session = ReaderSession(wallet, this, defaultName = { "Card $it" })
            session.resume().join()
            val token = session.state.value.token!!
            session.pause().join()
            wallet.endReading(token)
            assertEquals(card.identity, wallet.state.value.hardware.identity)
            assertEquals(2, backend.applies)
            assertEquals(1, backend.stops)
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
        assertTrue(wallet.state.value.busy)
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
        val wallet = WalletCoordinator(CardStore(Storage()), Backend(), owner, ResumeStore())
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
        val wallet = WalletCoordinator(CardStore(Storage()), Backend(), owner, ResumeStore())
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
        val wallet =
            WalletCoordinator(
                CardStore(Storage()),
                backend,
                owner,
                ResumeStore(),
                diagnostics = { _, _, _, _ -> throw IOException("Full diagnostic cache") },
            )
        try {
            wallet.enterForeground(foregroundOwner).join()
            wallet.activate(profile()).join()
            assertEquals(AccessMode.ACTIVE, wallet.state.value.hardware.mode)
            assertNull(wallet.state.value.message)
            wallet.stop().join()
            assertEquals(AccessMode.IDLE, wallet.state.value.hardware.mode)
            assertNull(wallet.state.value.message)
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun importingCardsKeepsActiveHardwareAndExistingDefault() =
        runCase { _, store, backend, wallet ->
            val active = store.add("Existing", identity(), 0, 0)
            wallet.activate(active).join()
            val incoming = profile(2)
            val result = wallet.importCards(WalletSnapshot(listOf(incoming), incoming.id)).await()
            assertEquals(1, result.added)
            assertEquals(2, wallet.state.value.wallet.cards.size)
            assertEquals(active.id, wallet.state.value.wallet.defaultId)
            assertEquals(active.identity, wallet.state.value.hardware.identity)
            assertEquals(1, backend.applies)
            assertEquals(0, backend.stops)
        }

    @Test
    fun importingDuringReadLeaseIsRejectedWithoutMutatingWallet() =
        runCase { _, store, backend, wallet ->
            store.add("Existing", identity(), 0, 0)
            val token = wallet.beginReading()
            try {
                val incoming = profile(2)
                try {
                    wallet.importCards(WalletSnapshot(listOf(incoming), null)).await()
                    fail("Expected a busy reader lease")
                } catch (e: AccessException) {
                    assertEquals(AccessFailure.BUSY, e.failure)
                }
                assertEquals(1, store.load().cards.size)
                assertEquals(0, backend.applies)
                assertEquals(0, backend.stops)
            } finally {
                wallet.endReading(token)
            }
        }
}
