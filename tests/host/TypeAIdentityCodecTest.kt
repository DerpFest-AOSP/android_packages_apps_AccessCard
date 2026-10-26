// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.nfc

import org.derpfest.accesscard.model.CardIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TypeAIdentityCodecTest {
    private val uid = byteArrayOf(1, 2, 3, 4)

    @Test
    fun mapsClassicSensResToNciByteOneAndByteTwo() {
        val identity = CardIdentity(uid, byteArrayOf(0x04, 0x00), 0x08)

        assertEquals(0x04.toByte(), TypeAIdentityCodec.bitFrameSdd(identity))
        assertEquals(0x00.toByte(), TypeAIdentityCodec.platformConfig(identity))
    }

    @Test
    fun mapsControllerParametersBackToAndroidResponseOrder() {
        val identity = TypeAIdentityCodec.fromController(uid, 0x04, 0x00, 0x08)

        assertArrayEquals(byteArrayOf(0x04, 0x00), identity.atqa)
        assertEquals(CardIdentity(uid, byteArrayOf(0x04, 0x00), 0x08), identity)
        assertNotEquals(identity, TypeAIdentityCodec.fromController(uid, 0x00, 0x04, 0x08))
    }

    @Test
    fun preservesBothNonzeroAtqaBytesAndUnsignedSak() {
        val identity = CardIdentity(uid, byteArrayOf(0x84.toByte(), 0x03), 0x88)

        assertEquals(0x84.toByte(), TypeAIdentityCodec.bitFrameSdd(identity))
        assertEquals(0x03.toByte(), TypeAIdentityCodec.platformConfig(identity))
        assertEquals(
            identity,
            TypeAIdentityCodec.fromController(uid, 0x84.toByte(), 0x03, 0x88.toByte()),
        )
    }
}
