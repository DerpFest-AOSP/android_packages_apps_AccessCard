// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard

import org.derpfest.accesscard.model.*
import org.derpfest.accesscard.nfc.*

data class WalletState(
    val wallet: WalletSnapshot = WalletSnapshot.empty(),
    val hardware: HardwareState = HardwareState(AccessMode.IDLE),
    val loading: Boolean = true,
    val operation: AccessMode? = null,
    val storageFailed: Boolean = false,
    val message: AccessFailure? = null,
    val selectedCardId: String? = null,
) {
    val busy
        get() = operation != null || hardware.mode == AccessMode.READING

    val mode
        get() = operation?.takeUnless { it == AccessMode.IDLE } ?: hardware.mode

    val activeCard
        get() = wallet.cards.firstOrNull { it.identity == hardware.identity }
}
