// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy

@Composable
fun BackupPasswordDialog(export: Boolean, onClose: () -> Unit, onContinue: (CharArray) -> Unit) {
    // Never save passwords to savedInstanceState, preferences, diagnostics or disk.
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = {
            Text(stringResource(if (export) R.string.export_backup else R.string.import_backup))
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.password_detail))
                OutlinedTextField(
                    password,
                    { if (it.length <= 256) password = it },
                    label = { Text(stringResource(R.string.backup_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (export)
                    OutlinedTextField(
                        confirmation,
                        { if (it.length <= 256) confirmation = it },
                        label = { Text(stringResource(R.string.confirm_password)) },
                        singleLine = true,
                        isError = confirmation.isNotEmpty() && confirmation != password,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
            }
        },
        confirmButton = {
            TextButton(
                {
                    val secret = password.toCharArray()
                    password = ""
                    confirmation = ""
                    onContinue(secret)
                },
                enabled = password.length in 12..256 && (!export || password == confirmation),
            ) {
                Text(stringResource(R.string.continue_action))
            }
        },
        dismissButton = { TextButton(onClose) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ImportReviewDialog(
    count: Int,
    busy: Boolean,
    failed: Boolean,
    onClose: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(stringResource(R.string.import_review)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.import_review_detail, count))
                if (failed)
                    Text(
                        stringResource(R.string.error_import),
                        color = MaterialTheme.colorScheme.error,
                    )
                if (busy) CircularProgressIndicator()
            }
        },
        confirmButton = {
            TextButton(onConfirm, enabled = !busy) { Text(stringResource(R.string.import_confirm)) }
        },
        dismissButton = {
            TextButton(onClose, enabled = !busy) { Text(stringResource(R.string.cancel)) }
        },
    )
}
