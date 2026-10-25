// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.uitests

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.derpfest.accesscard.WalletState
import org.derpfest.accesscard.model.*
import org.derpfest.accesscard.nfc.*
import org.derpfest.accesscard.ui.*
import org.derpfest.accesscard.ui.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises the production wallet composables without permissions or controller writes. */
class WalletUiTest {
    @get:Rule val compose = createComposeRule()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private val capabilities =
        BackendCapabilities(
            true,
            true,
            true,
            Qualification.EXPERIMENTAL,
            setOf(4, 7, 10),
            emptySet(),
        )

    private fun card(n: Int, name: String = "Test card $n") =
        AccessCardProfile(
            UUID.randomUUID().toString(),
            name,
            CardIdentity(byteArrayOf(n.toByte(), 2, 3, 4), byteArrayOf(0, 4), 8),
            0,
            0,
            0,
        )

    private var activated: AccessCardProfile? = null
    private var adds = 0
    private var stops = 0
    private var moved: Pair<String, Int>? = null

    private fun show(state: WalletState, quick: Boolean = false, session: State<Int>? = null) {
        compose.setContent {
            AccessCardTheme {
                WalletScreen(
                    state,
                    capabilities,
                    null,
                    onAdd = { adds++ },
                    onActivate = { activated = it },
                    onStop = { stops++ },
                    onUpdate = {},
                    onDefault = {},
                    onDelete = {},
                    onMove = { id, delta -> moved = id to delta },
                    onAddTile = {},
                    onNfcSettings = {},
                    onRefresh = {},
                    onClearMessage = {},
                    quick = quick,
                    selectionSession = session?.value ?: 0,
                )
            }
        }
    }

    @Test
    fun emptyWalletOffersAdd() {
        show(WalletState(loading = false))
        compose.onNodeWithText(text(R.string.empty_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.add_card)).performClick()
        assertEquals(1, adds)
        assertNull(activated)
    }

    @Test
    fun swipeBrowsesUntilExplicitUse() {
        val a = card(1)
        val b = card(2)
        show(WalletState(wallet = WalletSnapshot(listOf(a, b), a.id), loading = false))
        compose.onNodeWithTag("card_carousel").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertNull(activated)
        compose.onNodeWithText(text(R.string.page_indicator, 2, 2)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.use_card)).performClick()
        assertEquals(b.id, activated?.id)
        // The callback alone cannot establish ACTIVE or change the default.
        compose.onNodeWithText(text(R.string.use_card)).assertIsEnabled()
    }

    @Test
    fun quickPickerPrioritizesHardwareOverDefault() {
        val a = card(1)
        val b = card(2)
        show(
            WalletState(
                wallet = WalletSnapshot(listOf(a, b), b.id),
                loading = false,
                hardware = HardwareState(AccessMode.ACTIVE, a.identity),
            ),
            quick = true,
        )
        compose.onNodeWithText(text(R.string.stop_card)).performClick()
        assertEquals(1, stops)
        assertNull(activated)
        compose.onNodeWithText(text(R.string.page_indicator, 1, 2)).assertIsDisplayed()
    }

    @Test
    fun reopeningPickerDiscardsLastBrowseAndReselectsActiveCard() {
        val a = card(1)
        val b = card(2)
        val session = mutableIntStateOf(0)
        show(
            WalletState(
                wallet = WalletSnapshot(listOf(a, b), b.id),
                loading = false,
                hardware = HardwareState(AccessMode.ACTIVE, a.identity),
            ),
            quick = true,
            session = session,
        )
        compose.onNodeWithTag("card_carousel").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.use_card)).assertIsEnabled()
        compose.runOnIdle { session.intValue++ }
        compose.onNodeWithText(text(R.string.page_indicator, 1, 2)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.stop_card)).assertIsEnabled()
        assertNull(activated)
        assertEquals(0, stops)
    }

    @Test
    fun readLeaseDisablesActivationInQuickPicker() {
        val a = card(1)
        show(
            WalletState(
                wallet = WalletSnapshot(listOf(a), a.id),
                loading = false,
                operation = AccessMode.READING,
            ),
            quick = true,
        )
        compose.onNodeWithText(text(R.string.use_card)).assertIsNotEnabled()
        assertNull(activated)
    }

    @Test
    fun allCardsCanReorderWithoutActivation() {
        val a = card(1)
        val b = card(2)
        show(WalletState(wallet = WalletSnapshot(listOf(a, b), a.id), loading = false))
        compose.onNodeWithText(text(R.string.all_cards)).performClick()
        compose.onNodeWithText(text(R.string.edit_order)).performClick()
        compose.onNodeWithContentDescription(text(R.string.move_up, b.name)).performClick()
        assertEquals(b.id to -1, moved)
        assertNull(activated)
    }

    @Test
    fun recoveryFailureOffersExplicitRetryWithoutActivatingACard() {
        show(
            WalletState(
                loading = false,
                hardware =
                    HardwareState(
                        AccessMode.ERROR,
                        needsRecovery = true,
                        failure = AccessFailure.JOURNAL_UNAVAILABLE,
                    ),
            ),
            quick = true,
        )
        compose.onNodeWithText(text(R.string.error_journal)).assertExists()
        compose.onNodeWithText(text(R.string.retry_recovery)).performScrollTo().performClick()
        assertEquals(1, stops)
        assertNull(activated)
    }

    @Test
    fun nfcOffAndLongNameRemainUsable() {
        val a =
            card(
                1,
                "A very long access card name for a building and entrance on another floor".take(64),
            )
        show(
            WalletState(
                wallet = WalletSnapshot(listOf(a), a.id),
                loading = false,
                hardware = HardwareState(AccessMode.NFC_OFF),
            )
        )
        compose.onNodeWithText(text(R.string.use_card)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.mode_nfc_off)).assertExists()
        compose.onNodeWithText(text(R.string.all_cards)).performClick()
        compose.onAllNodesWithText(a.name).onLast().assertIsDisplayed()
    }
    @Test
    fun searchFindsNotesWithoutActivatingAndReorderingClearsFilter() {
        val a = card(1, "Home")
        val b = card(2, "Office").withNotes("South entrance")
        show(WalletState(wallet = WalletSnapshot(listOf(a, b), a.id), loading = false))
        compose.onNodeWithText(text(R.string.all_cards)).performClick()
        compose.onNodeWithTag("card_search").performTextInput("sOuTh")
        compose.onNodeWithTag("card_list_row_${a.id}").assertDoesNotExist()
        compose.onNodeWithTag("card_list_row_${b.id}").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.edit_order)).performClick()
        compose.onNodeWithTag("card_search").assertIsNotEnabled()
        compose.onNodeWithTag("card_list_row_${a.id}").assertExists()
        assertNull(activated)
    }

    @Test
    fun manualTestRequiresLocationAndUsesExplicitUserResult() {
        var saved: CardTestRecord? = null
        compose.setContent { AccessCardTheme { ManualTestDialog({}, { saved = it }) } }
        compose.onNodeWithText(text(R.string.save_changes)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.test_location)).performTextInput("Lobby")
        compose.onNodeWithText(text(R.string.test_rejected)).performClick()
        compose.onNodeWithText(text(R.string.save_changes)).performClick()
        assertEquals("Lobby", saved?.location)
        assertEquals(CardTestRecord.Result.REJECTED, saved?.result)
        assertNull(activated)
    }

}
