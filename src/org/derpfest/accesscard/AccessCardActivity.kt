// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard

import androidx.activity.ComponentActivity

/** Scope identity emulation to the focused Activity, including multi-window focus changes. */
abstract class AccessCardActivity : ComponentActivity() {
    private val foregroundOwner = Any()
    private val controller
        get() = (application as AccessCardApplication).wallet

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (isTopResumedActivity) controller.coordinator.enterForeground(foregroundOwner)
        else controller.coordinator.leaveForeground(foregroundOwner)
    }

    override fun onPause() {
        controller.coordinator.leaveForeground(foregroundOwner)
        super.onPause()
    }
}
