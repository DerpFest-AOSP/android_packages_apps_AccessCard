// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.NfcA
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.lifecycle.lifecycleScope
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.derpfest.accesscard.data.CardStore
import org.derpfest.accesscard.model.*
import org.derpfest.accesscard.nfc.AccessMode
import org.derpfest.accesscard.ui.*

class ReaderActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    private val wallet
        get() = (application as AccessCardApplication).wallet

    private val adapter by lazy { NfcAdapter.getDefaultAdapter(this) }
    private val accepting = AtomicBoolean(false)
    private var identity by mutableStateOf<CardIdentity?>(null)
    private var error by mutableStateOf<Int?>(null)
    private var waitingForStop by mutableStateOf(false)
    private var preparing by mutableStateOf(true)
    private var reading by mutableStateOf(false)
    private var saving by mutableStateOf(false)
    private var name by mutableStateOf("")
    private var color by mutableIntStateOf(0)
    private var icon by mutableIntStateOf(0)
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        savedInstanceState?.let {
            name = it.getString("name", "")
            color = it.getInt("color")
            icon = it.getInt("icon")
            val uid = it.getByteArray("uid")
            val atqa = it.getByteArray("atqa")
            if (uid != null && atqa != null)
                identity = runCatching { CardIdentity(uid, atqa, it.getInt("sak")) }.getOrNull()
        }
        setContent { AccessCardTheme { ReaderScreen() } }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (identity == null && !saving) prepare()
    }

    override fun onPause() {
        resumed = false
        accepting.set(false)
        runCatching { adapter?.disableReaderMode(this) }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("name", name)
        outState.putInt("color", color)
        outState.putInt("icon", icon)
        identity?.let {
            outState.putByteArray("uid", it.uid)
            outState.putByteArray("atqa", it.atqa)
            outState.putInt("sak", it.sak)
        }
        super.onSaveInstanceState(outState)
    }

    private fun prepare() {
        preparing = true
        reading = false
        error = null
        lifecycleScope.launch {
            val hardware =
                withContext(Dispatchers.IO) { runCatching { wallet.backend.inspect() }.getOrNull() }
            if (!resumed) return@launch
            preparing = false
            when {
                adapter == null -> error = R.string.mode_unsupported
                adapter?.isEnabled != true -> error = R.string.mode_nfc_off
                hardware?.mode == AccessMode.ACTIVE -> waitingForStop = true
                hardware?.needsRecovery == true -> error = R.string.mode_recovery
                else -> startReader()
            }
        }
    }

    private fun startReader() {
        if (!resumed || adapter?.isEnabled != true) {
            error = R.string.mode_nfc_off
            return
        }
        waitingForStop = false
        error = null
        accepting.set(true)
        try {
            adapter?.enableReaderMode(
                this,
                this,
                NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                null,
            )
        } catch (_: Exception) {
            accepting.set(false)
            error = R.string.error_read
        }
    }

    override fun onTagDiscovered(tag: Tag) {
        if (!accepting.compareAndSet(true, false)) return
        lifecycleScope.launch {
            reading = true
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val tech = NfcA.get(tag) ?: throw IllegalArgumentException("Not NFC-A")
                        tech.use {
                            it.connect()
                            CardIdentity(tag.id, it.atqa, it.sak.toInt() and 255)
                        }
                    }
                }
            reading = false
            if (!resumed) return@launch
            result
                .onSuccess {
                    identity = it
                    name =
                        getString(R.string.default_name, wallet.state.value.wallet.cards.size + 1)
                    runCatching { adapter?.disableReaderMode(this@ReaderActivity) }
                }
                .onFailure {
                    error =
                        when (it) {
                            is IllegalArgumentException -> R.string.error_tag_type
                            is TagLostException -> R.string.error_tag_lost
                            else -> R.string.error_read
                        }
                }
        }
    }

    private fun save() {
        val card = identity ?: return
        if (saving || name.isBlank()) return
        saving = true
        error = null
        lifecycleScope.launch {
            try {
                val added = wallet.add(name, card, color, icon)
                setResult(RESULT_OK, Intent().putExtra(EXTRA_SELECTED_CARD, added.id))
                Toast.makeText(this@ReaderActivity, R.string.reader_saved, Toast.LENGTH_SHORT)
                    .show()
                // The QS reader is a separate task; the launcher will refresh from durable storage.
                finish()
            } catch (_: CardStore.DuplicateCardException) {
                error = R.string.error_duplicate
            } catch (_: Exception) {
                error = R.string.error_storage
            } finally {
                saving = false
            }
        }
    }

    @Composable
    private fun ReaderScreen() {
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
                        TextButton({ prepare() }) { Text(stringResource(R.string.try_again)) }
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
                        { name = it },
                        { color = it },
                        { icon = it },
                        !saving,
                    )
                    Button(
                        ::save,
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
                    TextButton({
                        waitingForStop = false
                        lifecycleScope.launch {
                            wallet.stop().join()
                            prepare()
                        }
                    }) {
                        Text(stringResource(R.string.stop_and_read))
                    }
                },
                dismissButton = {
                    TextButton({ finish() }) { Text(stringResource(R.string.cancel)) }
                },
            )
    }
}
