// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.nfc

import org.derpfest.accesscard.model.CardIdentity

/** Maps NfcA.getAtqa() response bytes to the NCI listen parameters, without reversal. */
internal object TypeAIdentityCodec {
    // NCI LA_BIT_FRAME_SDD supplies SENS_RES byte 1; LA_PLATFORM_CONFIG supplies byte 2.
    fun bitFrameSdd(identity: CardIdentity): Byte = identity.atqa[0]

    fun platformConfig(identity: CardIdentity): Byte = identity.atqa[1]

    fun fromController(
        uid: ByteArray,
        bitFrameSdd: Byte,
        platformConfig: Byte,
        selInfo: Byte,
    ): CardIdentity =
        CardIdentity(uid, byteArrayOf(bitFrameSdd, platformConfig), selInfo.toInt() and 255)
}
