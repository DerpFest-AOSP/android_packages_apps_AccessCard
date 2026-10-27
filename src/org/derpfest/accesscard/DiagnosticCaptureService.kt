// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import kotlinx.coroutines.*

/** Runs only while the user explicitly enables diagnostics, including away from the app. */
class DiagnosticCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var task: Job? = null
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AccessCard:Diagnostics")
            .apply { setReferenceCounted(false) }
    }
    private val wallet
        get() = (application as AccessCardApplication).wallet

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!wallet.preferences.state.value.debug) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.diagnostic_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, OptionsActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_nfc)
                .setContentTitle(getString(R.string.diagnostic_notification_title))
                .setContentText(getString(R.string.diagnostic_notification_body))
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
        startForeground(4102, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (task == null)
            task =
                scope.launch {
                    try {
                        var nextWake = 0L
                        while (
                            isActive &&
                                wallet.preferences.state.value.debug &&
                                getSystemService(UserManager::class.java).isUserForeground
                        ) {
                            if (SystemClock.elapsedRealtime() >= nextWake) {
                                wakeLock.acquire(120_000)
                                nextWake = SystemClock.elapsedRealtime() + 30_000
                            }
                            try {
                                wallet.diagnostics.system.tick()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                wallet.diagnostics.system.unavailable()
                            }
                            delay(1_000)
                        }
                    } finally {
                        releaseWakeLock()
                        withContext(NonCancellable) { wallet.diagnostics.system.interrupted() }
                        stopSelf()
                    }
                }
        return START_STICKY
    }

    override fun onDestroy() {
        // The capture coroutine releases the wake lock in finally. Releasing
        // it here as well races with that thread and throws if it already ran.
        scope.cancel()
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        val lock = wakeLock
        if (lock.isHeld) lock.release()
    }

    companion object {
        private const val CHANNEL = "accesscard_diagnostics"

        fun ensure(context: Context) {
            context.startForegroundService(Intent(context, DiagnosticCaptureService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DiagnosticCaptureService::class.java))
        }
    }
}
