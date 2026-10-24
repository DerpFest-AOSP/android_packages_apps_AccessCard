// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.derpfest.accesscard.ui.AccessCardTheme
import org.derpfest.accesscard.ui.WalletScreen

class QuickAccessActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[AccessCardViewModel::class.java] }
    private var polling: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = .35f }
        setContent {
            val state by model.state.collectAsState()
            val controller = model.controller
            AccessCardTheme {
                WalletScreen(
                    state,
                    controller.backend.capabilities,
                    null,
                    onAdd = {
                        startActivity(Intent(this, ReaderActivity::class.java))
                        finish()
                    },
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
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
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
        polling?.cancel()
        super.onStop()
    }
}
