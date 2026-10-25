// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard

import android.app.Application
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.derpfest.accesscard.tools.*
import org.derpfest.accesscard.ui.Glyph

class OptionsViewModel(application: Application) : AndroidViewModel(application) {
    val wallet = (application as AccessCardApplication).wallet
    private val pending = AtomicBoolean(false)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<Int?>(null)
    val bytes = MutableStateFlow(0L)
    init { refreshBytes() }
    fun refreshBytes() { wallet.scope.launch { bytes.value = runCatching { wallet.diagnostics.bytes() }.getOrDefault(0) } }
    fun perform(success: Int, action: suspend () -> Unit) {
        if (!pending.compareAndSet(false, true)) return
        busy.value = true
        message.value = null
        wallet.scope.launch {
            try { action(); message.value = success }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message.value = R.string.error_tools }
            finally { refreshBytes(); busy.value = false; pending.set(false) }
        }
    }
    fun debug(enabled: Boolean) = perform(R.string.preferences_saved) { wallet.diagnostics.configure(enabled) }
    fun theme(choice: ThemeChoice) = perform(R.string.preferences_saved) {
        wallet.preferences.update(wallet.preferences.state.value.copy(theme = choice))
    }
    fun clear() = perform(R.string.diagnostics_cleared) { wallet.diagnostics.clear() }
    fun export(uri: Uri) = perform(R.string.export_complete) {
        wallet.refresh().join()
        val text = wallet.diagnostics.report(wallet.state.value, wallet.backend.capabilities)
        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
            it.write(text.toByteArray(Charsets.UTF_8)); it.flush()
        } ?: throw IOException("Document could not be opened")
    }
}

class OptionsActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[OptionsViewModel::class.java] }
    private val diagnosticFile = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
        it?.let(model::export)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val preferences by model.wallet.preferences.state.collectAsState()
            val busy by model.busy.collectAsState()
            val message by model.message.collectAsState()
            val bytes by model.bytes.collectAsState()
            AppTheme(model.wallet.preferences) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ finish() }) { Glyph(R.drawable.ic_back, stringResource(R.string.back)) }
                            Text(stringResource(R.string.preferences), style = MaterialTheme.typography.headlineSmall)
                        }
                        Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleMedium)
                        ThemeChoice.entries.forEach { choice ->
                            OutlinedButton({ model.theme(choice) }, enabled = !busy,
                                modifier = Modifier.fillMaxWidth()) {
                                if (preferences.theme == choice) { Glyph(R.drawable.ic_check); Spacer(Modifier.width(8.dp)) }
                                Text(stringResource(when(choice) {
                                    ThemeChoice.SYSTEM -> R.string.theme_system
                                    ThemeChoice.LIGHT -> R.string.theme_light
                                    ThemeChoice.DARK -> R.string.theme_dark
                                }))
                            }
                        }
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.debug_recording), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Switch(preferences.debug, { model.debug(it) }, enabled = !busy)
                        }
                        Text(stringResource(R.string.debug_detail), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.diagnostic_size, bytes), style = MaterialTheme.typography.bodySmall)
                        Button({ diagnosticFile.launch("AccessCard-diagnostics.txt") },
                            enabled = preferences.debug && !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.export_diagnostics))
                        }
                        OutlinedButton({ model.clear() }, enabled = !busy,
                            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.clear_diagnostics)) }
                        Text(stringResource(R.string.export_location_note), style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        Text(stringResource(R.string.usage_rules), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.usage_rules_detail))
                        if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                        message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }
    }
}
