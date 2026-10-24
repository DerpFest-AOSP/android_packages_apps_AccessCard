// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard

import android.app.Application
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.TagLostException
import android.nfc.tech.NfcA
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import kotlinx.coroutines.*
import org.derpfest.accesscard.model.*
import org.derpfest.accesscard.nfc.*
import org.derpfest.accesscard.ui.*

class ReaderViewModel(application: Application, initial: ReaderState) :
    AndroidViewModel(application) {
    private val wallet = (application as AccessCardApplication).wallet
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val session =
        ReaderSession(wallet.coordinator, scope, initial) {
            application.getString(R.string.default_name, it)
        }

    override fun onCleared() {
        // Close the connection and release the shared lease before cancelling its owner.
        wallet.scope.launch {
            session.pause().join()
            scope.cancel()
        }
    }
}

class ReaderActivity : ComponentActivity() {
    private val wallet
        get() = (application as AccessCardApplication).wallet

    private val adapter by lazy { NfcAdapter.getDefaultAdapter(this) }
    private lateinit var model: ReaderViewModel
    private var readerToken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initial = restoreDraft(savedInstanceState)
        model =
            ViewModelProvider(
                this,
                object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        return ReaderViewModel(application, initial) as T
                    }
                },
            )[ReaderViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    while (isActive) {
                        wallet.refresh().join()
                        val state = model.session.state.value
                        if (adapter?.isEnabled != true)
                            state.token?.let {
                                model.session.readerFailed(it, AccessFailure.NFC_OFF)
                            }
                        delay(1000)
                    }
                }
                model.session.state.collect { state ->
                    if (
                        state.phase == ReaderPhase.WAITING &&
                            state.token != null &&
                            state.token != readerToken
                    ) {
                        enableReader(requireNotNull(state.token))
                    } else if (
                        state.phase != ReaderPhase.WAITING && state.phase != ReaderPhase.READING
                    ) {
                        disableReader()
                    }
                    if (state.phase == ReaderPhase.SAVED) {
                        setResult(
                            RESULT_OK,
                            Intent().putExtra(EXTRA_SELECTED_CARD, state.savedCardId),
                        )
                        Toast.makeText(
                                this@ReaderActivity,
                                R.string.reader_saved,
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                        finish()
                    }
                }
            }
        }
        setContent { AccessCardTheme { ReaderScreen() } }
    }

    override fun onResume() {
        super.onResume()
        model.session.resume()
    }

    override fun onPause() {
        disableReader()
        model.session.pause()
        super.onPause()
    }

    private fun disableReader() {
        readerToken = null
        runCatching { adapter?.disableReaderMode(this) }
    }

    private fun enableReader(token: String) {
        readerToken = token
        try {
            val nfc = adapter ?: throw AccessException(AccessFailure.UNSUPPORTED)
            if (!nfc.isEnabled) throw AccessException(AccessFailure.NFC_OFF)
            nfc.enableReaderMode(
                this,
                { tag ->
                    val tech = NfcA.get(tag)
                    model.session.onTag(
                        token,
                        object : IdentityReader {
                            override fun read(): CardIdentity {
                                if (tech == null) throw AccessException(AccessFailure.TAG_TYPE)
                                try {
                                    tech.connect()
                                    return CardIdentity(tag.id, tech.atqa, tech.sak.toInt() and 255)
                                } catch (e: TagLostException) {
                                    throw AccessException(AccessFailure.TAG_LOST)
                                } catch (e: IllegalArgumentException) {
                                    throw AccessException(AccessFailure.TAG_TYPE)
                                }
                            }

                            override fun close() {
                                tech?.close()
                            }
                        },
                    )
                },
                NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                null,
            )
        } catch (e: Exception) {
            disableReader()
            model.session.readerFailed(
                token,
                (e as? AccessException)?.failure ?: AccessFailure.READ_FAILED,
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val state = model.session.state.value
        outState.putString("draft_id", state.draftId)
        outState.putString("name", state.name)
        outState.putInt("color", state.color)
        outState.putInt("icon", state.icon)
        state.identity?.let {
            outState.putByteArray("uid", it.uid)
            outState.putByteArray("atqa", it.atqa)
            outState.putInt("sak", it.sak)
        }
        super.onSaveInstanceState(outState)
    }

    private fun restoreDraft(saved: Bundle?): ReaderState {
        if (saved == null) return ReaderState()
        val identity =
            runCatching {
                    CardIdentity(
                        requireNotNull(saved.getByteArray("uid")),
                        requireNotNull(saved.getByteArray("atqa")),
                        saved.getInt("sak"),
                    )
                }
                .getOrNull()
        val draftId =
            runCatching { java.util.UUID.fromString(saved.getString("draft_id")).toString() }
                .getOrElse { java.util.UUID.randomUUID().toString() }
        return ReaderState(
            draftId = draftId,
            identity = identity,
            phase = if (identity == null) ReaderPhase.PREPARING else ReaderPhase.FOUND,
            name = saved.getString("name", ""),
            color = saved.getInt("color"),
            icon = saved.getInt("icon"),
        )
    }

    @Composable
    private fun ReaderScreen() {
        val state by model.session.state.collectAsState()
        val identity = state.identity
        val error = state.error?.messageRes
        val saving = state.phase == ReaderPhase.SAVING
        val reading = state.phase == ReaderPhase.READING
        val preparing = state.phase == ReaderPhase.PREPARING
        val waitingForStop = state.phase == ReaderPhase.NEEDS_STOP
        val name = state.name
        val color = state.color
        val icon = state.icon
        BackHandler(enabled = saving) {}
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.safeDrawingPadding()
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ finish() }, enabled = !saving) {
                        Glyph(R.drawable.ic_back, stringResource(R.string.back))
                    }
                    Text(
                        stringResource(
                            if (reading) R.string.reader_reading
                            else if (identity == null) R.string.reader_title
                            else R.string.reader_found
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                val card = identity
                if (card == null) {
                    Surface(
                        Modifier.align(Alignment.CenterHorizontally).padding(vertical = 28.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
                            Glyph(R.drawable.ic_nfc, modifier = Modifier.size(70.dp))
                        }
                    }
                    Text(
                        stringResource(R.string.reader_hint),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (preparing || reading)
                        CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    if (error != null)
                        TextButton({ model.session.resume() }) {
                            Text(stringResource(R.string.try_again))
                        }
                    if (adapter?.isEnabled != true)
                        TextButton({ startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }) {
                            Text(stringResource(R.string.nfc_settings))
                        }
                } else {
                    val preview =
                        AccessCardProfile(
                            "00000000-0000-0000-0000-000000000000",
                            name.ifBlank { getString(R.string.card_name) },
                            card,
                            color,
                            icon,
                            0,
                        )
                    CardFace(preview, false, false, Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.reader_found_detail),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    AppearanceEditor(
                        name,
                        color,
                        icon,
                        { model.session.rename(it) },
                        { model.session.color(it) },
                        { model.session.icon(it) },
                        !saving,
                    )
                    Button(
                        { model.session.save() },
                        enabled = name.isNotBlank() && !saving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                    ) {
                        if (saving) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.saving))
                        } else Text(stringResource(R.string.save))
                    }
                }
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                Text(
                    stringResource(R.string.identity_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (waitingForStop)
            AlertDialog(
                onDismissRequest = ::finish,
                title = { Text(stringResource(R.string.reader_stop_title)) },
                text = { Text(stringResource(R.string.reader_stop_detail)) },
                confirmButton = {
                    TextButton({ model.session.stopAndRead() }) {
                        Text(stringResource(R.string.stop_and_read))
                    }
                },
                dismissButton = {
                    TextButton({ finish() }) { Text(stringResource(R.string.cancel)) }
                },
            )
    }
}
