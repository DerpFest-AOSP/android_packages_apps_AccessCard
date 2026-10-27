// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.tools

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.derpfest.accesscard.WalletResumeState
import org.derpfest.accesscard.WalletResumeStore

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

class AppPreferences(context: Context) : WalletResumeStore {
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
    override fun load(): WalletResumeState =
        try {
            WalletResumeState(
                file.getString("last_used_card", null),
                file.getBoolean("manually_stopped", false),
            )
        } catch (e: ClassCastException) {
            throw IOException("Resume preferences could not be read", e)
        }

    @Synchronized
    override fun save(state: WalletResumeState) {
        if (
            !file
                .edit()
                .putString("last_used_card", state.cardId)
                .putBoolean("manually_stopped", state.manuallyStopped)
                .commit()
        )
            throw IOException("Resume preferences could not be saved")
    }

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
