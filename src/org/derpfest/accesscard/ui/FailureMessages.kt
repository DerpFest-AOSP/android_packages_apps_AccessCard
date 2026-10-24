// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.ui

import org.derpfest.accesscard.nfc.AccessFailure

val AccessFailure.messageRes: Int
    get() =
        when (this) {
            AccessFailure.STORAGE -> R.string.error_storage
            AccessFailure.DUPLICATE -> R.string.error_duplicate
            AccessFailure.UNSUPPORTED -> R.string.mode_unsupported
            AccessFailure.NFC_OFF -> R.string.mode_nfc_off
            AccessFailure.INVALID_CARD -> R.string.error_card_mode
            AccessFailure.BUSY -> R.string.error_busy
            AccessFailure.NEEDS_STOP -> R.string.reader_stop_detail
            AccessFailure.SERVICE_UNAVAILABLE -> R.string.error_service
            AccessFailure.SNAPSHOT_UNAVAILABLE -> R.string.error_snapshot
            AccessFailure.CONFIG_REJECTED -> R.string.error_rejected
            AccessFailure.READBACK_MISMATCH -> R.string.error_readback
            AccessFailure.TIMEOUT -> R.string.error_timeout
            AccessFailure.RECOVERY_REQUIRED -> R.string.error_restore
            AccessFailure.JOURNAL_UNAVAILABLE -> R.string.error_journal
            AccessFailure.DISCOVERY_FAILED -> R.string.error_discovery
            AccessFailure.READ_FAILED -> R.string.error_read
            AccessFailure.TAG_LOST -> R.string.error_tag_lost
            AccessFailure.TAG_TYPE -> R.string.error_tag_type
            AccessFailure.OPERATION_FAILED -> R.string.error_operation
        }
