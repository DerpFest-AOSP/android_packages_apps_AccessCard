// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.tools

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.IOException
import java.time.Instant
import org.derpfest.accesscard.WalletState
import org.derpfest.accesscard.data.DiagnosticStore
import org.derpfest.accesscard.nfc.*

/** All methods run on the wallet IO scope. Debug off never writes a diagnostic event. */
class Diagnostics(private val context: Context, private val preferences: AppPreferences) {
    private var store: DiagnosticStore? = null
    private var cacheFailed = false
    @Synchronized fun initialize() { cache() }
    private fun cache(): DiagnosticStore {
        return store ?: DiagnosticStore(File(context.cacheDir, "diagnostics"), System::currentTimeMillis)
            .also { it.setEnabled(preferences.state.value.debug); store = it }
    }
    @Synchronized fun configure(enabled: Boolean) {
        preferences.update(preferences.state.value.copy(debug = enabled))
        try { cache().setEnabled(enabled); cacheFailed = false }
        catch (e: IOException) { cacheFailed = true; throw e }
    }
    @Synchronized fun clear() { cache().clear(); cacheFailed = false }
    @Synchronized fun bytes(): Long = cache().size()
    @Synchronized fun record(event: DiagnosticStore.Event, outcome: DiagnosticStore.Outcome,
            failure: AccessFailure?, hardware: HardwareState) {
        if (!preferences.state.value.debug) return
        try {
            cache().record(event, outcome, failure?.ordinal ?: -1, hardware.mode.ordinal,
                hardware.needsRecovery, hardware.failedParameter)
        } catch (_: Exception) { cacheFailed = true }
    }
    @Synchronized fun report(state: WalletState, capabilities: BackendCapabilities): String {
        if (!preferences.state.value.debug) throw IOException("Debug recording is disabled")
        // Deliberate allowlist: never serialize WalletState, a Bundle, an exception or a card.
        return buildString {
            appendLine("AccessCard diagnostics v1")
            appendLine("created=${Instant.now()}")
            appendLine("app=${packageVersion(context.packageName)}")
            appendLine("nfc_app=${packageVersion("com.android.nfc")}")
            appendLine("device=${Build.MANUFACTURER} / ${Build.MODEL} / ${Build.DEVICE}")
            appendLine("build=${Build.FINGERPRINT}")
            appendLine("sdk=${Build.VERSION.SDK_INT} security_patch=${Build.VERSION.SECURITY_PATCH}")
            appendLine("qualification=${capabilities.qualification}")
            appendLine("can_read=${capabilities.canReadIdentity} can_apply=${capabilities.canApplyIdentity} can_restore=${capabilities.canRestore}")
            appendLine("allowed_uid_lengths=${capabilities.allowedUidLengths.sorted()}")
            appendLine("rf_verified_uid_lengths=${capabilities.verifiedUidLengths.sorted()}")
            appendLine("mode=${state.hardware.mode} operation=${state.operation}")
            appendLine("recovery=${state.hardware.needsRecovery} generation=${state.hardware.generation}")
            appendLine("controller_failure=${state.hardware.failure} parameter=${state.hardware.failedParameter}")
            appendLine("ui_failure=${state.message} storage_failed=${state.storageFailed}")
            appendLine("cache_write_failed=$cacheFailed")
            appendLine("error_codes=${AccessFailure.entries.mapIndexed { i, e -> "$i:$e" }.joinToString()}")
            appendLine("mode_codes=${AccessMode.entries.mapIndexed { i, e -> "$i:$e" }.joinToString()}")
            appendLine("Events: milliseconds since Unix epoch; no card identifiers or names")
            append(runCatching { cache().export() }.getOrDefault("Events unavailable: diagnostic cache could not be read.\n"))
        }
    }
    private fun packageVersion(name: String): String = try {
        context.packageManager.getPackageInfo(name, 0).let { "${it.versionName} (${it.longVersionCode})" }
    } catch (_: PackageManager.NameNotFoundException) { "unavailable" }
}
