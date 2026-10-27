// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.derpfest.accesscard.nfc.AccessMode
import org.derpfest.accesscard.tools.AppTheme
import org.derpfest.accesscard.ui.WalletScreen

class QuickAccessActivity : AccessCardActivity() {
    private val model by lazy { ViewModelProvider(this)[AccessCardViewModel::class.java] }
    private var polling: Job? = null
    private var entryJob: Job? = null
    private var entering by mutableStateOf(true)
    private var selectionSession by mutableIntStateOf(0)
    private var entryCardId by mutableStateOf<String?>(null)
    private val reader =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshEntry()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = .35f }
        setContent {
            val state by model.state.collectAsState()
            val controller = model.controller
            AppTheme(controller.preferences) {
                WalletScreen(
                    if (entering) state.copy(loading = true, operation = AccessMode.IDLE)
                    else state,
                    controller.backend.capabilities,
                    entryCardId,
                    onAdd = { reader.launch(Intent(this, ReaderActivity::class.java)) },
                    onActivate = { controller.activate(it) },
                    onStop = { controller.stop() },
                    onUpdate = { controller.update(it) },
                    onDefault = { controller.setDefault(it) },
                    onDelete = { controller.delete(it) },
                    onMove = { id, delta -> controller.move(id, delta) },
                    onAddTile = {},
                    onNfcSettings = { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) },
                    onRefresh = { controller.refresh() },
                    onClearMessage = controller::clearMessage,
                    quick = true,
                    onOpenApp = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                )
                        )
                        finish()
                    },
                    onClose = ::finish,
                    selectionSession = selectionSession,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        refreshEntry()
    }

    private fun refreshEntry() {
        entryJob?.cancel()
        entering = true
        entryJob =
            lifecycleScope.launch {
                model.controller.refresh().join()
                val latest = model.state.value
                entryCardId =
                    latest.activeCard?.id
                        ?: latest.selectedCardId
                        ?: latest.wallet.defaultId
                        ?: latest.wallet.cards.firstOrNull()?.id
                selectionSession++
                entering = false
            }
    }

    override fun onStart() {
        super.onStart()
        refreshEntry()
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        window.setGravity(Gravity.BOTTOM)
        polling =
            lifecycleScope.launch {
                while (isActive) {
                    model.controller.refresh().join()
                    delay(2000)
                }
            }
    }

    override fun onStop() {
        entryJob?.cancel()
        polling?.cancel()
        super.onStop()
    }
}
