// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.tools

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.derpfest.accesscard.DiagnosticCaptureService
import org.derpfest.accesscard.WalletState
import org.derpfest.accesscard.data.DiagnosticStore
import org.derpfest.accesscard.nfc.*

/** All methods run on the wallet IO scope. Debug off never writes a diagnostic event. */
class Diagnostics(private val context: Context, private val preferences: AppPreferences) {
    val system = SystemDiagnosticCapture(context, preferences)
    private val control = Mutex()
    private var store: DiagnosticStore? = null
    private var cacheFailed = false

    suspend fun initialize() =
        control.withLock {
            synchronized(this) { cache() }
            if (preferences.state.value.debug) ensureRecording() else system.disable()
        }

    fun ensureRecording() {
        if (!preferences.state.value.debug) return
        try {
            system.starting()
            DiagnosticCaptureService.ensure(context)
        } catch (_: Exception) {
            system.unavailable()
        }
    }

    private fun cache(): DiagnosticStore {
        return store
            ?: DiagnosticStore(File(context.cacheDir, "diagnostics"), System::currentTimeMillis)
                .also {
                    it.setEnabled(preferences.state.value.debug)
                    store = it
                }
    }

    suspend fun configure(enabled: Boolean) =
        control.withLock {
            if (enabled) system.checkPermissions()
            preferences.update { it.copy(debug = enabled) }
            try {
                synchronized(this) {
                    cache().setEnabled(enabled)
                    cacheFailed = false
                }
            } catch (e: IOException) {
                cacheFailed = true
                throw e
            } finally {
                if (enabled) ensureRecording()
                else {
                    DiagnosticCaptureService.stop(context)
                    system.disable()
                }
            }
        }

    suspend fun clear() =
        control.withLock {
            synchronized(this) {
                cache().clear()
                cacheFailed = false
            }
            system.reset()
            if (preferences.state.value.debug) ensureRecording()
        }

    @Synchronized fun bytes(): Long = cache().size() + system.bytes()

    suspend fun writeArchive(
        output: OutputStream,
        state: WalletState,
        capabilities: BackendCapabilities,
    ) {
        val appReport = report(state, capabilities)
        runCatching { system.tick() }.onFailure { system.unavailable() }
        val files = system.archive()
        ZipOutputStream(output).use { zip ->
            val entries =
                linkedMapOf(
                    "app-diagnostics.txt" to appReport.toByteArray(),
                    "collection.txt" to
                        ("AccessCard diagnostic bundle v2\n" +
                                "created=${Instant.now()}\n" +
                                "capture_state=${system.state.value}\n" +
                                "NFC-related main/system/radio/crash logs only; unrelated apps, kernel and security buffers excluded.\n" +
                                "Ordinary app tags are ignored. Host-emulation logs are omitted.\n" +
                                "NFC traces contain RF field/host/EE activity and payload lengths, not APDU content.\n" +
                                "NFC dumps include routing, discovery and the stack's available native/snoop logs.\n" +
                                "NFC-process logs and dumps may contain card identifiers or NFC transaction information.\n" +
                                "Retention: 24 hours; bounded files may rotate. Check capture.properties for wrap/errors.\n" +
                                "Only events emitted by the installed HAL/firmware can be collected; no RF response is inferred.\n" +
                                "HOST_DATA: value=technology extra=payload_length; DISCOVERY: value=host_routing extra=rf_enabled.\n")
                            .toByteArray(),
                )
            entries.putAll(files)
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    @Synchronized
    fun record(
        event: DiagnosticStore.Event,
        outcome: DiagnosticStore.Outcome,
        failure: AccessFailure?,
        hardware: HardwareState,
    ) {
        if (!preferences.state.value.debug) return
        try {
            cache()
                .record(
                    event,
                    outcome,
                    failure?.ordinal ?: -1,
                    hardware.mode.ordinal,
                    hardware.needsRecovery,
                    hardware.failedParameter,
                )
        } catch (_: Exception) {
            cacheFailed = true
        }
    }

    @Synchronized
    fun report(state: WalletState, capabilities: BackendCapabilities): String {
        if (!preferences.state.value.debug) throw IOException("Debug recording is disabled")
        val events =
            try {
                cache().export()
            } catch (_: Exception) {
                cacheFailed = true
                "Events unavailable: diagnostic cache could not be read.\n"
            }
        // Deliberate allowlist: never serialize WalletState, a Bundle, an exception or a card.
        return buildString {
            appendLine("AccessCard diagnostics v2")
            appendLine("created=${Instant.now()}")
            appendLine("app=${packageVersion(context.packageName)}")
            appendLine("nfc_app=${packageVersion("com.android.nfc")}")
            appendLine("device=${Build.MANUFACTURER} / ${Build.MODEL} / ${Build.DEVICE}")
            appendLine("build=${Build.FINGERPRINT}")
            appendLine(
                "sdk=${Build.VERSION.SDK_INT} security_patch=${Build.VERSION.SECURITY_PATCH}"
            )
            appendLine("qualification=${capabilities.qualification}")
            appendLine(
                "can_read=${capabilities.canReadIdentity} can_apply=${capabilities.canApplyIdentity} can_restore=${capabilities.canRestore}"
            )
            appendLine("allowed_uid_lengths=${capabilities.allowedUidLengths.sorted()}")
            appendLine("rf_verified_uid_lengths=${capabilities.verifiedUidLengths.sorted()}")
            appendLine("mode=${state.hardware.mode} operation=${state.operation}")
            appendLine(
                "recovery=${state.hardware.needsRecovery} generation=${state.hardware.generation}"
            )
            appendLine(
                "controller_failure=${state.hardware.failure} parameter=${state.hardware.failedParameter}"
            )
            appendLine("ui_failure=${state.message} storage_failed=${state.storageFailed}")
            appendLine("cache_write_failed=$cacheFailed")
            appendLine(
                "error_codes=${AccessFailure.entries.mapIndexed { i, e -> "$i:$e" }.joinToString()}"
            )
            appendLine(
                "mode_codes=${AccessMode.entries.mapIndexed { i, e -> "$i:$e" }.joinToString()}"
            )
            appendLine("Events: milliseconds since Unix epoch; no card identifiers or names")
            append(events)
        }
    }

    private fun packageVersion(name: String): String =
        try {
            context.packageManager.getPackageInfo(name, 0).let {
                "${it.versionName} (${it.longVersionCode})"
            }
        } catch (_: PackageManager.NameNotFoundException) {
            "unavailable"
        }
}
