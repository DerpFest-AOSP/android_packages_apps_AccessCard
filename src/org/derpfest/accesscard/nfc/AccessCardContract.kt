// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.nfc

import org.derpfest.accesscard.model.CardIdentity

enum class AccessMode {
    UNSUPPORTED,
    NFC_OFF,
    IDLE,
    READING,
    APPLYING,
    ACTIVE,
    STOPPING,
    ERROR,
}

enum class Qualification {
    UNKNOWN,
    EXPERIMENTAL,
    VERIFIED,
}

data class BackendCapabilities(
    val canReadIdentity: Boolean,
    val canApplyIdentity: Boolean,
    val canRestore: Boolean,
    val qualification: Qualification,
    val allowedUidLengths: Set<Int>,
    val verifiedUidLengths: Set<Int>,
)

data class HardwareState(
    val mode: AccessMode,
    val identity: CardIdentity? = null,
    val ownerUid: Int = -1,
    val generation: Long = 0,
    val needsRecovery: Boolean = false,
    val failure: AccessFailure? = null,
    val failedParameter: Int = -1,
)

interface AccessCardBackend {
    val capabilities: BackendCapabilities

    fun inspect(): HardwareState

    fun apply(identity: CardIdentity): Boolean

    fun stop(): Boolean

    fun canApply(identity: CardIdentity): Boolean
}

/** Safe, identifier-free errors shared by hardware, storage and UI. */
enum class AccessFailure {
    STORAGE,
    DUPLICATE,
    UNSUPPORTED,
    NFC_OFF,
    INVALID_CARD,
    BUSY,
    NEEDS_STOP,
    SERVICE_UNAVAILABLE,
    SNAPSHOT_UNAVAILABLE,
    CONFIG_REJECTED,
    READBACK_MISMATCH,
    TIMEOUT,
    RECOVERY_REQUIRED,
    JOURNAL_UNAVAILABLE,
    DISCOVERY_FAILED,
    READ_FAILED,
    TAG_LOST,
    TAG_TYPE,
    OPERATION_FAILED;

    companion object {
        fun fromController(value: String?): AccessFailure? =
            when (value) {
                "busy" -> BUSY
                "snapshot_unavailable" -> SNAPSHOT_UNAVAILABLE
                "config_rejected" -> CONFIG_REJECTED
                "readback_mismatch" -> READBACK_MISMATCH
                "timeout" -> TIMEOUT
                "rollback_failed",
                "recovery_required" -> RECOVERY_REQUIRED
                "journal_unavailable" -> JOURNAL_UNAVAILABLE
                "discovery_failed" -> DISCOVERY_FAILED
                else -> null
            }
    }
}

class AccessException(val failure: AccessFailure) : RuntimeException(failure.name)
