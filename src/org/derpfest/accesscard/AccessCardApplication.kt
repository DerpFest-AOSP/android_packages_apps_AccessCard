// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import org.derpfest.accesscard.nfc.AospAccessCardBackend

class AccessCardApplication : Application() {
    val wallet by lazy { WalletController(this, AospAccessCardBackend(this)) }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    wallet.refresh(notifyTile = true)
                }
            },
            IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
            Context.RECEIVER_EXPORTED,
        )
    }
}
