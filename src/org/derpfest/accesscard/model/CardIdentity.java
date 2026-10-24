// SPDX-License-Identifier: Apache-2.0
package org.derpfest.accesscard.model;

import java.util.Arrays;
import java.util.Locale;

/** Immutable NFC-A identity. This is not a sector dump or a credential. */
public final class CardIdentity {
    private final byte[] uid;
    private final byte[] atqa;
    private final int sak;

    public CardIdentity(byte[] uid, byte[] atqa, int sak) {
        if (uid == null || (uid.length != 4 && uid.length != 7 && uid.length != 10)) {
            throw new IllegalArgumentException("Invalid UID length");
        }
        if (atqa == null || atqa.length != 2 || sak < 0 || sak > 255) {
            throw new IllegalArgumentException("Invalid NFC-A identity");
        }
        this.uid = uid.clone();
        this.atqa = atqa.clone();
        this.sak = sak;
    }

    public byte[] getUid() { return uid.clone(); }
    public byte[] getAtqa() { return atqa.clone(); }
    public int getSak() { return sak; }
    public String getUidHex() { return hex(uid); }
    public String getAtqaHex() { return hex(atqa); }
    public String getSakHex() { return String.format(Locale.ROOT, "%02X", sak); }
    public boolean isLegacyTypeA() { return (sak & 0x20) == 0; }

    public static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) result.append(String.format(Locale.ROOT, "%02X", b & 255));
        return result.toString();
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof CardIdentity)) return false;
        CardIdentity identity = (CardIdentity) other;
        return Arrays.equals(uid, identity.uid) && Arrays.equals(atqa, identity.atqa)
                && sak == identity.sak;
    }
    @Override public int hashCode() {
        return 31 * Arrays.hashCode(uid) + 17 * Arrays.hashCode(atqa) + sak;
    }
}
