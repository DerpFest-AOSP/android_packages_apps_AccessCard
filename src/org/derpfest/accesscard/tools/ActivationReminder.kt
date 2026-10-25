// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.tools

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import org.derpfest.accesscard.QuickAccessActivity
import org.derpfest.accesscard.R

/** A short-lived operation receipt, not a second source of live NFC state. */
class ActivationReminder(private val context: Context, private val preferences: AppPreferences) {
    private val manager
        get() = context.getSystemService(NotificationManager::class.java)

    @Synchronized
    fun cancel() {
        manager.cancel(ID)
    }

    @Synchronized
    fun applied(stillCurrent: () -> Boolean) {
        if (
            !stillCurrent() ||
                !preferences.state.value.reminders ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED ||
                !manager.areNotificationsEnabled()
        )
            return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.reminder_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val controls =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, QuickAccessActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        manager.notify(
            ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_card)
                .setContentTitle(context.getString(R.string.reminder_title))
                .setContentText(context.getString(R.string.reminder_body))
                .setContentIntent(controls)
                .addAction(
                    Notification.Action.Builder(
                            Icon.createWithResource(context, R.drawable.ic_card),
                            context.getString(R.string.open_card_controls),
                            controls,
                        )
                        .build()
                )
                .setAutoCancel(true)
                .setTimeoutAfter(60_000)
                .setLocalOnly(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build(),
        )
    }

    companion object {
        private const val CHANNEL = "activation_receipts"
        private const val ID = 1
    }
}
