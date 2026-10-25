// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.derpfest.accesscard.data.DiagnosticStore
import org.derpfest.accesscard.data.WalletBackup
import org.derpfest.accesscard.model.WalletSnapshot
import org.derpfest.accesscard.tools.*
import org.derpfest.accesscard.ui.*

class OptionsViewModel(application: Application) : AndroidViewModel(application) {
    val wallet = (application as AccessCardApplication).wallet
    private val pending = AtomicBoolean(false)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<Int?>(null)
    val bytes = MutableStateFlow(0L)

    init {
        refreshBytes()
    }

    fun refreshBytes() {
        wallet.scope.launch {
            bytes.value = runCatching { wallet.diagnostics.bytes() }.getOrDefault(0)
        }
    }

    fun perform(
        success: Int,
        error: Int = R.string.error_tools,
        action: suspend () -> Unit,
    ): Boolean {
        if (!pending.compareAndSet(false, true)) return false
        busy.value = true
        message.value = null
        wallet.scope.launch {
            try {
                action()
                message.value = success
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                message.value = error
            } finally {
                refreshBytes()
                busy.value = false
                pending.set(false)
            }
        }
        return true
    }

    fun debug(enabled: Boolean) =
        perform(R.string.preferences_saved) { wallet.diagnostics.configure(enabled) }

    fun theme(choice: ThemeChoice) =
        perform(R.string.preferences_saved) {
            wallet.preferences.update { it.copy(theme = choice) }
        }

    fun reminders(enabled: Boolean) =
        perform(R.string.preferences_saved) {
            wallet.preferences.update { it.copy(reminders = enabled) }
            if (!enabled) wallet.reminders.cancel()
        }

    fun clear() = perform(R.string.diagnostics_cleared) { wallet.diagnostics.clear() }

    fun export(uri: Uri) =
        perform(R.string.export_complete) {
            try {
                requireDocument(uri)
                wallet.refresh().join()
                val text =
                    wallet.diagnostics.report(wallet.state.value, wallet.backend.capabilities)
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                    it.flush()
                } ?: throw IOException("Document could not be opened")
            } catch (e: Exception) {
                deleteCreated(uri)
                throw e
            }
        }

    private var password: CharArray? = null
    val incoming = MutableStateFlow<WalletSnapshot?>(null)
    val importCounts = MutableStateFlow<Pair<Int, Int>?>(null)

    @Synchronized
    fun setPassword(value: CharArray) {
        password?.fill('\u0000')
        password = value.copyOf()
        value.fill('\u0000')
    }

    @Synchronized
    fun clearPassword() {
        password?.fill('\u0000')
        password = null
    }

    @Synchronized private fun takePassword(): CharArray? = password.also { password = null }

    override fun onCleared() {
        clearPassword()
        incoming.value = null
        super.onCleared()
    }

    fun dismissImport() {
        if (!busy.value) {
            incoming.value = null
            message.value = null
        }
    }

    fun backup(uri: Uri) {
        val secret = takePassword()
        if (secret == null) {
            message.value = R.string.error_password_expired
            wallet.scope.launch { deleteCreated(uri) }
            return
        }
        val accepted =
            perform(R.string.export_complete, R.string.error_backup_export) {
                try {
                    requireDocument(uri)
                    val snapshot = wallet.coordinator.snapshotForBackup()
                    val encrypted = WalletBackup.encrypt(snapshot, secret)
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(encrypted)
                        it.flush()
                    } ?: throw IOException("Document could not be opened")
                    wallet.diagnostics.record(
                        DiagnosticStore.Event.BACKUP,
                        DiagnosticStore.Outcome.SUCCEEDED,
                        null,
                        wallet.state.value.hardware,
                    )
                } catch (e: Exception) {
                    deleteCreated(uri)
                    wallet.diagnostics.record(
                        DiagnosticStore.Event.BACKUP,
                        DiagnosticStore.Outcome.FAILED,
                        null,
                        wallet.state.value.hardware,
                    )
                    throw e
                } finally {
                    secret.fill('\u0000')
                }
            }
        if (!accepted) {
            secret.fill('\u0000')
            wallet.scope.launch { deleteCreated(uri) }
        }
    }

    fun readBackup(uri: Uri) {
        val secret = takePassword()
        if (secret == null) {
            message.value = R.string.error_password_expired
            return
        }
        val accepted =
            perform(R.string.import_ready, R.string.error_backup_open) {
                try {
                    requireDocument(uri)
                    val encrypted =
                        getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                            it.readNBytes(WalletBackup.MAX_BYTES + 1)
                        } ?: throw IOException("Document could not be opened")
                    incoming.value = WalletBackup.decrypt(encrypted, secret)
                } finally {
                    secret.fill('\u0000')
                }
            }
        if (!accepted) secret.fill('\u0000')
    }

    fun confirmImport() {
        val snapshot = incoming.value ?: return
        perform(R.string.import_completed, R.string.error_import) {
            val result = wallet.coordinator.importCards(snapshot).await()
            importCounts.value = result.added to result.skipped
            incoming.value = null
        }
    }

    private fun requireDocument(uri: Uri) {
        if (uri.scheme != "content") throw IOException("Expected a document provider")
    }

    private fun deleteCreated(uri: Uri) {
        // Only called for a new ACTION_CREATE_DOCUMENT result, never an imported file.
        runCatching {
            DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, uri)
        }
    }
}

class OptionsActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[OptionsViewModel::class.java] }
    private val diagnosticFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
            it?.let(model::export)
        }
    private val backupFile =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) {
            if (it == null) model.clearPassword() else model.backup(it)
        }
    private val importFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) {
            if (it == null) model.clearPassword() else model.readBackup(it)
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) model.reminders(true)
            else model.message.value = R.string.error_reminder_permission
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val preferences by model.wallet.preferences.state.collectAsState()
            val busy by model.busy.collectAsState()
            val message by model.message.collectAsState()
            val bytes by model.bytes.collectAsState()
            val incoming by model.incoming.collectAsState()
            val counts by model.importCounts.collectAsState()
            val walletState by model.wallet.state.collectAsState()
            var passwordAction by remember { mutableStateOf<String?>(null) }
            AppTheme(model.wallet.preferences) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.safeDrawingPadding()
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ finish() }) {
                                Glyph(R.drawable.ic_back, stringResource(R.string.back))
                            }
                            Text(
                                stringResource(R.string.preferences),
                                style = MaterialTheme.typography.headlineSmall,
                            )
                        }
                        Text(
                            stringResource(R.string.theme),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        ThemeChoice.entries.forEach { choice ->
                            OutlinedButton(
                                { model.theme(choice) },
                                enabled = !busy,
                                modifier =
                                    Modifier.fillMaxWidth().semantics {
                                        selected = preferences.theme == choice
                                    },
                            ) {
                                if (preferences.theme == choice) {
                                    Glyph(R.drawable.ic_check)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    stringResource(
                                        when (choice) {
                                            ThemeChoice.SYSTEM -> R.string.theme_system
                                            ThemeChoice.LIGHT -> R.string.theme_light
                                            ThemeChoice.DARK -> R.string.theme_dark
                                        }
                                    )
                                )
                            }
                        }
                        HorizontalDivider()
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.reminder_preference),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Switch(
                                preferences.reminders,
                                { enabled ->
                                    if (
                                        enabled &&
                                            checkSelfPermission(
                                                Manifest.permission.POST_NOTIFICATIONS
                                            ) != PackageManager.PERMISSION_GRANTED
                                    )
                                        notificationPermission.launch(
                                            Manifest.permission.POST_NOTIFICATIONS
                                        )
                                    else model.reminders(enabled)
                                },
                                enabled = !busy,
                                modifier =
                                    Modifier.semantics {
                                        contentDescription = getString(R.string.reminder_preference)
                                    },
                            )
                        }
                        Text(
                            stringResource(R.string.reminder_detail),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton({
                            try {
                                startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                                )
                            } catch (_: Exception) {
                                model.message.value = R.string.error_tools
                            }
                        }) {
                            Text(stringResource(R.string.notification_settings))
                        }
                        HorizontalDivider()
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.debug_recording),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Switch(
                                preferences.debug,
                                { model.debug(it) },
                                enabled = !busy,
                                modifier =
                                    Modifier.semantics {
                                        contentDescription = getString(R.string.debug_recording)
                                    },
                            )
                        }
                        Text(
                            stringResource(R.string.debug_detail),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.diagnostic_size, bytes),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            {
                                try {
                                    diagnosticFile.launch("AccessCard-diagnostics.txt")
                                } catch (_: Exception) {
                                    model.message.value = R.string.error_tools
                                }
                            },
                            enabled = preferences.debug && !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.export_diagnostics))
                        }
                        OutlinedButton(
                            { model.clear() },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.clear_diagnostics))
                        }
                        Text(
                            stringResource(R.string.export_location_note),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        HorizontalDivider()
                        Text(
                            stringResource(R.string.backup_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(stringResource(R.string.backup_detail))
                        Button(
                            { passwordAction = "backup" },
                            enabled = !busy && !walletState.storageFailed && !walletState.loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.export_backup))
                        }
                        OutlinedButton(
                            { passwordAction = "import" },
                            enabled = !busy && !walletState.storageFailed,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.import_backup))
                        }
                        if (message == R.string.import_completed)
                            counts?.let {
                                Text(stringResource(R.string.import_counts, it.first, it.second))
                            }
                        HorizontalDivider()
                        Text(
                            stringResource(R.string.usage_rules),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(stringResource(R.string.usage_rules_detail))
                        if (busy)
                            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                        message?.let {
                            Text(stringResource(it), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                passwordAction?.let { action ->
                    BackupPasswordDialog(action == "backup", { passwordAction = null }) { secret ->
                        model.setPassword(secret)
                        passwordAction = null
                        try {
                            if (action == "backup") backupFile.launch("AccessCard-wallet.acbackup")
                            else importFile.launch(arrayOf("*/*"))
                        } catch (_: Exception) {
                            model.clearPassword()
                            model.message.value = R.string.error_tools
                        }
                    }
                }
                incoming?.let {
                    ImportReviewDialog(
                        it.cards.size,
                        busy,
                        message == R.string.error_import,
                        model::dismissImport,
                        model::confirmImport,
                    )
                }
            }
        }
    }
}
