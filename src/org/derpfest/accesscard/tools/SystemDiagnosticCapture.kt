// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.derpfest.accesscard.data.SystemDiagnosticStore

enum class CaptureState {
    OFF,
    STARTING,
    RECORDING,
    PARTIAL,
}

class SystemDiagnosticCapture(
    private val context: Context,
    private val preferences: AppPreferences,
) {
    private val mutex = Mutex()
    private val store = SystemDiagnosticStore(File(context.cacheDir, "system-diagnostics"))
    private val bridge = NfcDiagnosticBridge(context)
    private var started = false
    private var nextLease = 0L
    private var traceAvailable = false
    private var lastState = ""
    private val output = ByteArray(256 * 1024)
    private val mutable = MutableStateFlow(CaptureState.OFF)
    val state = mutable.asStateFlow()

    fun checkPermissions() {
        for (permission in arrayOf(Manifest.permission.READ_LOGS, Manifest.permission.DUMP)) {
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
                throw IOException("System diagnostic permissions unavailable")
        }
    }

    fun starting() {
        mutable.value = CaptureState.STARTING
    }

    fun unavailable() {
        mutable.value = CaptureState.PARTIAL
    }

    fun bytes(): Long = store.size()

    suspend fun tick() =
        mutex.withLock {
            if (!preferences.state.value.debug) return@withLock
            if (!context.getSystemService(android.os.UserManager::class.java).isUserForeground) {
                unavailable()
                return@withLock
            }
            if (started && store.expired(System.currentTimeMillis())) {
                runCatching { NativeNfcLogs.close() }
                runCatching { bridge.lease(false) }
                store.clear()
                started = false
                nextLease = 0
            }
            if (!started) {
                checkPermissions()
                store.begin(System.currentTimeMillis())
                started = true
                store.status(
                    "recorder",
                    "running; resumed=${Instant.now()}; buffers=main,system,radio,crash; NFC-only",
                )
                snapshot("nfc-start.txt")
            }
            var logAvailable = false
            try {
                val batch = NativeNfcLogs.read(store.cursor(), output)
                if (batch.size != 6 || batch[4] !in 0..output.size.toLong())
                    throw IOException("Invalid logd response")
                store.batch(
                    batch[0],
                    output,
                    batch[4].toInt(),
                    "code=${batch[1]} scanned=${batch[2]} selected=${batch[3]} nfc_uid_seen=${batch[5]}",
                )
                logAvailable = batch[1] >= 0 && batch[5] == 1L
                if (!logAvailable)
                    store.status(
                        "last_logd_error",
                        "${Instant.now()} code=${batch[1]} nfc_uid_seen=${batch[5]}; consent or log source may be unavailable",
                    )
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                store.status("last_logd_error", "${Instant.now()} class=${e.javaClass.simpleName}")
                store.batch(
                    store.cursor(),
                    output,
                    0,
                    "unavailable; class=${e.javaClass.simpleName}",
                )
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (now >= nextLease) {
                traceAvailable = runCatching { bridge.lease(true) }.getOrDefault(false)
                nextLease = now + 15_000
                store.status(
                    "nfc_trace",
                    if (traceAvailable) "leased" else "unavailable; matching NFC module required",
                )
            }
            if (traceAvailable) {
                runCatching {
                        val state = bridge.state() ?: throw IOException("NFC state unavailable")
                        if (state.getInt("diagnostics_version") != 1)
                            throw IOException("NFC trace unavailable")
                        val current = buildString {
                            appendLine(
                                "RF state reported by the NFC service; field activity does not prove reader acceptance."
                            )
                            for (key in
                                arrayOf(
                                    "rf_field",
                                    "host_activated",
                                    "ee_listen",
                                    "rf_discovery",
                                    "active",
                                    "recovery_required",
                                )) appendLine("$key=${state.getBoolean(key)}")
                            for (key in
                                arrayOf(
                                    "screen_state",
                                    "listen_tech",
                                    "poll_tech",
                                    "failed_parameter",
                                )) appendLine("$key=${state.getInt(key, -1)}")
                            appendLine("generation=${state.getLong("generation")}")
                            appendLine("failure=${state.getString("failure_reason")}")
                        }
                        if (current != lastState) {
                            store.snapshot(
                                "nfc-state.txt",
                                ("captured=${Instant.now()}\n" + current).toByteArray(),
                            )
                            lastState = current
                        }
                        val events = state.getString("diagnostic_events", "unavailable")
                        store.trace(state.getString("diagnostic_instance", "unknown"), events)
                        if (!events.startsWith("recording=true")) {
                            traceAvailable = false
                            nextLease = 0
                        }
                    }
                    .onFailure {
                        traceAvailable = false
                        store.status(
                            "last_trace_error",
                            "${Instant.now()} class=${it.javaClass.simpleName}",
                        )
                        store.status(
                            "nfc_trace",
                            "state unavailable; class=${it.javaClass.simpleName}",
                        )
                    }
            }
            mutable.value =
                if (logAvailable && traceAvailable) CaptureState.RECORDING else CaptureState.PARTIAL
        }

    suspend fun interrupted() =
        mutex.withLock {
            runCatching { NativeNfcLogs.close() }
            runCatching { bridge.lease(false) }
            if (preferences.state.value.debug) {
                runCatching {
                    store.status("recorder", "interrupted=${Instant.now()}; logs retained")
                    store.status("last_interruption", Instant.now().toString())
                }
                mutable.value = CaptureState.PARTIAL
            }
            started = false
            nextLease = 0
            traceAvailable = false
        }

    suspend fun disable() =
        mutex.withLock {
            runCatching { NativeNfcLogs.close() }
            runCatching { bridge.lease(false) }
            started = false
            nextLease = 0
            lastState = ""
            store.clear()
            mutable.value = CaptureState.OFF
        }

    suspend fun reset() {
        disable()
        if (preferences.state.value.debug) starting()
    }

    suspend fun archive(): Map<String, ByteArray> =
        mutex.withLock {
            if (!preferences.state.value.debug) throw IOException("Debug recording is disabled")
            if (!started) {
                store.begin(System.currentTimeMillis())
                store.status("recorder", "no running recorder; system history may be incomplete")
            }
            snapshot("nfc-latest.txt")
            store.export(System.currentTimeMillis())
        }

    private fun snapshot(name: String) {
        try {
            val dump = bridge.dump()
            store.snapshot(name, dump)
            store.status(
                "nfc_snapshot",
                "file=$name; captured=${Instant.now()}; inspect file for truncation or permission errors",
            )
        } catch (e: Exception) {
            store.snapshot(name, "SNAPSHOT_UNAVAILABLE: ${e.javaClass.simpleName}\n".toByteArray())
            store.status("nfc_snapshot", "unavailable; class=${e.javaClass.simpleName}")
        }
    }
}
