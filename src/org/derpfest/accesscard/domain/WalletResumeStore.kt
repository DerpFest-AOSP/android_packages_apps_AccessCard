// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard

/** Remember user intent separately from the controller's actual state. */
data class WalletResumeState(val cardId: String? = null, val manuallyStopped: Boolean = false)

interface WalletResumeStore {
    fun load(): WalletResumeState

    fun save(state: WalletResumeState)
}
