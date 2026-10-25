// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import org.derpfest.accesscard.model.CardTestRecord

@Composable
fun ManualTestDialog(onClose: () -> Unit, onSave: (CardTestRecord) -> Unit) {
    // Retain the user draft across Activity recreation; this is not a test result yet.
    var location by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var result by rememberSaveable { mutableStateOf(CardTestRecord.Result.INCONCLUSIVE) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.add_test_record)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.manual_test_note))
                OutlinedTextField(location, { if (it.length <= 80) location = it },
                    label = { Text(stringResource(R.string.test_location)) }, singleLine = true)
                CardTestRecord.Result.values().forEach { choice ->
                    FilterChip(result == choice, { result = choice },
                        label = { Text(stringResource(testResultLabel(choice))) })
                }
                OutlinedTextField(note, { if (it.length <= 256) note = it },
                    label = { Text(stringResource(R.string.test_note)) }, maxLines = 4)
            }
        },
        confirmButton = { TextButton({
            onSave(CardTestRecord(UUID.randomUUID().toString(), location, note, System.currentTimeMillis(), result))
        }, enabled = location.isNotBlank()) { Text(stringResource(R.string.save_changes)) } },
        dismissButton = { TextButton(onClose) { Text(stringResource(R.string.cancel)) } },
    )
}

fun testResultLabel(result: CardTestRecord.Result) = when (result) {
    CardTestRecord.Result.ACCEPTED -> R.string.test_accepted
    CardTestRecord.Result.REJECTED -> R.string.test_rejected
    CardTestRecord.Result.INCONCLUSIVE -> R.string.test_inconclusive
}

@Composable
fun ManualTestHistory(records: List<CardTestRecord>, enabled: Boolean, onDelete: (String) -> Unit) {
    Text(stringResource(R.string.manual_test_note), style = MaterialTheme.typography.bodySmall)
    if (records.isEmpty()) Text(stringResource(R.string.no_tests))
    records.forEach { record ->
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(record.location, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(testResultLabel(record.result)))
                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(record.testedAt)),
                    style = MaterialTheme.typography.bodySmall)
                if (record.note.isNotBlank()) Text(record.note)
                TextButton({ onDelete(record.id) }, enabled = enabled) { Text(stringResource(R.string.delete_test)) }
            }
        }
    }
}
