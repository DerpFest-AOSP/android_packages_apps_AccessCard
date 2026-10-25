// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.tools

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import org.derpfest.accesscard.ui.AccessCardTheme

@Composable
fun AppTheme(preferences: AppPreferences, content: @Composable () -> Unit) {
    val settings by preferences.state.collectAsState()
    val dark = when (settings.theme) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
    AccessCardTheme(dark, content)
}
