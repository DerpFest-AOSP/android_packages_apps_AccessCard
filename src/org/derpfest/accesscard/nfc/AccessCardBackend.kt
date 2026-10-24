// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.nfc

import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.NfcOemExtension
import android.os.Bundle
import org.derpfest.accesscard.R
import org.derpfest.accesscard.model.CardIdentity

/** One shared AOSP backend; SoC and product names never select a code path. */
class AospAccessCardBackend(context: Context) : AccessCardBackend {
    private val app = context.applicationContext
    private val adapter = NfcAdapter.getDefaultAdapter(app)
    private val rawQualification =
        app.resources.getInteger(R.integer.config_accesscard_qualification)
    private val stateGetter =
        runCatching { NfcOemExtension::class.java.getMethod("getNfcATagEmulationState") }
            .getOrNull()

    override val capabilities =
        BackendCapabilities(
            canReadIdentity =
                adapter != null && app.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC),
            canApplyIdentity = adapter != null && stateGetter != null && rawQualification in 1..2,
            canRestore = adapter != null && stateGetter != null,
            qualification =
                when (rawQualification) {
                    1 -> Qualification.EXPERIMENTAL
                    2 -> Qualification.VERIFIED
                    else -> Qualification.UNKNOWN
                },
            allowedUidLengths =
                app.resources
                    .getIntArray(R.array.config_accesscard_allowed_uid_lengths)
                    .filter { it == 4 || it == 7 || it == 10 }
                    .toSet(),
            verifiedUidLengths =
                app.resources
                    .getIntArray(R.array.config_accesscard_verified_uid_lengths)
                    .filter { it == 4 || it == 7 || it == 10 }
                    .toSet(),
        )

    override fun inspect(): HardwareState {
        val nfc = adapter ?: return HardwareState(AccessMode.UNSUPPORTED)
        if (!nfc.isEnabled) return HardwareState(AccessMode.NFC_OFF)
        val getter = stateGetter ?: return HardwareState(AccessMode.UNSUPPORTED)
        // The versioned read-only extension also permits safe degradation on older NFC modules.
        val state =
            try {
                getter.invoke(nfc.nfcOemExtension) as? Bundle
                    ?: throw AccessException(AccessFailure.SERVICE_UNAVAILABLE)
            } catch (e: Exception) {
                return HardwareState(AccessMode.ERROR, failure = AccessFailure.SERVICE_UNAVAILABLE)
            }
        val failure = AccessFailure.fromController(state.getString("failure_reason"))
        if (state.getInt("version") != 1) return HardwareState(AccessMode.UNSUPPORTED)
        if (state.getBoolean("transitioning")) return HardwareState(AccessMode.STOPPING)
        if (state.getBoolean("recovery_required")) {
            return HardwareState(
                AccessMode.ERROR,
                needsRecovery = true,
                failure = failure ?: AccessFailure.RECOVERY_REQUIRED,
            )
        }
        if (state.getBoolean("reading")) return HardwareState(AccessMode.READING, failure = failure)
        if (!state.getBoolean("active")) {
            return HardwareState(
                if (capabilities.canApplyIdentity) AccessMode.IDLE else AccessMode.UNSUPPORTED,
                generation = state.getLong("generation"),
                failure = failure,
            )
        }
        val uid = requireNotNull(state.getByteArray("uid"))
        val bitFrame = requireNotNull(state.getByteArray("bit_frame_sdd"))
        val platform = requireNotNull(state.getByteArray("platform_config"))
        val sak = requireNotNull(state.getByteArray("sel_info"))
        require(bitFrame.size == 1 && platform.size == 1 && sak.size == 1)
        val identity =
            CardIdentity(uid, byteArrayOf(platform[0], bitFrame[0]), sak[0].toInt() and 255)
        return HardwareState(
            AccessMode.ACTIVE,
            identity,
            state.getInt("owner_uid", -1),
            state.getLong("generation"),
        )
    }

    override fun canApply(identity: CardIdentity) =
        capabilities.canApplyIdentity &&
            identity.uid.size in capabilities.allowedUidLengths &&
            identity.isLegacyTypeA

    override fun apply(identity: CardIdentity): Boolean {
        if (!canApply(identity)) return false
        val nfc = adapter ?: return false
        if (!nfc.isEnabled) return false
        val atqa = identity.atqa
        // Match the stock parameter byte order. RATS is untouched for non-ISO-DEP identities.
        val result =
            try {
                nfc.nfcOemExtension.emulateNfcTechnologyATag(
                    true,
                    atqa[1],
                    atqa[0],
                    identity.sak.toByte(),
                    identity.uid,
                    0,
                    null,
                )
            } catch (e: Exception) {
                throw AccessException(AccessFailure.SERVICE_UNAVAILABLE)
            }
        return result == NfcOemExtension.EMULATE_NFC_A_TAG_STATUS_OK &&
            inspect().let { it.mode == AccessMode.ACTIVE && it.identity == identity }
    }

    override fun stop(): Boolean {
        val nfc = adapter ?: return false
        if (!nfc.isEnabled || !capabilities.canRestore) return false
        val result =
            try {
                nfc.nfcOemExtension.emulateNfcTechnologyATag(false, 0, 0, 0, byteArrayOf(), 0, null)
            } catch (e: Exception) {
                throw AccessException(AccessFailure.SERVICE_UNAVAILABLE)
            }
        val after = inspect()
        return result == NfcOemExtension.EMULATE_NFC_A_TAG_STATUS_OK &&
            after.mode != AccessMode.ACTIVE &&
            after.mode != AccessMode.ERROR
    }
}
