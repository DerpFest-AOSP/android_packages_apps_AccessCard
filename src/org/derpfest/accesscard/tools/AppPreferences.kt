// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.tools

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeChoice {
    SYSTEM,
    LIGHT,
    DARK,
}

data class Preferences(
    val debug: Boolean = false,
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val reminders: Boolean = false,
)

class AppPreferences(context: Context) {
    private val file = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    private val mutable =
        MutableStateFlow(
            Preferences(
                file.getBoolean("debug", false),
                runCatching { ThemeChoice.valueOf(file.getString("theme", "SYSTEM")!!) }
                    .getOrDefault(ThemeChoice.SYSTEM),
                file.getBoolean("reminders", false),
            )
        )
    val state = mutable.asStateFlow()

    @Synchronized
    fun update(transform: (Preferences) -> Preferences) {
        val value = transform(mutable.value)
        if (
            !file
                .edit()
                .putBoolean("debug", value.debug)
                .putString("theme", value.theme.name)
                .putBoolean("reminders", value.reminders)
                .commit()
        )
            throw IOException("Preferences could not be saved")
        mutable.value = value
    }
}
