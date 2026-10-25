// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.tools

import android.view.WindowInsetsController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import org.derpfest.accesscard.ui.AccessCardTheme

@Composable
fun AppTheme(preferences: AppPreferences, content: @Composable () -> Unit) {
    val settings by preferences.state.collectAsState()
    val dark =
        when (settings.theme) {
            ThemeChoice.SYSTEM -> isSystemInDarkTheme()
            ThemeChoice.LIGHT -> false
            ThemeChoice.DARK -> true
        }
    val view = LocalView.current
    SideEffect {
        val mask =
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        view.windowInsetsController?.setSystemBarsAppearance(if (dark) 0 else mask, mask)
    }
    AccessCardTheme(dark, content)
}
