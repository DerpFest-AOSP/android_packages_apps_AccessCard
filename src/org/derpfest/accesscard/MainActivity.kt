// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.derpfest.accesscard.ui.AccessCardTheme
import org.derpfest.accesscard.ui.WalletScreen

const val EXTRA_SELECTED_CARD = "org.derpfest.accesscard.SELECTED_CARD"

class MainActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[AccessCardViewModel::class.java] }
    private var requestedCard by mutableStateOf<String?>(null)
    private var polling: Job? = null
    private val reader =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK)
                requestedCard = it.data?.getStringExtra(EXTRA_SELECTED_CARD)
            model.controller.refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedCard = intent.getStringExtra(EXTRA_SELECTED_CARD)
        setContent {
            val state by model.state.collectAsState()
            val controller = model.controller
            AccessCardTheme {
                WalletScreen(
                    state,
                    controller.backend.capabilities,
                    requestedCard,
                    onAdd = { reader.launch(Intent(this, ReaderActivity::class.java)) },
                    onActivate = { controller.activate(it) },
                    onStop = { controller.stop() },
                    onUpdate = { controller.update(it) },
                    onDefault = { controller.setDefault(it) },
                    onDelete = { controller.delete(it) },
                    onMove = { id, delta -> controller.move(id, delta) },
                    onAddTile = ::addTile,
                    onNfcSettings = ::openNfcSettings,
                    onRefresh = { controller.refresh() },
                    onClearMessage = controller::clearMessage,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedCard = intent.getStringExtra(EXTRA_SELECTED_CARD)
    }

    override fun onStart() {
        super.onStart()
        polling =
            lifecycleScope.launch {
                while (isActive) {
                    model.controller.refresh().join()
                    delay(2000)
                }
            }
    }

    override fun onStop() {
        polling?.cancel()
        super.onStop()
    }

    private fun openNfcSettings() {
        startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
    }

    private fun addTile() {
        try {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, AccessCardTileService::class.java),
                "AccessCard",
                Icon.createWithResource(this, R.drawable.ic_card),
                mainExecutor,
            ) { result ->
                val text =
                    if (
                        result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                            result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                    )
                        R.string.tile_added
                    else R.string.error_tile
                Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, R.string.error_tile, Toast.LENGTH_LONG).show()
        }
    }
}
